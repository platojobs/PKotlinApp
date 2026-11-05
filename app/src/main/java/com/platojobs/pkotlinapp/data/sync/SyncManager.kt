package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.domain.household.HouseholdSyncer
import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import com.platojobs.pkotlinapp.domain.sync.SyncCursorStore
import com.platojobs.pkotlinapp.domain.sync.SyncEngine
import com.platojobs.pkotlinapp.domain.sync.SyncOutcome
import com.platojobs.pkotlinapp.domain.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 同步编排：冷启动增量拉取、后台批推、失败降级与状态汇总。
 *
 * 401（登录失效）是唯一的「暂停」状态：保留本地数据与游标，不自动重新登录，
 * 由用户确认后调用 [relogin] 换新匿名账号。
 */
class SyncManager(
    private val dao: DeviceDao,
    private val engine: SyncEngine,
    private val statusStore: SyncStatusStore,
    private val tokenStore: TokenStore,
    private val cursorStore: SyncCursorStore,
    private val readingSyncer: ReadingSyncer,
    /** 家庭同步（可选）；失败只记录家庭状态，不阻塞设备/读数同步。 */
    private val householdSyncer: HouseholdSyncer? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    val status: StateFlow<SyncStatus> = combine(
        statusStore.state,
        dao.observePendingCount(),
    ) { state, pending ->
        state.copy(pendingCount = pending)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = statusStore.state.value,
    )

    private val mutex = Mutex()

    /** 冷启动先拉增量；失败不抛给 UI，只记录状态，保证仍可渲染本地数据。 */
    suspend fun coldStartPull(): Boolean = mutex.withLock {
        if (statusStore.state.value.authExpired) return@withLock false
        try {
            engine.pullChanges()
            applyStructuralSafely()
            if (engine.lastPullWasResync) {
                // 410 全量重拉后，本机 pending 读数仍然有效，立即尝试补传一次；
                // 失败（如离线）不影响首屏，pending 标记保留到下一次后台同步。
                runCatching { readingSyncer.syncPending() }
            }
            syncHouseholdSafely()
            statusStore.recordSuccess(lastSyncAt = System.currentTimeMillis(), conflicts = 0)
            true
        } catch (e: AuthExpiredException) {
            statusStore.recordAuthExpired()
            false
        } catch (e: Exception) {
            statusStore.recordFailure(e.readableMessage())
            false
        }
    }

    /** 推送 + 拉取；失败向上抛给 WorkManager 决定 retry。 */
    suspend fun syncNow(): SyncOutcome = mutex.withLock {
        if (statusStore.state.value.authExpired) throw AuthExpiredException()
        try {
            val outcome = engine.syncAll()
            applyStructuralSafely()
            // 设备先落服务端，再补传读数；同一 push 契约、独立 200/批的幂等批次。
            readingSyncer.syncPending()
            syncHouseholdSafely()
            statusStore.recordSuccess(
                lastSyncAt = System.currentTimeMillis(),
                conflicts = outcome.conflicts.size,
            )
            outcome
        } catch (e: AuthExpiredException) {
            statusStore.recordAuthExpired()
            throw e
        } catch (e: Exception) {
            statusStore.recordFailure(e.readableMessage())
            throw e
        }
    }

    /** 手动同步：先重置降级状态，再尝试一次完整同步；登录失效时会立即失败并提示重新登录。 */
    suspend fun manualSync(): SyncOutcome {
        statusStore.resetForManualSync()
        return syncNow()
    }

    /**
     * 用户确认后重新登录：
     * - 清除失效 token，换新的匿名账号；
     * - 清空同步游标（新账号的序列号空间是全新的）；
     * - 本机记录重新排队推送到新账号；
     * - 旧账号数据不会被删除或覆盖。
     */
    suspend fun relogin(): SyncOutcome = mutex.withLock {
        tokenStore.clear()
        cursorStore.cursor = null
        dao.markAllPending()
        // 新匿名账号的家庭空间与旧账号无关：清空本地家庭映射，本机房间保留待重新合并。
        householdSyncer?.onRelogin()
        statusStore.resetForRelogin()
        try {
            val outcome = engine.syncAll()
            applyStructuralSafely()
            readingSyncer.syncPending()
            syncHouseholdSafely()
            statusStore.recordSuccess(
                lastSyncAt = System.currentTimeMillis(),
                conflicts = outcome.conflicts.size,
            )
            outcome
        } catch (e: AuthExpiredException) {
            statusStore.recordAuthExpired()
            throw e
        } catch (e: Exception) {
            statusStore.recordFailure(e.readableMessage())
            throw e
        }
    }

    fun isLocalOnly(): Boolean = status.value.localOnly

    fun isAuthExpired(): Boolean = status.value.authExpired

    /**
     * 结构变更先落家庭仓库，再由 [syncHouseholdSafely] 拉全量基线。
     * 失败不阻塞设备/读数同步：未知或异常的家庭状态交给下一次同步修复。
     */
    private suspend fun applyStructuralSafely() {
        val changes = engine.lastStructuralChanges
        if (changes.isEmpty()) return
        try {
            householdSyncer?.applyStructural(changes)
        } catch (_: Exception) {
            // 与家庭同步一致的降级策略：保留本机数据，不阻断主同步链路。
        }
    }

    private suspend fun syncHouseholdSafely() {
        try {
            householdSyncer?.onSync()
        } catch (_: Exception) {
            // 家庭接口不可用（离线/后端未部署）时保留本机房间与设备，仅由家庭模块标记未同步。
        }
    }
}

private fun Exception.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

