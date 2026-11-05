package com.platojobs.pkotlinapp.domain.export

import com.platojobs.pkotlinapp.domain.reading.ReadingPage
import com.platojobs.pkotlinapp.domain.reading.RemoteReading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ReadingPagerTest {

    private fun row(id: Int) = RemoteReading(
        id = "r$id",
        metric = "temperature",
        value = id.toDouble(),
        unit = "°C",
        recordedAt = id.toLong(),
    )

    @Test
    fun `按 cursor 翻页直到 hasMore=false`() = runBlocking {
        val pages = listOf(
            ReadingPage(listOf(row(1), row(2)), "c2", true),
            ReadingPage(listOf(row(3)), "c3", true),
            ReadingPage(listOf(row(4)), null, false),
        )
        val requests = mutableListOf<Pair<Int, String?>>()
        val pager = ReadingPager(fetch = { limit, cursor ->
            requests.add(limit to cursor)
            pages[requests.size - 1]
        })
        val rows = pager.fetchAll()
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.id.removePrefix("r").toInt() })
        assertEquals(listOf(100 to null, 100 to "c2", 100 to "c3"), requests)
    }

    @Test
    fun `进度回调使用真实累计条数`() = runBlocking {
        val pages = listOf(
            ReadingPage(listOf(row(1), row(2)), "c2", true),
            ReadingPage(listOf(row(3)), null, false),
        )
        var index = 0
        val progress = mutableListOf<Int>()
        val pager = ReadingPager(fetch = { _, _ -> pages[index++] })
        pager.fetchAll { progress.add(it) }
        assertEquals(listOf(2, 3), progress)
    }

    @Test
    fun `超过五万条立即拒绝`() = runBlocking {
        var call = 0
        val pager = ReadingPager(maxRows = 50_000, fetch = { _, _ ->
            call++
            ReadingPage(
                items = (1..100).map(::row),
                nextCursor = "c$call",
                hasMore = true,
            )
        })
        try {
            pager.fetchAll()
            fail("应抛出 ExportTooManyRowsException")
        } catch (e: ExportTooManyRowsException) {
            assertEquals(50_000, e.maxRows)
        }
    }

    @Test
    fun `cursor 为空或重复时停止避免死循环`() = runBlocking {
        var calls = 0
        val pager = ReadingPager(fetch = { _, _ ->
            calls++
            ReadingPage(listOf(row(calls)), "same", true)
        })
        pager.fetchAll()
        // 第一次拿到 "same" 继续请求；第二次发现游标没有推进，停止。
        assertEquals(2, calls)
    }
}
