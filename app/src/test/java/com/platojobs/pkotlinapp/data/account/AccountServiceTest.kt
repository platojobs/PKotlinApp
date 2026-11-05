package com.platojobs.pkotlinapp.data.account

import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.account.AccountExportPage
import com.platojobs.pkotlinapp.domain.account.AccountRemote
import com.platojobs.pkotlinapp.domain.account.ClaimedAccount
import com.platojobs.pkotlinapp.domain.account.PairingCode
import com.platojobs.pkotlinapp.domain.sync.SyncCursorStore
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccountServiceTest {

    private class FakeTokenStore(
        private val events: MutableList<String> = mutableListOf(),
    ) : TokenStore {
        var stored: String? = "old-token"
        override fun token(): String? = stored
        override fun saveToken(token: String) {
            events += "token.save"
            stored = token
        }
        override fun clear() {
            events += "token.clear"
            stored = null
        }
    }

    private class FakeCursorStore : SyncCursorStore {
        override var cursor: String? = "42"
    }

    private class FakeRemote(
        private val pages: List<AccountExportPage> = emptyList(),
        private val deleteFails: Boolean = false,
        private val events: MutableList<String> = mutableListOf(),
    ) : AccountRemote {
        var claimCalls = 0

        override suspend fun createPairingCode(): PairingCode {
            events += "remote.create"
            return PairingCode(code = "ABCD2345", expiresAt = 1_000L)
        }

        override suspend fun claim(code: String): ClaimedAccount {
            events += "remote.claim:$code"
            claimCalls++
            return ClaimedAccount(userId = "u-new", token = "token-new")
        }

        override suspend fun exportPage(cursor: String?, limit: Int): AccountExportPage {
            events += "remote.export:$cursor"
            val index = if (cursor == null) 0 else cursor.substringAfter(':').toInt()
            return pages[index]
        }

        override suspend fun deleteAccount() {
            events += "remote.delete"
            if (deleteFails) error("服务端失败")
        }
    }

    private fun page(readings: Int, cursor: String?, hasMore: Boolean) = AccountExportPage(
        user = mapOf("id" to "u-old"),
        devices = listOf(mapOf("id" to "d1")),
        readings = List(readings) { mapOf("id" to "r$it") },
        exportedAt = 0L,
        exportedAtRaw = "2026-01-01T00:00:00Z",
        nextCursor = cursor,
        hasMore = hasMore,
    )

    private fun newDir(): File =
        File(System.getProperty("java.io.tmpdir"), "pkotlin-export-${System.nanoTime()}")

    private fun service(
        remote: FakeRemote,
        tokenStore: FakeTokenStore = FakeTokenStore(),
        events: MutableList<String> = mutableListOf(),
        exportDir: File = newDir(),
    ) = AccountService(
        remote = remote,
        tokenStore = tokenStore,
        cursorStore = FakeCursorStore(),
        resetSyncStatus = { events += "status.reset" },
        markAllPending = { events += "mark.pending" },
        wipeLocalData = { events += "wipe.local" },
        exportDir = exportDir,
        moshi = Moshi.Builder().build(),
        now = { 1_700_000_000_000L },
    )

    @Test
    fun `领取配对码会替换 token 重置游标并重新排队上传`() = runBlocking {
        val events = mutableListOf<String>()
        val tokenStore = FakeTokenStore(events)
        val remote = FakeRemote(events = events)
        val service = service(remote, tokenStore, events)

        val account = service.claim("abcd2345")

        assertEquals("u-new", account.userId)
        assertEquals("token-new", tokenStore.stored)
        assertEquals(
            listOf("remote.claim:ABCD2345", "token.save", "mark.pending", "status.reset"),
            events,
        )
    }

    @Test
    fun `配对码格式不合法时不会请求远端`() {
        val remote = FakeRemote()
        val service = service(remote)
        val error = runCatching { runBlocking { service.claim("123") } }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, remote.claimCalls)
    }

    @Test
    fun `导出多页会拼接并写盘`() = runBlocking {
        val remote = FakeRemote(
            pages = listOf(
                page(readings = 3, cursor = "opaque:1", hasMore = true),
                page(readings = 2, cursor = null, hasMore = false),
            ),
        )
        val dir = newDir()
        val service = service(remote, exportDir = dir)
        val progress = mutableListOf<Int>()

        val result = service.exportData { progress += it.readings }

        assertEquals(5, result.readings)
        assertEquals(2, result.pages)
        assertEquals(listOf(3, 5), progress)
        assertTrue(result.file.exists())
        assertTrue(result.file.parentFile == dir)
        val text = result.file.readText()
        assertTrue(text.contains("\"pageCount\": 2"))
        assertTrue(text.contains("\"readings\""))
    }

    @Test
    fun `注销成功后才按顺序清理本地`() = runBlocking {
        val events = mutableListOf<String>()
        val tokenStore = FakeTokenStore(events)
        val service = service(FakeRemote(events = events), tokenStore, events)

        service.deleteAccount()

        assertEquals(
            listOf("remote.delete", "token.clear", "status.reset", "wipe.local"),
            events,
        )
        assertNull(tokenStore.stored)
    }

    @Test
    fun `注销失败时本地数据保持不变`() {
        val events = mutableListOf<String>()
        val tokenStore = FakeTokenStore(events)
        val service = service(FakeRemote(deleteFails = true, events = events), tokenStore, events)

        val error = runCatching { runBlocking { service.deleteAccount() } }.exceptionOrNull()

        assertTrue(error != null)
        assertEquals("old-token", tokenStore.stored)
        assertEquals(listOf("remote.delete"), events)
        assertFalse(events.contains("wipe.local"))
    }
}
