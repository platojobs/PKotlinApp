package com.platojobs.pkotlinapp.data.feedback

import com.platojobs.pkotlinapp.data.local.FeedbackDao
import com.platojobs.pkotlinapp.data.local.FeedbackDraftEntity
import com.platojobs.pkotlinapp.domain.feedback.FeedbackCategory
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 将来接入反馈接口时实现；当前版本保持为空，草稿只留本机。 */
fun interface FeedbackRemote {
    suspend fun submit(draft: FeedbackDraft)
}

/** 一次提交/重试的真实结果：sent=false 表示仍留在本地待发队列。 */
data class FeedbackSubmission(val draftId: Long, val sent: Boolean)

/**
 * 本地反馈队列：
 * 1. 提交先落库（state=pending），失败不丢内容；
 * 2. 没有远端接口时如实返回 `sent=false`，由 UI 标注「未发送」；
 * 3. 接入远端后 [retry] 复用同一条草稿，成功才标记 sent。
 */
class FeedbackStore(
    private val dao: FeedbackDao,
    private val remote: FeedbackRemote? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observePending(): Flow<List<FeedbackDraft>> =
        dao.observePending().map { rows -> rows.map(FeedbackDraftEntity::toDomain) }

    suspend fun submit(
        category: FeedbackCategory,
        description: String,
        contact: String?,
    ): FeedbackSubmission {
        val id = dao.insert(
            FeedbackDraftEntity(
                category = category.name,
                description = description.trim(),
                contact = contact?.trim()?.takeIf { it.isNotBlank() },
                createdAt = clock(),
            ),
        )
        return deliver(id)
    }

    suspend fun retry(id: Long): FeedbackSubmission = deliver(id)

    suspend fun discard(id: Long) {
        dao.delete(id)
    }

    private suspend fun deliver(id: Long): FeedbackSubmission {
        val row = dao.find(id) ?: return FeedbackSubmission(draftId = id, sent = false)
        val remote = remote ?: return FeedbackSubmission(draftId = id, sent = false)
        return try {
            remote.submit(row.toDomain())
            dao.markSent(id)
            FeedbackSubmission(draftId = id, sent = true)
        } catch (e: Exception) {
            FeedbackSubmission(draftId = id, sent = false)
        }
    }
}

private fun FeedbackDraftEntity.toDomain(): FeedbackDraft = FeedbackDraft(
    id = id,
    category = FeedbackCategory.entries.firstOrNull { it.name == category } ?: FeedbackCategory.OTHER,
    description = description,
    contact = contact,
    createdAt = createdAt,
    unsent = state == "pending",
)
