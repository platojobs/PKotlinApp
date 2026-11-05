package com.platojobs.pkotlinapp.data.sync

import android.content.Context
import androidx.core.content.edit
import com.platojobs.pkotlinapp.domain.sync.SyncCursorStore
import com.platojobs.pkotlinapp.domain.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** 游标 / 安装标识 / 同步状态等非敏感信息。 */
class SyncPrefs(context: Context) : SyncCursorStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var cursor: String?
        get() = prefs.getString(KEY_CURSOR, null)
        set(value) {
            prefs.edit { putString(KEY_CURSOR, value) }
        }

    val installId: String
        get() {
            val existing = prefs.getString(KEY_INSTALL_ID, null)
            if (existing != null) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit { putString(KEY_INSTALL_ID, generated) }
            return generated
        }

    private companion object {
        const val PREFS_NAME = "sync_state"
        const val KEY_CURSOR = "cursor"
        const val KEY_INSTALL_ID = "install_id"
    }
}

/**
 * 同步状态存储：连续失败达到阈值后进入纯本地模式，界面据此提示。
 */
class SyncStatusStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SyncStatus> = _state.asStateFlow()

    fun recordSuccess(lastSyncAt: Long, conflicts: Int) {
        update(
            SyncStatus(
                lastSyncAt = lastSyncAt,
                pendingCount = _state.value.pendingCount,
                consecutiveFailures = 0,
                lastConflictCount = conflicts,
                localOnly = false,
                authExpired = false,
                lastError = null,
                // 全量重同步提示保留到下一次手动重置，避免刷新后立刻消失。
                resyncedAt = _state.value.resyncedAt,
            ),
        )
    }

    /**
     * 410 游标失效：记录「已从 0 全量重同步」的时间，界面据此提示，不静默。
     * 该分支不是失败，不计入连续失败次数，也不进入纯本地模式。
     */
    fun recordResync(at: Long = System.currentTimeMillis()) {
        update(
            _state.value.copy(
                consecutiveFailures = 0,
                localOnly = false,
                resyncedAt = at,
            ),
        )
    }

    /**
     * 401 登录失效：只标记暂停，不计入连续失败阈值，也不清空本地数据与游标。
     */
    fun recordAuthExpired(message: String = AUTH_EXPIRED_MESSAGE) {
        update(
            _state.value.copy(
                authExpired = true,
                lastError = message,
            ),
        )
    }

    fun recordFailure(message: String) {
        val failures = _state.value.consecutiveFailures + 1
        update(
            _state.value.copy(
                consecutiveFailures = failures,
                localOnly = failures >= LOCAL_ONLY_THRESHOLD,
                lastError = message,
            ),
        )
    }

    fun resetForManualSync() {
        update(
            _state.value.copy(
                consecutiveFailures = 0,
                localOnly = false,
                lastError = null,
            ),
        )
    }

    /** 用户确认重新登录后清除失效态；本地数据与 lastSyncAt 保留。 */
    fun resetForRelogin() {
        update(
            _state.value.copy(
                consecutiveFailures = 0,
                localOnly = false,
                authExpired = false,
                lastError = null,
            ),
        )
    }

    fun withPendingCount(pendingCount: Int): SyncStatus =
        _state.value.copy(pendingCount = pendingCount)

    private fun update(newState: SyncStatus) {
        _state.value = newState
        prefs.edit {
            putLong(KEY_LAST_SYNC_AT, newState.lastSyncAt ?: -1L)
            putInt(KEY_FAILURES, newState.consecutiveFailures)
            putInt(KEY_CONFLICTS, newState.lastConflictCount)
            putBoolean(KEY_LOCAL_ONLY, newState.localOnly)
            putBoolean(KEY_AUTH_EXPIRED, newState.authExpired)
            putString(KEY_LAST_ERROR, newState.lastError)
            putLong(KEY_RESYNCED_AT, newState.resyncedAt ?: -1L)
        }
    }

    private fun read(): SyncStatus {
        val lastSync = prefs.getLong(KEY_LAST_SYNC_AT, -1L).takeIf { it > 0 }
        return SyncStatus(
            lastSyncAt = lastSync,
            consecutiveFailures = prefs.getInt(KEY_FAILURES, 0),
            lastConflictCount = prefs.getInt(KEY_CONFLICTS, 0),
            localOnly = prefs.getBoolean(KEY_LOCAL_ONLY, false),
            authExpired = prefs.getBoolean(KEY_AUTH_EXPIRED, false),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
            resyncedAt = prefs.getLong(KEY_RESYNCED_AT, -1L).takeIf { it > 0 },
        )
    }

    private companion object {
        const val PREFS_NAME = "sync_status"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_CONFLICTS = "last_conflict_count"
        const val KEY_LOCAL_ONLY = "local_only"
        const val KEY_AUTH_EXPIRED = "auth_expired"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_RESYNCED_AT = "resynced_at"
        const val LOCAL_ONLY_THRESHOLD = 3
        const val AUTH_EXPIRED_MESSAGE = "登录已失效，同步已暂停"
    }
}
