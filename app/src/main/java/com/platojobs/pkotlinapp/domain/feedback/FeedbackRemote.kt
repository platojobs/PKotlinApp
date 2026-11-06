package com.platojobs.pkotlinapp.domain.feedback

/**
 * 反馈远端能力。
 *
 * [submit] 必须在 2xx 且拿到非空 feedbackId 时才正常返回；
 * 其它情况抛出 [FeedbackRateLimitedException] / [FeedbackConflictException] /
 * [FeedbackRejectedException] / 网络异常，由 [FeedbackStore] 归一化并落库。
 */
interface FeedbackRemote {

    suspend fun submit(draft: FeedbackDraft, context: FeedbackContext): String

    suspend fun listMine(cursor: String?, limit: Int): FeedbackPage

    /** `GET /feedback/{id}`（仅本人）。 */
    suspend fun detail(id: String): FeedbackDetail

    /** `PATCH /feedback/{id}` 关闭本人反馈；已关闭再改抛 [FeedbackAlreadyClosedException]。 */
    suspend fun close(id: String): FeedbackDetail
}
