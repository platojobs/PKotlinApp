package com.platojobs.pkotlinapp.domain.export

import com.platojobs.pkotlinapp.domain.reading.ReadingPage
import com.platojobs.pkotlinapp.domain.reading.RemoteReading

class ExportTooManyRowsException(val maxRows: Int) : Exception("读数超过 $maxRows 条")

/**
 * 服务端分页拉取：`limit` 固定 100（契约上限），按 cursor 翻页直到 hasMore=false。
 * 任一分页失败直接抛出，由 UI 提供可重试错误；超过上限立刻停止，避免无界内存占用。
 */
class ReadingPager(
    private val maxRows: Int = 50_000,
    private val pageSize: Int = 100,
    private val fetch: suspend (limit: Int, cursor: String?) -> ReadingPage,
) {

    suspend fun fetchAll(onProgress: (Int) -> Unit = {}): List<RemoteReading> {
        val rows = ArrayList<RemoteReading>()
        var cursor: String? = null
        while (true) {
            val page = fetch(pageSize, cursor)
            rows.addAll(page.items)
            onProgress(rows.size)
            if (rows.size > maxRows) throw ExportTooManyRowsException(maxRows)
            if (!page.hasMore) break
            val next = page.nextCursor
            if (next.isNullOrBlank() || next == cursor) break
            cursor = next
        }
        return rows
    }
}
