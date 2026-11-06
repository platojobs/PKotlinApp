package com.platojobs.pkotlinapp.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** 横轴标签按跨度选格式；24 小时视图首尾不能都显示同一个 `HH:mm`。 */
class ChartAxisLabelFormatterTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2024-11-05T00:00:00Z */
    private val base = 1_730_764_800_000L
    private val hour = 3_600_000L
    private val minute = 60_000L

    @Test
    fun `span of at least one day shows month and day`() {
        val (from, to) = ChartAxisLabelFormatter.labels(
            base,
            base + 30 * hour,
            Locale.US,
            utc,
        )
        assertEquals("11-05", from)
        assertEquals("11-06", to)
    }

    @Test
    fun `exactly twenty four hours does not repeat the same clock label`() {
        // 回归：24h 视图曾因按 HH:mm 格式化，首尾都显示 20:31。
        val (from, to) = ChartAxisLabelFormatter.labels(
            base,
            base + 24 * hour,
            Locale.US,
            utc,
        )
        assertEquals("11-05", from)
        assertEquals("11-06", to)
    }

    @Test
    fun `span between one hour and one day shows hour and minute`() {
        val (from, to) = ChartAxisLabelFormatter.labels(
            base,
            base + 6 * hour,
            Locale.US,
            utc,
        )
        assertEquals("00:00", from)
        assertEquals("06:00", to)
    }

    @Test
    fun `span under one hour shows seconds`() {
        val (from, to) = ChartAxisLabelFormatter.labels(
            base,
            base + 10 * minute,
            Locale.US,
            utc,
        )
        assertEquals("00:00:00", from)
        assertEquals("00:10:00", to)
    }

    @Test
    fun `identical labels are disambiguated down to milliseconds`() {
        val (from, to) = ChartAxisLabelFormatter.labels(
            base,
            base + 1L,
            Locale.US,
            utc,
        )
        assertEquals("00:00:00.000", from)
        assertEquals("00:00:00.001", to)
    }
}
