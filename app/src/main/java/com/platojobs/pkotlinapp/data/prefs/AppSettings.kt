package com.platojobs.pkotlinapp.data.prefs

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** 设备列表排序方式；默认最近出现，持久化在偏好设置里。 */
enum class DeviceSortMode {
    RECENT,
    NAME,
    RSSI,
    ADDED,
}

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name).orEmpty())
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            prefs.edit { putString(KEY_THEME, value.name) }
        }

    /** 告警通知总开关；关闭后不投递通知，但规则命中仍会记录。 */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS_ENABLED, true)
        set(value) {
            prefs.edit { putBoolean(KEY_ALERTS_ENABLED, value) }
        }

    /** 同一设备同一规则的通知节流窗口，只允许 1 / 5 / 30 分钟。 */
    var alertThrottleMinutes: Int
        get() = prefs.getInt(KEY_ALERT_THROTTLE_MINUTES, DEFAULT_ALERT_THROTTLE_MINUTES)
            .takeIf { it in ALERT_THROTTLE_OPTIONS }
            ?: DEFAULT_ALERT_THROTTLE_MINUTES
        set(value) {
            val normalized = value.takeIf { it in ALERT_THROTTLE_OPTIONS }
                ?: DEFAULT_ALERT_THROTTLE_MINUTES
            prefs.edit { putInt(KEY_ALERT_THROTTLE_MINUTES, normalized) }
        }

    /** 告警静音截止时间（毫秒）；场景「静音告警 N 分钟」写入，投递器读取。 */
    var alertsMutedUntil: Long
        get() = prefs.getLong(KEY_ALERTS_MUTED_UNTIL, 0L)
        set(value) {
            prefs.edit { putLong(KEY_ALERTS_MUTED_UNTIL, value) }
        }

    fun muteAlertsFor(minutes: Int, now: Long = System.currentTimeMillis()) {
        alertsMutedUntil = now + minutes.coerceAtLeast(1) * 60_000L
    }

    fun alertsMuted(now: Long = System.currentTimeMillis()): Boolean = alertsMutedUntil > now

    var deviceSortMode: DeviceSortMode
        get() = runCatching {
            DeviceSortMode.valueOf(
                prefs.getString(KEY_DEVICE_SORT, DeviceSortMode.RECENT.name).orEmpty(),
            )
        }.getOrDefault(DeviceSortMode.RECENT)
        set(value) {
            prefs.edit { putString(KEY_DEVICE_SORT, value.name) }
        }

    /** 后台采集开关：连接设备后是否用前台服务保持 notify 采集。 */
    var backgroundCollectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_COLLECTION, false)
        set(value) {
            prefs.edit { putBoolean(KEY_BACKGROUND_COLLECTION, value) }
        }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }

    companion object {
        val ALERT_THROTTLE_OPTIONS = listOf(1, 5, 30)
        const val DEFAULT_ALERT_THROTTLE_MINUTES = 5

        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ALERTS_ENABLED = "alerts_enabled"
        private const val KEY_ALERT_THROTTLE_MINUTES = "alert_throttle_minutes"
        private const val KEY_DEVICE_SORT = "device_sort_mode"
        private const val KEY_BACKGROUND_COLLECTION = "background_collection_enabled"
        private const val KEY_ALERTS_MUTED_UNTIL = "alerts_muted_until"
    }
}
