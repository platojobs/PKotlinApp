package com.platojobs.pkotlinapp.domain.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountExportPagerTest {

    private fun page(
        readingCount: Int,
        nextCursor: String?,
        hasMore: Boolean,
    ): AccountExportPage = AccountExportPage(
        user = mapOf("id" to "u1"),
        devices = emptyList(),
        readings = List(readingCount) { mapOf("id" to "r$it") },
        exportedAt = 0L,
        exportedAtRaw = "2026-01-01T00:00:00Z",
        nextCursor = nextCursor,
        hasMore = hasMore,
    )

    @Test
    fun `按 hasMore 与 nextCursor 连续拉取并拼接`() = runBlocking {
        val cursors = mutableListOf<String?>()
        val pager = AccountExportPager(fetch = { cursor ->
            cursors += cursor
            when (cursor) {
                null -> page(readingCount = 3, nextCursor = "opaque:1", hasMore = true)
                "opaque:1" -> page(readingCount = 2, nextCursor = "opaque:2", hasMore = true)
                else -> page(readingCount = 1, nextCursor = null, hasMore = false)
            }
        })
        val progress = mutableListOf<AccountExportProgress>()
        val result = pager.exportAll { progress += it }

        assertEquals(listOf<String?>(null, "opaque:1", "opaque:2"), cursors)
        assertEquals(3, result.pageCount)
        assertEquals(6, result.readings)
        assertEquals(listOf(3, 5, 6), progress.map { it.readings })
        assertEquals(listOf(1, 2, 3), progress.map { it.page })
    }

    @Test
    fun `hasMore 为 false 时立即停止`() = runBlocking {
        var calls = 0
        val pager = AccountExportPager(fetch = {
            calls++
            page(readingCount = 2, nextCursor = "opaque:1", hasMore = false)
        })
        val result = pager.exportAll()

        assertEquals(1, calls)
        assertEquals(1, result.pageCount)
        assertEquals(2, result.readings)
    }

    @Test
    fun `cursor 重复时保护性停止避免死循环`() = runBlocking {
        var calls = 0
        val pager = AccountExportPager(fetch = {
            calls++
            page(readingCount = 1, nextCursor = "opaque:same", hasMore = true)
        })
        val result = pager.exportAll()

        // 第一次 cursor=null 拿到 opaque:same；第二次拿到同一 cursor 后停止。
        assertEquals(2, calls)
        assertEquals(2, result.pageCount)
        assertEquals(2, result.readings)
    }

    @Test
    fun `hasMore 为 true 但 cursor 为空时停止`() = runBlocking {
        var calls = 0
        val pager = AccountExportPager(fetch = {
            calls++
            page(readingCount = 1, nextCursor = " ", hasMore = true)
        })
        pager.exportAll()

        assertEquals(1, calls)
    }

    @Test
    fun `超过最大条数时抛异常`() {
        val pager = AccountExportPager(
            maxReadings = 2,
            fetch = { page(readingCount = 3, nextCursor = null, hasMore = false) },
        )
        val error = runCatching { runBlocking { pager.exportAll() } }.exceptionOrNull()

        assertTrue(error is AccountExportTooLargeException)
        assertEquals(2, (error as AccountExportTooLargeException).maxReadings)
    }
}
