package com.platojobs.pkotlinapp.data.prefs

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name).orEmpty())
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
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

    private companion object {
        const val PREFS_NAME = "app_settings"
        const val KEY_THEME = "theme_mode"
    }
}
