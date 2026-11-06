package com.platojobs.pkotlinapp.ui.chart

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 图表横轴首尾标签：按实际时间跨度选格式，避免 24 小时视图首尾都落在同一时钟点、
 * 显示成两个完全相同的 `HH:mm`。
 *
 * - 跨度 ≥ 1 天：`MM-dd`
 * - 跨度 ≥ 1 小时且 < 1 天：`HH:mm`
 * - 跨度 < 1 小时：`HH:mm:ss`
 *
 * 若首尾格式化后仍然相同（亚秒级区间），逐级加细到毫秒，保证两个标签不完全相同。
 */
object ChartAxisLabelFormatter {

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L

    private const val PATTERN_DAY = "MM-dd"
    private const val PATTERN_MINUTE = "HH:mm"
    private const val PATTERN_SECOND = "HH:mm:ss"
    private const val PATTERN_DAY_MINUTE = "MM-dd HH:mm"
    private const val PATTERN_MILLIS = "HH:mm:ss.SSS"

    fun labels(
        from: Long,
        to: Long,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): Pair<String, String> {
        val span = (to - from).coerceAtLeast(0L)
        val pattern = when {
            span >= DAY_MS -> PATTERN_DAY
            span >= HOUR_MS -> PATTERN_MINUTE
            else -> PATTERN_SECOND
        }
        val fromLabel = format(from, pattern, locale, zone)
        val toLabel = format(to, pattern, locale, zone)
        if (fromLabel != toLabel) return fromLabel to toLabel

        val finer = when (pattern) {
            PATTERN_DAY -> PATTERN_DAY_MINUTE
            PATTERN_MINUTE -> PATTERN_SECOND
            else -> PATTERN_MILLIS
        }
        return format(from, finer, locale, zone) to format(to, finer, locale, zone)
    }

    private fun format(epochMs: Long, pattern: String, locale: Locale, zone: TimeZone): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(epochMs))
}
