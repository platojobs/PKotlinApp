package com.platojobs.pkotlinapp.data.feedback

import com.platojobs.pkotlinapp.data.remote.FeedbackContextDto
import com.platojobs.pkotlinapp.data.remote.FeedbackCreateRequest
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.apiErrorDetails
import com.platojobs.pkotlinapp.data.remote.toSyncException
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.feedback.FeedbackConflictException
import com.platojobs.pkotlinapp.domain.feedback.FeedbackContext
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDraft
import com.platojobs.pkotlinapp.domain.feedback.FeedbackErrorCodes
import com.platojobs.pkotlinapp.domain.feedback.FeedbackItem
import com.platojobs.pkotlinapp.domain.feedback.FeedbackKind
import com.platojobs.pkotlinapp.domain.feedback.FeedbackPage
import com.platojobs.pkotlinapp.domain.feedback.FeedbackRateLimitedException
import com.platojobs.pkotlinapp.domain.feedback.FeedbackRejectedException
import com.platojobs.pkotlinapp.domain.feedback.FeedbackRemote
import com.platojobs.pkotlinapp.domain.feedback.FeedbackStatus
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import retrofit2.HttpException

/**
 * 反馈接口适配（契约 0.12.0 / 后端 v0.16.0）。
 *
 * 错误语义不解析 message 做机读判断：
 * - 401/403 → 登录失效；
 * - 409 → [FeedbackConflictException]（同 key 不同内容，重试无意义）；
 * - 429 → [FeedbackRateLimitedException]，带 `Retry-After` 秒数；
 * - 400/413 → [FeedbackRejectedException]（内容不合规）；
 * - 503 → 服务端繁忙（可退避重试）。
 */
class RemoteFeedbackDataSource(
    private val api: SyncApi,
    private val tokenStore: TokenStore,
) : FeedbackRemote {

    override suspend fun submit(draft: FeedbackDraft, context: FeedbackContext): String =
        withToken { auth ->
            val response = api.createFeedback(
                authorization = auth,
                // 幂等键直接使用草稿本地 ID：断网/重试始终命中同一条服务端记录。
                idempotencyKey = draft.idempotencyKey,
                body = FeedbackCreateRequest(
                    kind = draft.kind.wire,
                    body = draft.description.trim(),
                    contact = draft.contact?.trim()?.takeIf { it.isNotEmpty() },
                    context = FeedbackContextDto(
                        platform = context.platform,
                        appVersion = context.appVersion,
                        buildType = context.buildType,
                        osVersion = context.osVersion,
                        deviceModel = context.deviceModel,
                    ),
                ),
            )
            // 只有真正拿到 feedbackId 才算送达；2xx 但缺字段不伪造成功。
            response.feedbackId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("反馈提交响应缺少 feedbackId")
        }

    override suspend fun listMine(cursor: String?, limit: Int): FeedbackPage = withToken { auth ->
        val response = api.listMyFeedback(
            authorization = auth,
            cursor = cursor?.takeIf { it.isNotBlank() },
            limit = limit,
        )
        FeedbackPage(
            items = response.items.orEmpty().mapNotNull { item ->
                val id = item.feedbackId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FeedbackItem(
                    feedbackId = id,
                    kind = FeedbackKind.parse(item.kind),
                    description = item.body.orEmpty(),
                    status = FeedbackStatus.parse(item.status),
                    createdAt = Rfc3339.parse(item.createdAt) ?: 0L,
                )
            },
            nextCursor = response.nextCursor?.takeIf { it.isNotBlank() },
            hasMore = response.hasMore == true,
        )
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = tokenStore.token()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("尚未登录，无法提交反馈")
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            val details = e.apiErrorDetails()
            when {
                e.code() == 401 || e.code() == 403 ->
                    throw e.toSyncException() ?: FeedbackRejectedException(
                        FeedbackErrorCodes.UNAUTHORIZED,
                        details?.message,
                    )

                e.code() == 409 -> throw FeedbackConflictException(
                    code = details?.code,
                    message = details?.message,
                )

                e.code() == 429 -> throw FeedbackRateLimitedException(
                    retryAfterSeconds = e.retryAfterSecondsOrNull(),
                    message = details?.message,
                )

                e.code() == 400 || e.code() == 413 || e.code() == 422 ->
                    throw FeedbackRejectedException(
                        code = details?.code,
                        message = details?.message,
                    )

                else -> throw e.toSyncException() ?: e
            }
        }
    }

    /** `Retry-After` 按秒解析；HTTP-date 形式无法直接换算时返回 null。 */
    private fun HttpException.retryAfterSecondsOrNull(): Long? =
        response()?.headers()?.get("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
}
