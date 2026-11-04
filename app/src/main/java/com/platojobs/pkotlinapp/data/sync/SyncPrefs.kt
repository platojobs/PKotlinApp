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
                lastError = null,
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

    fun withPendingCount(pendingCount: Int): SyncStatus =
        _state.value.copy(pendingCount = pendingCount)

    private fun update(newState: SyncStatus) {
        _state.value = newState
        prefs.edit {
            putLong(KEY_LAST_SYNC_AT, newState.lastSyncAt ?: -1L)
            putInt(KEY_FAILURES, newState.consecutiveFailures)
            putInt(KEY_CONFLICTS, newState.lastConflictCount)
            putBoolean(KEY_LOCAL_ONLY, newState.localOnly)
            putString(KEY_LAST_ERROR, newState.lastError)
        }
    }

    private fun read(): SyncStatus {
        val lastSync = prefs.getLong(KEY_LAST_SYNC_AT, -1L).takeIf { it > 0 }
        return SyncStatus(
            lastSyncAt = lastSync,
            consecutiveFailures = prefs.getInt(KEY_FAILURES, 0),
            lastConflictCount = prefs.getInt(KEY_CONFLICTS, 0),
            localOnly = prefs.getBoolean(KEY_LOCAL_ONLY, false),
            lastError = prefs.getString(KEY_LAST_ERROR, null),
        )
    }

    private companion object {
        const val PREFS_NAME = "sync_status"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_FAILURES = "consecutive_failures"
        const val KEY_CONFLICTS = "last_conflict_count"
        const val KEY_LOCAL_ONLY = "local_only"
        const val KEY_LAST_ERROR = "last_error"
        const val LOCAL_ONLY_THRESHOLD = 3
    }
}
