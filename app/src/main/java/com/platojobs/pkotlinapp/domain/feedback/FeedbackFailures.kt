package com.platojobs.pkotlinapp.domain.feedback

import java.io.IOException

/** 反馈接口的机读错误码，与后端 `error.code` 对齐。 */
object FeedbackErrorCodes {
    const val RATE_LIMITED = "FEEDBACK_RATE_LIMITED"
    const val IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val BAD_REQUEST = "BAD_REQUEST"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val TIMEOUT = "TIMEOUT"

    /** 本地归一化的网络类错误。 */
    const val NETWORK = "NETWORK"
    const val UNKNOWN = "UNKNOWN"
}

/** 429：账号或 IP 维度超限，带服务端建议等待秒数。 */
class FeedbackRateLimitedException(
    val retryAfterSeconds: Long?,
    message: String? = null,
) : Exception(message ?: FeedbackErrorCodes.RATE_LIMITED)

/** 409：同一幂等键被用于不同请求体，重试没有意义。 */
class FeedbackConflictException(
    val code: String?,
    message: String? = null,
) : Exception(message ?: FeedbackErrorCodes.IDEMPOTENCY_CONFLICT)

/** 400/413 等请求被服务端拒绝：内容或长度不合规，自动重试同样没有意义。 */
class FeedbackRejectedException(
    val code: String?,
    message: String? = null,
) : Exception(message ?: code ?: FeedbackErrorCodes.BAD_REQUEST)

/**
 * 一次失败的可持久化描述：
 * - [code] 机读错误码，UI 据此分支（429 展示「稍后重试」）；
 * - [message] 服务端返回的可读原因，[code] 缺失时作为兜底文案；
 * - [retryable] 网络/5xx/429 可再试，4xx 校验类不可自动重试；
 * - [retryAfterSeconds] 服务端建议等待秒数。
 */
data class FeedbackFailure(
    val code: String,
    val message: String?,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
)

/** 把异常归一化为可落库的失败描述；不解析后端 message 做机读判断，只作为展示兜底。 */
fun classifyFeedbackFailure(error: Throwable): FeedbackFailure = when (error) {
    is FeedbackRateLimitedException -> FeedbackFailure(
        code = FeedbackErrorCodes.RATE_LIMITED,
        message = error.message,
        retryable = true,
        retryAfterSeconds = error.retryAfterSeconds,
    )

    is FeedbackConflictException -> FeedbackFailure(
        code = error.code ?: FeedbackErrorCodes.IDEMPOTENCY_CONFLICT,
        message = error.message,
        retryable = false,
    )

    is FeedbackRejectedException -> FeedbackFailure(
        code = error.code ?: FeedbackErrorCodes.BAD_REQUEST,
        message = error.message,
        retryable = false,
    )

    is com.platojobs.pkotlinapp.domain.sync.AuthExpiredException -> FeedbackFailure(
        code = FeedbackErrorCodes.UNAUTHORIZED,
        message = error.message,
        retryable = false,
    )

    is com.platojobs.pkotlinapp.domain.sync.ServerBusyException -> FeedbackFailure(
        code = FeedbackErrorCodes.UNAVAILABLE,
        message = error.message,
        retryable = true,
    )

    is IOException -> FeedbackFailure(
        code = FeedbackErrorCodes.NETWORK,
        message = error.message,
        retryable = true,
    )

    else -> FeedbackFailure(
        code = FeedbackErrorCodes.UNKNOWN,
        message = error.message,
        retryable = true,
    )
}
