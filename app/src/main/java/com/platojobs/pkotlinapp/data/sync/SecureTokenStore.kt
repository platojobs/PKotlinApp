package com.platojobs.pkotlinapp.data.sync

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 匿名登录 token 只写入 EncryptedSharedPreferences（MasterKey 由 Android Keystore 保护），
 * 不落明文数据库，也不写入日志。
 */
class SecureTokenStore(context: Context) : TokenStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun token(): String? = prefs.getString(KEY_TOKEN, null)

    /** token 与到期时间必须同一次编辑写入：并发刷新时不会出现「新 token + 旧到期时间」。 */
    @Synchronized
    override fun saveToken(token: String) {
        prefs.edit { putString(KEY_TOKEN, token) }
    }

    @Synchronized
    override fun saveSession(token: String, expiresAt: Long?) {
        prefs.edit {
            putString(KEY_TOKEN, token)
            if (expiresAt != null && expiresAt > 0) {
                putLong(KEY_EXPIRES_AT, expiresAt)
            } else {
                remove(KEY_EXPIRES_AT)
            }
        }
    }

    override fun sessionExpiresAt(): Long? =
        prefs.getLong(KEY_EXPIRES_AT, 0L).takeIf { it > 0 }

    override fun reloginRequired(): Boolean = prefs.getBoolean(KEY_RELOGIN_REQUIRED, false)

    @Synchronized
    override fun setReloginRequired(required: Boolean) {
        prefs.edit { putBoolean(KEY_RELOGIN_REQUIRED, required) }
    }

    /** 只清 token 与到期时间；reloginRequired 由调用方显式维护，避免刷新失败后又被自动登录。 */
    @Synchronized
    override fun clear() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_EXPIRES_AT)
        }
    }

    private companion object {
        const val PREFS_NAME = "sync_auth"
        const val KEY_TOKEN = "anonymous_token"
        const val KEY_EXPIRES_AT = "token_expires_at"
        const val KEY_RELOGIN_REQUIRED = "relogin_required"
    }
}
