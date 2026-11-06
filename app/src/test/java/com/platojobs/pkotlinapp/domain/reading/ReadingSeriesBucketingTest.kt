package com.platojobs.pkotlinapp.domain.reading

import com.platojobs.pkotlinapp.ui.chart.ChartBucket
import com.platojobs.pkotlinapp.ui.chart.ReadingChartGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 离线兜底分桶：与在线 UTC 网格一致，且 30 天区间只覆盖本地 7 天。 */
class ReadingSeriesBucketingTest {

    private val now = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun reading(value: Double, at: Long, id: String = "id-$at-$value") = Reading(
        id = id,
        deviceId = "AA:BB:CC:00:00:01",
        metric = "temperature",
        value = value,
        unit = "°C",
        recordedAt = at,
    )

    @Test
    fun `aggregate computes min max avg last per utc bucket`() {
        val base = ReadingRange.bucketStart(now, "1h")
        val rows = listOf(
            reading(20.0, base + 60_000L, "a"),
            reading(24.0, base + 2 * 60_000L, "b"),
            reading(22.0, base + 3 * 60_000L, "c"),
        )

        val buckets = ReadingSeriesBucketing.aggregate(rows, "1h")

        assertEquals(1, buckets.size)
        val bucket = buckets.single()
        assertEquals(base, bucket.start)
        assertEquals(base + hour, bucket.end)
        assertEquals(3L, bucket.count)
        assertEquals(20.0, bucket.min, 0.001)
        assertEquals(24.0, bucket.max, 0.001)
        assertEquals(22.0, bucket.avg, 0.001)
        // last 取 recordedAt 最大的那条。
        assertEquals(22.0, bucket.last, 0.001)
    }

    @Test
    fun `aggregate keeps buckets sorted and skips non finite values`() {
        val base = ReadingRange.bucketStart(now, "1h")
        val rows = listOf(
            reading(21.0, base + 2 * hour),
            reading(Double.NaN, base + hour),
            reading(23.0, base),
            reading(Double.POSITIVE_INFINITY, base + 3 * hour),
        )

        val buckets = ReadingSeriesBucketing.aggregate(rows, "1h")

        assertEquals(2, buckets.size)
        assertEquals(listOf(base, base + 2 * hour), buckets.map { it.start })
    }

    @Test
    fun `empty local data yields empty buckets`() {
        assertTrue(ReadingSeriesBucketing.aggregate(emptyList(), "1h").isEmpty())
    }

    @Test
    fun `remote twenty four hours at thirty minute buckets keeps forty eight buckets`() {
        val last = ReadingRange.bucketStart(now, "30m")
        val halfHour = 1_800_000L
        val rows = (0 until 48).map { index ->
            val at = last - (47 - index) * halfHour
            RemoteReading("r-$index", "temperature", 20.0 + index % 4, "°C", at)
        }

        val buckets = ReadingSeriesBucketing.aggregateRemote(rows, "30m")

        assertEquals(48, buckets.size)
        assertEquals(halfHour, buckets[1].start - buckets[0].start)
        assertEquals(1L, buckets.first().count)
        assertEquals(20.0, buckets.first().min, 0.001)
    }

    @Test
    fun `thirty day offline fallback only covers the local seven days`() {
        // 模拟本地只保留了最近 7 天（更早的记录已被 cleanup 删除）。
        val rows = listOf(
            reading(20.0, now - 6 * day),
            reading(21.0, now - 3 * day),
            reading(22.0, now - 1 * day),
        )
        val buckets = ReadingSeriesBucketing.aggregate(rows, ReadingRange.DAY_30.bucket)

        assertEquals(3, buckets.size)
        val plot = ReadingChartGeometry.plot(
            buckets.map { ChartBucket(it.start, it.end, it.min, it.max, it.avg) },
            from = now - ReadingRange.DAY_30.windowMillis,
            to = now,
            left = 0f,
            top = 0f,
            right = 600f,
            bottom = 300f,
        )
        // 30 天窗口里，本地 7 天数据集中在右侧最后约 1/4 区域。
        assertTrue(plot.buckets.first().xStart > 400f)
        assertTrue(plot.buckets.last().xStart > 560f)
        assertTrue(plot.buckets.last().xStart < 600f)
    }
}
