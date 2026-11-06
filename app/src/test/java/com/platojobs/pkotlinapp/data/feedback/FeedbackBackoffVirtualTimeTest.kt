package com.platojobs.pkotlinapp.data.feedback

import com.platojobs.pkotlinapp.data.local.FeedbackDao
import com.platojobs.pkotlinapp.data.local.FeedbackDraftEntity
import com.platojobs.pkotlinapp.domain.feedback.FeedbackContext
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDraft
import com.platojobs.pkotlinapp.domain.feedback.FeedbackErrorCodes
import com.platojobs.pkotlinapp.domain.feedback.FeedbackKind
import com.platojobs.pkotlinapp.domain.feedback.FeedbackPage
import com.platojobs.pkotlinapp.domain.feedback.FeedbackRateLimitedException
import com.platojobs.pkotlinapp.domain.feedback.FeedbackRemote
import com.platojobs.pkotlinapp.domain.feedback.FeedbackState
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用 kotlinx-coroutines-test 虚拟时间验证自动退避时序，不真实等待：
 * 首次重试 10 秒、之后指数增长、5 分钟封顶、5 次自动重试后停止且草稿仍在。
 * 429 不进入自动重试路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackBackoffVirtualTimeTest {

    private val context = FeedbackContext(
        appVersion = "0.20.0",
        buildType = "debug",
        osVersion = "Android 16",
        deviceModel = "Pixel",
    )

    @Test
    fun `auto retry waits 10s then doubles until five retries and keeps the draft`() = runTest {
        val attempts = mutableListOf<Long>()
        val dao = FakeFeedbackDao()
        val remote = FakeRemote {
            attempts += testScheduler.currentTime
            throw IOException("offline")
        }
        val store = FeedbackStore(
            dao = dao,
            remote = remote,
            contextProvider = { context },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )

        val submission = store.submit(FeedbackKind.BUG, "离线提交", null)
        runCurrent()
        assertEquals(FeedbackState.FAILED, submission.state)
        assertEquals(listOf(0L), attempts)

        // 10 秒之前不能重试。
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, attempts.size)

        // 首次自动重试恰好发生在 10 秒。
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 10_000L), attempts)

        // 指数增长：+20s、+40s、+80s、+160s。
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(30_000L, attempts.last())

        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(70_000L, attempts.last())

        advanceTimeBy(80_000)
        runCurrent()
        assertEquals(150_000L, attempts.last())

        advanceTimeBy(160_000)
        runCurrent()
        assertEquals(310_000L, attempts.last())

        // 已完成 5 次自动重试（累计 6 次失败），不再安排新的自动重试。
        advanceTimeBy(60 * 60_000)
        runCurrent()
        assertEquals(6, attempts.size)
        assertEquals(6, dao.find(submission.draftId)!!.retryCount)

        val draft = dao.find(submission.draftId)!!
        assertEquals("failed", draft.state)
        assertEquals(FeedbackErrorCodes.NETWORK, draft.lastErrorCode)
        // 草稿永久保留，可继续手动重试。
        assertEquals(1, dao.totalCount)
    }

    @Test
    fun `retry delay caps at five minutes`() {
        assertEquals(10_000L, FeedbackStore.retryDelayMillis(1))
        assertEquals(20_000L, FeedbackStore.retryDelayMillis(2))
        assertEquals(40_000L, FeedbackStore.retryDelayMillis(3))
        assertEquals(80_000L, FeedbackStore.retryDelayMillis(4))
        assertEquals(160_000L, FeedbackStore.retryDelayMillis(5))
        // 320 秒理论值被 5 分钟封顶。
        assertEquals(5 * 60_000L, FeedbackStore.retryDelayMillis(6))
        assertEquals(5 * 60_000L, FeedbackStore.retryDelayMillis(100))
    }

    @Test
    fun `429 never schedules an automatic retry`() = runTest {
        var attempts = 0
        val dao = FakeFeedbackDao()
        val remote = FakeRemote {
            attempts += 1
            throw FeedbackRateLimitedException(retryAfterSeconds = 60, message = "rate limited")
        }
        val store = FeedbackStore(
            dao = dao,
            remote = remote,
            contextProvider = { context },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )

        val submission = store.submit(FeedbackKind.BUG, "限流", null)
        runCurrent()
        assertEquals(1, attempts)

        // 等待远超 Retry-After 的时间，也不应有自动重试。
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(1, attempts)

        val draft = dao.find(submission.draftId)!!
        assertEquals("failed", draft.state)
        assertEquals(FeedbackErrorCodes.RATE_LIMITED, draft.lastErrorCode)
        assertEquals(60L, draft.lastErrorRetryAfterSeconds)
        assertEquals(1, draft.retryCount)
    }

    private class FakeRemote(private val submitBlock: () -> String) : FeedbackRemote {
        override suspend fun submit(draft: FeedbackDraft, context: FeedbackContext): String =
            submitBlock()

        override suspend fun listMine(cursor: String?, limit: Int): FeedbackPage =
            FeedbackPage(items = emptyList(), nextCursor = null, hasMore = false)
    }

    private class FakeFeedbackDao : FeedbackDao {
        private val flow = MutableStateFlow<List<FeedbackDraftEntity>>(emptyList())
        private var nextId = 1L

        val totalCount: Int get() = flow.value.size

        override suspend fun insert(draft: FeedbackDraftEntity): Long {
            val id = nextId++
            flow.value = flow.value + draft.copy(id = id)
            return id
        }

        override fun observeUnsent(): Flow<List<FeedbackDraftEntity>> = flow

        override suspend fun find(id: Long): FeedbackDraftEntity? =
            flow.value.firstOrNull { it.id == id }

        override suspend fun markSending(id: Long, attemptedAt: Long) {
            update(id) { it.copy(state = "sending", lastAttemptAt = attemptedAt) }
        }

        override suspend fun markSent(id: Long, feedbackId: String, attemptedAt: Long) {
            update(id) {
                it.copy(
                    state = "sent",
                    remoteFeedbackId = feedbackId,
                    lastError = null,
                    lastErrorCode = null,
                    lastErrorRetryAfterSeconds = null,
                    lastAttemptAt = attemptedAt,
                )
            }
        }

        override suspend fun markFailed(
            id: Long,
            retryCount: Int,
            error: String?,
            errorCode: String?,
            retryAfterSeconds: Long?,
            attemptedAt: Long,
        ) {
            update(id) {
                it.copy(
                    state = "failed",
                    retryCount = retryCount,
                    lastError = error,
                    lastErrorCode = errorCode,
                    lastErrorRetryAfterSeconds = retryAfterSeconds,
                    lastAttemptAt = attemptedAt,
                )
            }
        }

        override suspend fun resetInFlightToPending() {
            flow.value = flow.value.map {
                if (it.state == "sending") it.copy(state = "pending") else it
            }
        }

        override suspend fun delete(id: Long) {
            flow.value = flow.value.filterNot { it.id == id }
        }

        private fun update(id: Long, transform: (FeedbackDraftEntity) -> FeedbackDraftEntity) {
            flow.value = flow.value.map { if (it.id == id) transform(it) else it }
        }
    }
}
