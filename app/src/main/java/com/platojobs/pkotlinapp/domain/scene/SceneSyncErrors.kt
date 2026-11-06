package com.platojobs.pkotlinapp.domain.scene

/**
 * 服务端 4xx 终态拒绝（401/403/408/429 除外）：重试没有意义。
 *
 * 场景会停在 `rejected`，只有用户修改内容后编辑语句才重置回 pending；
 * 避免一条永久不合法的场景无限挂在待上传队列里。
 */
class SceneRejectedException(
    val apiCode: String?,
    val serverMessage: String? = null,
) : RuntimeException(serverMessage ?: apiCode ?: "scene rejected")

/**
 * 可重试失败：网络错误、5xx、408、429。
 * 429 的 `Retry-After` 秒数写入本地，到点前不再次尝试。
 */
class SceneRetryableException(
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : RuntimeException(cause)

/** 失败分类结果：决定落 `rejected` 还是保持 `pending` 等待重试。 */
sealed interface SceneFailure {
    data class Terminal(val code: String?, val message: String?) : SceneFailure
    data class Retryable(val retryAfterSeconds: Long?) : SceneFailure

    companion object {
        fun of(error: Throwable): SceneFailure = when (error) {
            is SceneRejectedException -> Terminal(error.apiCode, error.serverMessage)
            is SceneRetryableException -> Retryable(error.retryAfterSeconds)
            // IOException / 5xx / 未知错误默认可重试，宁可下次再试也不永久丢弃。
            else -> Retryable(null)
        }
    }
}
