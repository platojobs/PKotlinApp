package com.platojobs.pkotlinapp.domain.sync

/**
 * 服务端繁忙（503 `TIMEOUT`，查询超过服务端超时预算）。
 *
 * 可重试：保持 pending 与游标，按指数退避重试；不得按 401 暂停，
 * 也不得触发 410 的全量重同步。
 */
class ServerBusyException(
    message: String = "服务端繁忙，稍后重试",
    cause: Throwable? = null,
) : Exception(message, cause)
