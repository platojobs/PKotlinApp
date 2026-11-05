package com.platojobs.pkotlinapp.domain.feedback

/** 反馈类型；只用于归类，不参与任何服务端枚举猜测。 */
enum class FeedbackCategory {
    BUG,
    CONNECTION,
    SYNC,
    OTHER,
}

/** 一条本地反馈草稿；[unsent] 为真表示仍停留在待发队列。 */
data class FeedbackDraft(
    val id: Long,
    val category: FeedbackCategory,
    val description: String,
    val contact: String?,
    val createdAt: Long,
    val unsent: Boolean,
)
