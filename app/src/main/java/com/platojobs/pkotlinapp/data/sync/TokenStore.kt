package com.platojobs.pkotlinapp.data.sync

/** 匿名登录 token 存储抽象，便于 JVM 单测替换 Android Keystore 实现。 */
interface TokenStore {
    fun token(): String?

    fun saveToken(token: String)

    fun clear()
}
