package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.ApiErrorCodes
import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import com.platojobs.pkotlinapp.domain.sync.CursorExpiredException
import com.platojobs.pkotlinapp.domain.sync.ServerBusyException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 同步失败分类。UI 只展示对应类别的可读文案，不直接把底层异常 message 拼给用户。
 *
 * [RESYNC] 与 [AUTH]、[BUSY] 是三个独立分支：
 * - AUTH（401）：暂停同步，等用户确认重新登录；
 * - RESYNC（410 `RESYNC_REQUIRED`）：清游标后 `cursor=0` 全量重拉一次，不进指数退避；
 * - BUSY（503 `TIMEOUT`）：服务端繁忙，按指数退避重试。
 */
enum class SyncErrorKind {
    NETWORK,
    TIMEOUT,
    AUTH,
    RESYNC,
    BUSY,
    CONFLICT,
    SERVER,
    UNKNOWN,
}

fun classifySyncError(error: Throwable): SyncErrorKind = when {
    error is AuthExpiredException -> SyncErrorKind.AUTH
    error is CursorExpiredException -> SyncErrorKind.RESYNC
    error is ServerBusyException -> SyncErrorKind.BUSY
    error is SocketTimeoutException -> SyncErrorKind.TIMEOUT
    error is UnknownHostException || error is ConnectException -> SyncErrorKind.NETWORK
    error is HttpException -> classifyHttpStatus(error.code())
    // 其余 IO 异常（断网、连接中断等）统一归为网络问题。
    error is IOException -> SyncErrorKind.NETWORK
    else -> SyncErrorKind.UNKNOWN
}

/** 错误码 → 分类；用于测试映射表完整性，也可供只有响应体的调用方使用。 */
fun classifyApiErrorCode(code: String?): SyncErrorKind? = when (code) {
    ApiErrorCodes.UNAUTHORIZED -> SyncErrorKind.AUTH
    ApiErrorCodes.RESYNC_REQUIRED -> SyncErrorKind.RESYNC
    ApiErrorCodes.TIMEOUT -> SyncErrorKind.BUSY
    ApiErrorCodes.IDEMPOTENCY_CONFLICT -> SyncErrorKind.CONFLICT
    ApiErrorCodes.RATE_LIMITED, ApiErrorCodes.UNAVAILABLE -> SyncErrorKind.BUSY
    else -> null
}

private fun classifyHttpStatus(status: Int): SyncErrorKind = when (status) {
    401, 403 -> SyncErrorKind.AUTH
    410 -> SyncErrorKind.RESYNC
    409 -> SyncErrorKind.CONFLICT
    503 -> SyncErrorKind.BUSY
    else -> SyncErrorKind.SERVER
}
