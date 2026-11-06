package com.platojobs.pkotlinapp.data.feedback

import com.platojobs.pkotlinapp.data.local.FeedbackDao
import com.platojobs.pkotlinapp.data.local.FeedbackDraftEntity
import com.platojobs.pkotlinapp.domain.feedback.FeedbackContext
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDraft
import com.platojobs.pkotlinapp.domain.feedback.FeedbackErrorCodes
import com.platojobs.pkotlinapp.domain.feedback.FeedbackFailure
import com.platojobs.pkotlinapp.domain.feedback.FeedbackKind
import com.platojobs.pkotlinapp.domain.feedback.FeedbackPage
import com.platojobs.pkotlinapp.domain.feedback.FeedbackRemote
import com.platojobs.pkotlinapp.domain.feedback.FeedbackState
import com.platojobs.pkotlinapp.domain.feedback.FeedbackSubmission
import com.platojobs.pkotlinapp.domain.feedback.classifyFeedbackFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/**
 * 反馈草稿队列与真实发送状态机。
 *
 * - 状态：pending → sending → sent / failed；只有 2xx 且拿到 feedbackId 才落 sent。
 * - 幂等：每次都复用草稿本地 ID 作为 `Idempotency-Key`，断网重试不会产生重复记录。
 * - 重试：前台手动重试不限次数；失败后若错误可重试且未达上限，后台只安排一次有界指数退避。
 * - 429：不自动重试，交由用户按提示稍后再试；失败草稿永久保留，绝不静默删除。
 */
class FeedbackStore(
    private val dao: FeedbackDao,
    private val remote: FeedbackRemote,
    private val contextProvider: () -> FeedbackContext,
    private val scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAutoRetries: Int = MAX_AUTO_RETRIES,
) {

    private val mutex = Mutex()
    private val retryJobs = mutableMapOf<Long, Job>()

    /** 待发队列（pending / sending / failed），UI 据此如实展示重试次数与错误。 */
    fun observeUnsent(): Flow<List<FeedbackDraft>> =
        dao.observeUnsent().map { rows -> rows.map { it.toDomain() } }

    /** 进程被杀导致卡在 sending 的草稿回到 pending，等待用户手动重试。 */
    suspend fun recoverInFlight() {
        dao.resetInFlightToPending()
    }

    suspend fun submit(
        kind: FeedbackKind,
        description: String,
        contact: String?,
    ): FeedbackSubmission {
        val id = dao.insert(
            FeedbackDraftEntity(
                category = kind.name,
                description = description.trim(),
                contact = contact?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = clock(),
            ),
        )
        return deliver(id)
    }

    /** 前台手动重试：取消已排队的后台重试，立即执行一次。 */
    suspend fun retry(id: Long): FeedbackSubmission {
        cancelScheduled(id)
        return deliver(id)
    }

    suspend fun discard(id: Long) {
        cancelScheduled(id)
        dao.delete(id)
    }

    /** 我的反馈列表：远端只返回本人，游标分页。 */
    suspend fun loadPage(cursor: String?, limit: Int): FeedbackPage =
        remote.listMine(cursor, limit)

    private suspend fun deliver(id: Long): FeedbackSubmission = mutex.withLock {
        val entity = dao.find(id)
            ?: return@withLock FeedbackSubmission(
                draftId = id,
                state = FeedbackState.PENDING,
                errorCode = null,
            )

        // 已送达的草稿不会被任何重试改写。
        if (entity.state == FeedbackState.SENT.name && entity.remoteFeedbackId != null) {
            return@withLock entity.toSubmission()
        }

        val attemptedAt = clock()
        dao.markSending(id, attemptedAt)
        val draft = (dao.find(id) ?: entity).toDomain()

        return@withLock try {
            val feedbackId = remote.submit(draft, contextProvider())
            dao.markSent(id, feedbackId, clock())
            cancelScheduled(id)
            FeedbackSubmission(
                draftId = id,
                state = FeedbackState.SENT,
                retryCount = draft.retryCount,
                remoteFeedbackId = feedbackId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = classifyFeedbackFailure(e)
            val retryCount = draft.retryCount + 1
            dao.markFailed(
                id = id,
                retryCount = retryCount,
                error = failure.message,
                errorCode = failure.code,
                retryAfterSeconds = failure.retryAfterSeconds,
                attemptedAt = attemptedAt,
            )
            scheduleAutoRetry(id, retryCount, failure)
            FeedbackSubmission(
                draftId = id,
                state = FeedbackState.FAILED,
                retryCount = retryCount,
                errorCode = failure.code,
                errorMessage = failure.message,
                retryAfterSeconds = failure.retryAfterSeconds,
            )
        }
    }

    /**
     * 失败后最多安排一次后台重试：
     * - 4xx（校验/冲突/登录失效）不自动重试；
     * - 429 交给用户按 `Retry-After` 提示手动重试，不自动狂重试；
     * - 网络/5xx 按有界指数退避，5 次自动重试后停止，失败草稿永久保留等待手动重试。
     */
    private fun scheduleAutoRetry(id: Long, retryCount: Int, failure: FeedbackFailure) {
        val scheduler = scope ?: return
        // retryCount 是已失败次数；达到上限后停止自动重试，只保留手动重试入口。
        if (!failure.retryable || retryCount > maxAutoRetries) return
        // 429 与带 Retry-After 的错误不自动重试，只提示用户稍后手动重试。
        if (failure.code == FeedbackErrorCodes.RATE_LIMITED) return
        if (failure.retryAfterSeconds != null) return
        cancelScheduled(id)
        val delayMillis = retryDelayMillis(retryCount)
        retryJobs[id] = scheduler.launch {
            delay(delayMillis)
            retryJobs.remove(id)
            deliver(id)
        }
    }

    private fun cancelScheduled(id: Long) {
        retryJobs.remove(id)?.cancel()
    }

    private fun FeedbackDraftEntity.toDomain(): FeedbackDraft = FeedbackDraft(
        id = id,
        kind = FeedbackKind.parse(category),
        description = description,
        contact = contact,
        createdAt = createdAt,
        state = FeedbackState.parse(state),
        retryCount = retryCount,
        lastError = lastError,
        lastErrorCode = lastErrorCode,
        retryAfterSeconds = lastErrorRetryAfterSeconds,
        remoteFeedbackId = remoteFeedbackId,
        lastAttemptAt = lastAttemptAt,
    )

    private fun FeedbackDraftEntity.toSubmission(): FeedbackSubmission = FeedbackSubmission(
        draftId = id,
        state = FeedbackState.parse(state),
        retryCount = retryCount,
        errorCode = lastErrorCode,
        errorMessage = lastError,
        retryAfterSeconds = lastErrorRetryAfterSeconds,
        remoteFeedbackId = remoteFeedbackId,
    )

    companion object {
        const val MAX_AUTO_RETRIES = 5
        private const val BASE_RETRY_DELAY_MILLIS = 10_000L
        private const val MAX_RETRY_DELAY_MILLIS = 5 * 60_000L

        /** 有界指数退避：10s、20s、40s… 最长 5 分钟。 */
        fun retryDelayMillis(retryCount: Int): Long {
            if (retryCount <= 1) return BASE_RETRY_DELAY_MILLIS
            val exponent = retryCount - 1
            val multiplier = 1L shl min(exponent, 10)
            return min(BASE_RETRY_DELAY_MILLIS * multiplier, MAX_RETRY_DELAY_MILLIS)
        }
    }
}
