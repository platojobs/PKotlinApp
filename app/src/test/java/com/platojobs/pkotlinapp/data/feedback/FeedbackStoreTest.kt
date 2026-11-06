package com.platojobs.pkotlinapp.data.feedback

import androidx.room.Room
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.domain.feedback.FeedbackContext
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDetail
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDraft
import com.platojobs.pkotlinapp.domain.feedback.FeedbackErrorCodes
import com.platojobs.pkotlinapp.domain.feedback.FeedbackKind
import com.platojobs.pkotlinapp.domain.feedback.FeedbackPage
import com.platojobs.pkotlinapp.domain.feedback.FeedbackRateLimitedException
import com.platojobs.pkotlinapp.domain.feedback.FeedbackRemote
import com.platojobs.pkotlinapp.domain.feedback.FeedbackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** 反馈真实发送链路：状态机、幂等键、429、失败保留与有界退避。 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class FeedbackStoreTest {

    private lateinit var db: AppDatabase
    private val context = FeedbackContext(
        platform = "android",
        appVersion = "0.19.0",
        buildType = "debug",
        osVersion = "Android 14 (API 34)",
        deviceModel = "Pixel Test",
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            org.robolectric.RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `state machine goes pending to sending to sent only with remote id`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val remote = FakeRemote(
            submitBlock = { _, _ ->
                started.complete(Unit)
                release.await()
                "remote-feedback-1"
            },
        )
        val store = FeedbackStore(db.feedbackDao(), remote, { context }, clock = { 1_000L })

        val job = launch { store.submit(FeedbackKind.BUG, "  崩溃  ", null) }
        started.await()

        val sending = store.observeUnsent().first().single()
        assertEquals(FeedbackState.SENDING, sending.state)
        assertEquals("崩溃", sending.description)

        release.complete(Unit)
        job.join()

        val sent = db.feedbackDao().find(sending.id)!!
        assertEquals("sent", sent.state)
        assertEquals("remote-feedback-1", sent.remoteFeedbackId)
        assertTrue(store.observeUnsent().first().isEmpty())
    }

    @Test
    fun `2xx without feedbackId is never marked sent`() = runBlocking {
        val remote = FakeRemote(submitBlock = { _, _ -> throw IllegalStateException("缺少 feedbackId") })
        val store = FeedbackStore(db.feedbackDao(), remote, { context }, clock = { 1L })

        val result = store.submit(FeedbackKind.OTHER, "建议", null)

        assertFalse(result.sent)
        assertEquals(FeedbackState.FAILED, result.state)
        val draft = db.feedbackDao().find(result.draftId)!!
        assertEquals("failed", draft.state)
        assertNull(draft.remoteFeedbackId)
        assertEquals(1, draft.retryCount)
        assertEquals("缺少 feedbackId", draft.lastError)
    }

    @Test
    fun `idempotency key reuses the same draft id across retries`() = runBlocking {
        val keys = mutableListOf<String>()
        val remote = FakeRemote(
            submitBlock = { draft, _ ->
                keys += draft.idempotencyKey
                if (keys.size == 1) throw IOException("offline")
                "remote-feedback-2"
            },
        )
        val store = FeedbackStore(db.feedbackDao(), remote, { context }, clock = { 2L })

        val first = store.submit(FeedbackKind.FEATURE, "希望支持导出", "me@example.com")
        assertFalse(first.sent)
        assertEquals(FeedbackState.FAILED, first.state)

        val second = store.retry(first.draftId)
        assertTrue(second.sent)
        assertEquals(second.draftId.toString(), keys[0])
        assertEquals(keys[0], keys[1])
        assertTrue(store.observeUnsent().first().isEmpty())
    }

    @Test
    fun `429 keeps draft with rate limit code and retry after seconds`() = runBlocking {
        val remote = FakeRemote(
            submitBlock = { _, _ -> throw FeedbackRateLimitedException(30, "稍后再试") },
        )
        val store = FeedbackStore(db.feedbackDao(), remote, { context }, clock = { 3L })

        val result = store.submit(FeedbackKind.EXPERIENCE, "列表卡顿", null)

        assertFalse(result.sent)
        assertEquals(FeedbackState.FAILED, result.state)
        assertEquals(FeedbackErrorCodes.RATE_LIMITED, result.errorCode)
        assertEquals(30L, result.retryAfterSeconds)
        val draft = store.observeUnsent().first().single()
        assertEquals(FeedbackState.FAILED, draft.state)
        assertEquals(FeedbackErrorCodes.RATE_LIMITED, draft.lastErrorCode)
        assertEquals(30L, draft.retryAfterSeconds)
        // 429 不自动重试：没有额外失败次数累积。
        assertEquals(1, draft.retryCount)
    }

    @Test
    fun `failed drafts survive beyond retry limit and keep last error`() = runBlocking {
        val remote = FakeRemote(submitBlock = { _, _ -> throw IOException("still offline") })
        val store = FeedbackStore(db.feedbackDao(), remote, { context }, clock = { 4L })

        val id = store.submit(FeedbackKind.BUG, "崩溃", null).draftId
        repeat(FeedbackStore.MAX_AUTO_RETRIES + 1) { store.retry(id) }

        val draft = store.observeUnsent().first().single()
        assertEquals(FeedbackState.FAILED, draft.state)
        assertEquals(FeedbackStore.MAX_AUTO_RETRIES + 2, draft.retryCount)
        assertEquals(FeedbackErrorCodes.NETWORK, draft.lastErrorCode)
        // 超过自动重试上限也绝不删除草稿。
        assertEquals(id, draft.id)
    }

    @Test
    fun `discard removes a failed draft`() = runBlocking {
        val remote = FakeRemote(submitBlock = { _, _ -> throw IOException("offline") })
        val store = FeedbackStore(db.feedbackDao(), remote, { context }, clock = { 5L })
        val id = store.submit(FeedbackKind.BUG, "崩溃", null).draftId

        store.discard(id)

        assertTrue(store.observeUnsent().first().isEmpty())
        assertNull(db.feedbackDao().find(id))
    }

    @Test
    fun `bounded exponential backoff never exceeds five minutes`() {
        assertEquals(10_000L, FeedbackStore.retryDelayMillis(1))
        assertEquals(20_000L, FeedbackStore.retryDelayMillis(2))
        assertEquals(40_000L, FeedbackStore.retryDelayMillis(3))
        assertEquals(5 * 60_000L, FeedbackStore.retryDelayMillis(10))
        assertEquals(5 * 60_000L, FeedbackStore.retryDelayMillis(100))
    }

    @Test
    fun `context provider uses build and device values`() = runBlocking {
        val remote = FakeRemote(submitBlock = { _, supplied ->
            assertEquals("android", supplied.platform)
            assertEquals(com.platojobs.pkotlinapp.BuildConfig.VERSION_NAME, supplied.appVersion)
            assertEquals(com.platojobs.pkotlinapp.BuildConfig.BUILD_TYPE, supplied.buildType)
            assertTrue(supplied.osVersion.startsWith("Android "))
            assertEquals(android.os.Build.MODEL, supplied.deviceModel)
            "remote-context"
        })
        val store = FeedbackStore(
            db.feedbackDao(),
            remote,
            contextProvider = { androidFeedbackContext() },
            clock = { 6L },
        )

        assertTrue(store.submit(FeedbackKind.OTHER, "上下文", null).sent)
    }

    private class FakeRemote(
        private val submitBlock: suspend (FeedbackDraft, FeedbackContext) -> String,
        private val listBlock: suspend (String?, Int) -> FeedbackPage = { _, _ -> FeedbackPage.EMPTY },
    ) : FeedbackRemote {
        override suspend fun submit(draft: FeedbackDraft, context: FeedbackContext): String =
            submitBlock(draft, context)

        override suspend fun listMine(cursor: String?, limit: Int): FeedbackPage =
            listBlock(cursor, limit)

        override suspend fun detail(id: String): FeedbackDetail = throw UnsupportedOperationException()

        override suspend fun close(id: String): FeedbackDetail = throw UnsupportedOperationException()
    }
}
