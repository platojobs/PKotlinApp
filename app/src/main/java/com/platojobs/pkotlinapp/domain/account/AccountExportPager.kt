package com.platojobs.pkotlinapp.domain.account

/** 导出进度：已完成的页数与累计拉取条数（真实读数，不是估算）。 */
data class AccountExportProgress(
    val page: Int,
    val readings: Int,
)

data class AccountExportResult(
    val pages: List<AccountExportPage>,
    val pageCount: Int,
    val readings: Int,
)

/**
 * 按 `hasMore` / `nextCursor` 循环拉全账号数据。
 *
 * - cursor 是不透明串，只原样回传，不解析、不构造；
 * - `hasMore=false` 或 cursor 为空即停止；
 * - cursor 重复（服务端异常）以本地保护性停止，避免死循环；
 * - 条数或页数超过上限时抛 [AccountExportTooLargeException]，由 UI 提示。
 */
class AccountExportPager(
    private val maxReadings: Int = DEFAULT_MAX_READINGS,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val fetch: suspend (cursor: String?) -> AccountExportPage,
) {

    suspend fun exportAll(
        onProgress: (AccountExportProgress) -> Unit = {},
    ): AccountExportResult {
        val pages = mutableListOf<AccountExportPage>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var readings = 0

        while (true) {
            val page = fetch(cursor)
            pages += page
            readings += page.readings.size
            if (readings > maxReadings) throw AccountExportTooLargeException(maxReadings)
            if (pages.size > maxPages) throw AccountExportTooLargeException(maxReadings)
            onProgress(AccountExportProgress(page = pages.size, readings = readings))

            if (!page.hasMore) break
            val next = page.nextCursor?.takeIf { it.isNotBlank() } ?: break
            if (!seenCursors.add(next)) break
            cursor = next
        }

        return AccountExportResult(
            pages = pages,
            pageCount = pages.size,
            readings = readings,
        )
    }

    companion object {
        const val DEFAULT_MAX_READINGS = 200_000
        const val DEFAULT_MAX_PAGES = 200
    }
}
