package com.platojobs.pkotlinapp.data.account

import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.account.AccountDeletionBlockedException
import com.platojobs.pkotlinapp.domain.account.AccountExportTask
import com.platojobs.pkotlinapp.domain.account.AccountRemote
import com.platojobs.pkotlinapp.domain.account.AccountSession
import com.platojobs.pkotlinapp.domain.account.ClaimedAccount
import com.platojobs.pkotlinapp.domain.account.DeletionRequestToken
import com.platojobs.pkotlinapp.domain.account.DeletionTokenExpiredException
import com.platojobs.pkotlinapp.domain.account.ExportDownloadExpiredException
import com.platojobs.pkotlinapp.domain.account.ExportTaskStatus
import com.platojobs.pkotlinapp.domain.account.PairingCode
import com.platojobs.pkotlinapp.domain.sync.SyncCursorStore
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
        var relogin = false

        override fun token(): String? = stored
        override fun saveToken(token: String) {
            events += "token.save"
            stored = token
        }
        override fun reloginRequired(): Boolean = relogin
        override fun setReloginRequired(required: Boolean) {
            relogin = required
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
        private val events: MutableList<String> = mutableListOf(),
    ) : AccountRemote {
        var claimCalls = 0
        var createdTask = AccountExportTask(id = "task-1", status = ExportTaskStatus.PENDING)
        var pollResults = mutableListOf<AccountExportTask>()
        var pollError: Throwable? = null
        var downloads = mutableListOf<String>()
        var downloadResults = mutableListOf<Result<String>>()
        var deletionError: Throwable? = null

        override suspend fun sessions(): List<AccountSession> = emptyList()

        override suspend fun revokeSession(id: String) {
            events += "remote.revoke:$id"
        }

        override suspend fun logout(): Boolean {
            events += "remote.logout"
            return true
        }

        override suspend fun createPairingCode(): PairingCode {
            events += "remote.create"
            return PairingCode(code = "ABCD2345", expiresAt = 1_000L)
        }

        override suspend fun claim(code: String): ClaimedAccount {
            events += "remote.claim:$code"
            claimCalls++
            return ClaimedAccount(userId = "u-new", token = "token-new")
        }

        override suspend fun requestDeletionToken(): DeletionRequestToken {
            events += "remote.deletion.request"
            return DeletionRequestToken(token = "deletion-token", expiresAt = 2_000L)
        }

        override suspend fun deleteAccount(deletionToken: String) {
            events += "remote.delete:$deletionToken"
            deletionError?.let { throw it }
        }

        override suspend fun createExport(): AccountExportTask {
            events += "remote.export.create"
            return createdTask
        }

        override suspend fun exportTask(id: String): AccountExportTask {
            events += "remote.export.poll:$id"
            pollError?.let { throw it }
            return pollResults.removeAt(0)
        }

        override suspend fun downloadExport(downloadUrl: String): String {
            events += "remote.export.download:$downloadUrl"
            downloads += downloadUrl
            return downloadResults.removeAt(0).getOrThrow()
        }
    }

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
        now = { 1_700_000_000_000L },
        pollIntervalMillis = 0,
    )

    @Test
    fun `领取配对码会替换 token 重置游标并重新排队上传`() = runBlocking {
        val events = mutableListOf<String>()
        val tokenStore = FakeTokenStore(events)
        val remote = FakeRemote(events)
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
    fun `领取配对码会解除重新登录态`() = runBlocking {
        val tokenStore = FakeTokenStore().apply { relogin = true }
        val service = service(FakeRemote(), tokenStore)

        service.claim("abcd2345")

        assertFalse(tokenStore.relogin)
    }

    @Test
    fun `退出当前会话只清 token 不清本地数据并进入重新登录态`() = runBlocking {
        val events = mutableListOf<String>()
        val tokenStore = FakeTokenStore(events)
        val remote = FakeRemote(events)
        val service = service(remote, tokenStore, events)

        val revoked = service.logout()

        assertTrue(revoked)
        assertNull(tokenStore.stored)
        assertTrue(tokenStore.relogin)
        assertEquals(listOf("remote.logout", "token.clear", "status.reset"), events)
        // 与「删除账号」不同：本地设备/读数/场景一律保留。
        assertFalse(events.contains("wipe.local"))
    }

    @Test
    fun `导出失败展示服务端 failureReason`() = runBlocking {
        val remote = FakeRemote().apply {
            createdTask = AccountExportTask(id = "task-3", status = ExportTaskStatus.PENDING)
            pollResults = mutableListOf(
                AccountExportTask(
                    id = "task-3",
                    status = ExportTaskStatus.FAILED,
                    failureReason = "导出任务因服务重启中断，请重新发起导出",
                ),
            )
        }
        val service = service(remote)

        val error = runCatching { runBlocking { service.exportData() } }.exceptionOrNull()

        assertEquals("导出任务因服务重启中断，请重新发起导出", error?.message)
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
    fun `导出会轮询到 ready 再下载并写盘`() = runBlocking {
        val events = mutableListOf<String>()
        val remote = FakeRemote(events).apply {
            createdTask = AccountExportTask(id = "task-9", status = ExportTaskStatus.PENDING)
            pollResults = mutableListOf(
                AccountExportTask(
                    id = "task-9",
                    status = ExportTaskStatus.READY,
                    downloadUrl = "/api/v1/me/export/task-9/download?token=abc",
                ),
            )
            downloadResults = mutableListOf(Result.success("""{"user":{"id":"u-1"}}"""))
        }
        val dir = newDir()
        val service = service(remote, exportDir = dir)
        val stages = mutableListOf<ExportStage>()

        val result = service.exportData { stages += it }

        assertEquals(listOf(ExportStage.PENDING, ExportStage.READY), stages)
        assertEquals(
            listOf(
                "remote.export.create",
                "remote.export.poll:task-9",
                "remote.export.download:/api/v1/me/export/task-9/download?token=abc",
            ),
            events,
        )
        assertTrue(result.file.exists())
        assertTrue(result.file.parentFile == dir)
        assertEquals("""{"user":{"id":"u-1"}}""", result.file.readText())
    }

    @Test
    fun `下载链接过期时重新查询任务并换用新链接`() = runBlocking {
        val remote = FakeRemote().apply {
            pollResults = mutableListOf(
                AccountExportTask(
                    id = "task-9",
                    status = ExportTaskStatus.READY,
                    downloadUrl = "/old",
                ),
                AccountExportTask(
                    id = "task-9",
                    status = ExportTaskStatus.READY,
                    downloadUrl = "/fresh",
                ),
            )
            downloadResults = mutableListOf(
                Result.failure(ExportDownloadExpiredException()),
                Result.success("""{"fresh":true}"""),
            )
        }

        val result = service(remote).exportData()

        assertEquals(listOf("/old", "/fresh"), remote.downloads)
        assertEquals("""{"fresh":true}""", result.file.readText())
    }

    @Test
    fun `服务端任务失败时不下载也不生成文件`() = runBlocking {
        val remote = FakeRemote().apply {
            pollResults = mutableListOf(AccountExportTask(id = "task-9", status = ExportTaskStatus.FAILED))
        }
        val dir = newDir()
        val service = service(remote, exportDir = dir)

        val error = runCatching { runBlocking { service.exportData() } }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(remote.downloads.isEmpty())
        assertFalse(dir.exists() && dir.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `注销先申请令牌再删除账号最后清理本地`() = runBlocking {
        val events = mutableListOf<String>()
        val tokenStore = FakeTokenStore(events)
        val service = service(FakeRemote(events), tokenStore, events)

        service.deleteAccount()

        assertEquals(
            listOf(
                "remote.deletion.request",
                "remote.delete:deletion-token",
                "token.clear",
                "status.reset",
                "wipe.local",
            ),
            events,
        )
        assertNull(tokenStore.stored)
    }

    @Test
    fun `409 家庭还有成员时本地数据保持不变`() {
        val events = mutableListOf<String>()
        val tokenStore = FakeTokenStore(events)
        val remote = FakeRemote(events).apply {
            deletionError = AccountDeletionBlockedException()
        }
        val service = service(remote, tokenStore, events)

        val error = runCatching { runBlocking { service.deleteAccount() } }.exceptionOrNull()

        assertTrue(error is AccountDeletionBlockedException)
        assertEquals("old-token", tokenStore.stored)
        assertEquals(listOf("remote.deletion.request", "remote.delete:deletion-token"), events)
        assertFalse(events.contains("wipe.local"))
    }

    @Test
    fun `删除令牌过期时不清空本地`() {
        val events = mutableListOf<String>()
        val tokenStore = FakeTokenStore(events)
        val remote = FakeRemote(events).apply {
            deletionError = DeletionTokenExpiredException()
        }
        val service = service(remote, tokenStore, events)

        val error = runCatching { runBlocking { service.deleteAccount() } }.exceptionOrNull()

        assertTrue(error is DeletionTokenExpiredException)
        assertEquals("old-token", tokenStore.stored)
        assertFalse(events.contains("wipe.local"))
    }
}
