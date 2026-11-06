package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.domain.model.HourCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HourlyPeakTest {

    @Test
    fun `无数据时没有峰值`() {
        assertNull(emptyList<HourCount>().peakHour())
    }

    @Test
    fun `单峰时返回唯一最大值`() {
        val counts = listOf(
            HourCount(hourIndex = 0, count = 40),
            HourCount(hourIndex = 1, count = 120),
            HourCount(hourIndex = 2, count = 80),
        )

        val peak = counts.peakHour()

        assertEquals(HourCount(hourIndex = 1, count = 120), peak)
    }

    @Test
    fun `并列时取最后出现的小时而不是 0 点`() {
        // 均匀造数下每小时条数相同，旧的 maxByOrNull 会把 0 点当成峰值。
        val counts = listOf(
            HourCount(hourIndex = 0, count = 100),
            HourCount(hourIndex = 1, count = 100),
            HourCount(hourIndex = 2, count = 80),
            HourCount(hourIndex = 3, count = 100),
        )

        val peak = counts.peakHour()

        assertEquals(HourCount(hourIndex = 3, count = 100), peak)
    }
}
