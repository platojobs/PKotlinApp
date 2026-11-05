package com.platojobs.pkotlinapp.data.notification

import com.platojobs.pkotlinapp.domain.rule.AlertStateStore
import androidx.core.content.edit
import android.content.Context

/** 告警节流与恢复状态落 SharedPreferences，进程重启后仍能去重。 */
class AlertStatePrefs(context: Context) : AlertStateStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastNotifiedAt(key: String): Long =
        prefs.getLong(KEY_LAST + key, 0L)

    override fun putLastNotifiedAt(key: String, timestamp: Long) {
        prefs.edit { putLong(KEY_LAST + key, timestamp) }
    }

    override fun isPendingRecovery(key: String): Boolean =
        prefs.getBoolean(KEY_PENDING + key, false)

    override fun putPendingRecovery(key: String, pending: Boolean) {
        prefs.edit { putBoolean(KEY_PENDING + key, pending) }
    }

    private companion object {
        const val PREFS_NAME = "alert_notification_state"
        const val KEY_LAST = "last:"
        const val KEY_PENDING = "pending:"
    }
}
