package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import com.platojobs.pkotlinapp.domain.sync.CursorExpiredException
import com.platojobs.pkotlinapp.domain.sync.ServerBusyException
import retrofit2.HttpException

/**
 * 后端错误码（`{"error":{"code":"..."}}`）。
 *
 * 客户端只基于 [SyncErrorKind] 分支处理，展示文案与 error.message 解耦；
 * 这张表与 `PJGoIoT/api/openapi.yaml` 的 ErrorCode 枚举保持一致。
 */
object ApiErrorCodes {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val RESYNC_REQUIRED = "RESYNC_REQUIRED"
    const val TIMEOUT = "TIMEOUT"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val NOT_FOUND = "NOT_FOUND"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val BAD_REQUEST = "BAD_REQUEST"
    const val UNAVAILABLE = "UNAVAILABLE"

    /** 409：owner 带成员退出等通用冲突。 */
    const val CONFLICT = "CONFLICT"

    /** 409：整段房间重排集合不一致（v0.14.0 起可作为机读判定，无需解析 message）。 */
    const val ROOM_SET_MISMATCH = "ROOM_SET_MISMATCH"
}

data class ApiErrorDto(
    val code: String? = null,
    val message: String? = null,
)

data class ApiErrorEnvelope(
    val error: ApiErrorDto? = null,
)

/**
 * 读取一次错误响应体里的 `code`；body 只能消费一次，因此必须在抛出异常时调用，
 * 不能等到 UI 分类时再读。
 */
internal fun HttpException.apiErrorCode(): String? = runCatching {
    val raw = response()?.errorBody()?.string().orEmpty()
    if (raw.isBlank()) {
        null
    } else {
        ApiClient.moshi().adapter(ApiErrorEnvelope::class.java).fromJson(raw)?.error?.code
    }
}.getOrNull()

/**
 * 把 HTTP 异常转换成同步领域的细分异常：
 * - 401/403 → 登录失效（暂停，等用户确认重登）；
 * - 410 或 `RESYNC_REQUIRED` → 游标失效（终态，全量重同步一次）；
 * - 503 或 `TIMEOUT` → 服务端繁忙（可重试，指数退避）。
 *
 * 其它错误返回 null，由调用方按原异常处理。
 */
internal fun HttpException.toSyncException(): Exception? {
    val apiCode = apiErrorCode()
    return when {
        code() == 401 || code() == 403 || apiCode == ApiErrorCodes.UNAUTHORIZED ->
            AuthExpiredException(cause = this)

        code() == 410 || apiCode == ApiErrorCodes.RESYNC_REQUIRED ->
            CursorExpiredException(cause = this)

        code() == 503 || apiCode == ApiErrorCodes.TIMEOUT ->
            ServerBusyException(cause = this)

        else -> null
    }
}
