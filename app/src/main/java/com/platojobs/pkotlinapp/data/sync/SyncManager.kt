package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.local.DeviceDao
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
 */
class SyncManager(
    dao: DeviceDao,
    private val engine: SyncEngine,
    private val statusStore: SyncStatusStore,
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
        try {
            engine.pullChanges()
            statusStore.recordSuccess(lastSyncAt = System.currentTimeMillis(), conflicts = 0)
            true
        } catch (e: Exception) {
            statusStore.recordFailure(e.readableMessage())
            false
        }
    }

    /** 推送 + 拉取；失败向上抛给 WorkManager 决定 retry。 */
    suspend fun syncNow(): SyncOutcome = mutex.withLock {
        try {
            val outcome = engine.syncAll()
            statusStore.recordSuccess(
                lastSyncAt = System.currentTimeMillis(),
                conflicts = outcome.conflicts.size,
            )
            outcome
        } catch (e: Exception) {
            statusStore.recordFailure(e.readableMessage())
            throw e
        }
    }

    /** 手动同步：先重置降级状态，再尝试一次完整同步。 */
    suspend fun manualSync(): SyncOutcome {
        statusStore.resetForManualSync()
        return syncNow()
    }

    fun isLocalOnly(): Boolean = status.value.localOnly
}

private fun Exception.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
