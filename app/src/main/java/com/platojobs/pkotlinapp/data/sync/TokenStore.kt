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

    fun clear()
}
