package com.platojobs.pkotlinapp.data.sync

/** 匿名登录 token 存储抽象，便于 JVM 单测替换 Android Keystore 实现。 */
interface TokenStore {
    fun token(): String?

    fun saveToken(token: String)

    /**
     * 保存 token 与服务端返回的到期时间（epoch millis）。
     * 默认实现忽略到期时间，便于测试替身保持简单。
     */
    fun saveSession(token: String, expiresAt: Long?) {
        saveToken(token)
    }

    /** token 到期时间；未登录或服务端未返回时为 null。 */
    fun sessionExpiresAt(): Long? = null

    /**
     * 刷新失败 / 主动退出后置为 true：此时**禁止**自动新建匿名账号，
     * 必须由用户显式选择「用配对码恢复原账号」或「新建匿名账号」。
     */
    fun reloginRequired(): Boolean = false

    fun setReloginRequired(required: Boolean) {}

    /** 只清 token 与到期时间，保留 reloginRequired 标记。 */
    fun clear()
}
