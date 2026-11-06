package com.platojobs.pkotlinapp.domain.feedback

/**
 * 反馈类型，对齐后端契约 `kind`：bug / feature / experience / other。
 *
 * 旧版本本地曾使用 CONNECTION / SYNC 两个本机分类；读取旧草稿时一律回落为 [OTHER]，
 * 内容原样保留，不会因为枚举变更丢失用户输入。
 */
enum class FeedbackKind(val wire: String) {
    BUG("bug"),
    FEATURE("feature"),
    EXPERIENCE("experience"),
    OTHER("other"),
    ;

    companion object {
        fun parse(value: String?): FeedbackKind {
            if (value.isNullOrBlank()) return OTHER
            return entries.firstOrNull {
                it.wire.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: OTHER
        }
    }
}

/** 本地草稿同步状态机：pending → sending → sent / failed。 */
enum class FeedbackState {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    ;

    companion object {
        fun parse(value: String?): FeedbackState =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}

/**
 * 提交上下文：由客户端自动填充，不允许硬编码。
 * 字段名与后端 `FeedbackCreate.context` 一一对应。
 */
data class FeedbackContext(
    val platform: String = "android",
    val appVersion: String,
    val buildType: String,
    val osVersion: String,
    val deviceModel: String,
)

/**
 * 一条本地反馈草稿。
 *
 * [retryCount] 是已失败的发送次数；[lastError] 保留服务端可读原因，
 * [lastErrorCode] 供 429/409 等分支做机读判断，[retryAfterSeconds] 来自 `Retry-After`。
 */
data class FeedbackDraft(
    val id: Long,
    val kind: FeedbackKind,
    val description: String,
    val contact: String?,
    val createdAt: Long,
    val state: FeedbackState = FeedbackState.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val lastErrorCode: String? = null,
    val retryAfterSeconds: Long? = null,
    val remoteFeedbackId: String? = null,
    val lastAttemptAt: Long? = null,
) {
    /** 只有真正拿到远端 feedbackId 才算已发送。 */
    val sent: Boolean get() = state == FeedbackState.SENT && remoteFeedbackId != null

    val unsent: Boolean get() = !sent

    /** 幂等键直接使用草稿本地 ID：断网重试始终命中同一条服务端记录。 */
    val idempotencyKey: String get() = id.toString()
}

/** 服务端反馈状态：submitted / received / closed。 */
enum class FeedbackStatus(val wire: String) {
    SUBMITTED("submitted"),
    RECEIVED("received"),
    CLOSED("closed"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun parse(value: String?): FeedbackStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: UNKNOWN
    }
}

/** `GET /feedback/mine` 的列表项；契约保证不返回 contact 与 diagnostics。 */
data class FeedbackItem(
    val feedbackId: String,
    val kind: FeedbackKind,
    val description: String,
    val status: FeedbackStatus,
    val createdAt: Long,
)

/** `GET /feedback/{id}` 详情：含状态说明与更新时间，但不含 contact / diagnostics。 */
data class FeedbackDetail(
    val feedbackId: String,
    val kind: FeedbackKind,
    val description: String,
    val status: FeedbackStatus,
    val statusMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 关闭自己的反馈时，若已经关闭（409 FEEDBACK_INVALID_TRANSITION）。 */
class FeedbackAlreadyClosedException(message: String? = null) : RuntimeException(message)

data class FeedbackPage(
    val items: List<FeedbackItem>,
    val nextCursor: String?,
    val hasMore: Boolean,
) {
    companion object {
        val EMPTY = FeedbackPage(items = emptyList(), nextCursor = null, hasMore = false)
    }
}

/** 一次提交/重试的结果快照，UI 只按真实状态展示，不自行编造成功。 */
data class FeedbackSubmission(
    val draftId: Long,
    val state: FeedbackState,
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryAfterSeconds: Long? = null,
    val remoteFeedbackId: String? = null,
) {
    val sent: Boolean get() = state == FeedbackState.SENT && remoteFeedbackId != null
}
