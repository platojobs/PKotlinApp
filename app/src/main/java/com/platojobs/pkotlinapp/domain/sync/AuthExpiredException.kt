package com.platojobs.pkotlinapp.domain.sync

/**
 * 匿名登录 token 已失效（服务端返回 401）。
 *
 * 处理原则与 iOS 一致：
 * - 停止同步，保留本地数据与同步游标，不自动重新匿名登录；
 * - 由用户在「我的」页确认后重新登录，避免静默切换到另一个匿名账号。
 */
class AuthExpiredException(
    message: String = "登录已失效",
    cause: Throwable? = null,
) : Exception(message, cause)
