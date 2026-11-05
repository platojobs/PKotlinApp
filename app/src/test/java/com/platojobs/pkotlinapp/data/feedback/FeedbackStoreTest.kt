package com.platojobs.pkotlinapp.data.feedback

import androidx.room.Room
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.domain.feedback.FeedbackCategory
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDraft
import kotlinx.coroutines.flow.first
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

/** 反馈队列：无远端时草稿留在本机并如实返回未发送；接入远端后成功才移除。 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class FeedbackStoreTest {

    private lateinit var db: AppDatabase

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
    fun `submit without backend keeps an unsent draft`() = runBlocking {
        val store = FeedbackStore(db.feedbackDao(), remote = null, clock = { 1762300000000 })

        val result = store.submit(FeedbackCategory.CONNECTION, "  连接失败  ", "   ")

        assertFalse(result.sent)
        val drafts = store.observePending().first()
        assertEquals(1, drafts.size)
        val draft = drafts.single()
        assertEquals(FeedbackCategory.CONNECTION, draft.category)
        assertEquals("连接失败", draft.description)
        assertNull(draft.contact)
        assertEquals(1762300000000, draft.createdAt)
        assertTrue(draft.unsent)
    }

    @Test
    fun `retry without backend keeps the same draft`() = runBlocking {
        val store = FeedbackStore(db.feedbackDao(), remote = null)
        val id = store.submit(FeedbackCategory.SYNC, "同步冲突", "me@example.com").draftId

        val retried = store.retry(id)

        assertFalse(retried.sent)
        assertEquals(id, retried.draftId)
        assertEquals(1, store.observePending().first().size)
    }

    @Test
    fun `successful remote removes the draft from pending`() = runBlocking {
        var submitted: FeedbackDraft? = null
        val store = FeedbackStore(
            db.feedbackDao(),
            remote = FeedbackRemote { draft -> submitted = draft },
        )
        val id = store.submit(FeedbackCategory.OTHER, "建议", null).draftId

        val result = store.retry(id)

        assertTrue(result.sent)
        assertEquals(id, submitted?.id)
        assertTrue(store.observePending().first().isEmpty())
    }

    @Test
    fun `failing remote keeps the draft for later retry`() = runBlocking {
        val store = FeedbackStore(
            db.feedbackDao(),
            remote = FeedbackRemote { throw IllegalStateException("network down") },
        )
        val id = store.submit(FeedbackCategory.BUG, "崩溃", null).draftId

        assertFalse(store.retry(id).sent)
        assertEquals(1, store.observePending().first().size)
    }

    @Test
    fun `discard removes a pending draft`() = runBlocking {
        val store = FeedbackStore(db.feedbackDao())
        val id = store.submit(FeedbackCategory.BUG, "崩溃", null).draftId

        store.discard(id)

        assertTrue(store.observePending().first().isEmpty())
    }
}
