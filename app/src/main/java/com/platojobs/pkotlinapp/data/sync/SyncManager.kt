package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.local.DeviceDao
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
        statusStore.resetForRelogin()
        try {
            val outcome = engine.syncAll()
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
}

private fun Exception.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
