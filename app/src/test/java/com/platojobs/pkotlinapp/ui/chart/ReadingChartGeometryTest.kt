package com.platojobs.pkotlinapp.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表几何关键口径：按 start 的真实时间比例定位、缺口断线、min–max 区间带、Y 轴自适应。
 */
class ReadingChartGeometryTest {

    private val start = 1_760_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun plot(buckets: List<ChartBucket>, from: Long, to: Long) =
        ReadingChartGeometry.plot(buckets, from, to, left = 0f, top = 0f, right = 600f, bottom = 300f)

    @Test
    fun `empty buckets produce an empty plot`() {
        val plot = plot(emptyList(), start, start + hour)
        assertTrue(plot.isEmpty)
        assertEquals(0, plot.segments.size)
    }

    @Test
    fun `single bucket draws a band and one point segment`() {
        val bucket = ChartBucket(start, start + hour, min = 20.0, max = 24.0, avg = 22.0)
        val plot = plot(listOf(bucket), start, start + hour)

        assertEquals(1, plot.buckets.size)
        assertEquals(1, plot.segments.size)
        assertEquals(1, plot.segments.single().xs.size)
        // 单桶时 Y 轴围绕真实值展开，min/max 分别落到底/顶附近。
        assertEquals(20.0, plot.minValue, 0.001)
        assertEquals(24.0, plot.maxValue, 0.001)
        assertTrue(plot.buckets.single().yMax < plot.buckets.single().yMin)
    }

    @Test
    fun `evenly spaced buckets share one continuous segment`() {
        val buckets = (0 until 4).map { index ->
            ChartBucket(
                start = start + index * hour,
                end = start + (index + 1) * hour,
                min = 20.0 + index,
                max = 24.0 + index,
                avg = 22.0 + index,
            )
        }
        val plot = plot(buckets, start, start + 4 * hour)

        assertEquals(1, plot.segments.size)
        assertEquals(4, plot.segments.single().xs.size)
        // 等距桶在时间轴上等距：每 60px 一格（跨度 4h、宽 600px）。
        assertEquals(0f, plot.segments.single().xs[0], 0.001f)
        assertEquals(150f, plot.segments.single().xs[1], 0.001f)
        assertEquals(300f, plot.segments.single().xs[2], 0.001f)
        assertEquals(450f, plot.segments.single().xs[3], 0.001f)
    }

    @Test
    fun `gap between buckets breaks the line into two segments`() {
        val first = ChartBucket(start, start + hour, min = 20.0, max = 21.0, avg = 20.5)
        // 中间空了一个小时：服务端不会返回该桶。
        val second = ChartBucket(
            start + 2 * hour,
            start + 3 * hour,
            min = 25.0,
            max = 26.0,
            avg = 25.5,
        )
        val plot = plot(listOf(first, second), start, start + 3 * hour)

        assertEquals(2, plot.buckets.size)
        assertEquals(2, plot.segments.size)
        assertEquals(1, plot.segments[0].xs.size)
        assertEquals(1, plot.segments[1].xs.size)
        // 第二个桶按真实时间定位在 2/3 处（400px），而不是按下标 1/1。
        assertEquals(400f, plot.segments[1].xs.single(), 0.001f)
    }

    @Test
    fun `x axis is positioned by start time not array index`() {
        // 两个桶相隔 20 小时，但数组只有两个元素；若按下标等距会画到中点。
        val first = ChartBucket(start, start + hour, 10.0, 12.0, 11.0)
        val second = ChartBucket(start + 20 * hour, start + 21 * hour, 30.0, 32.0, 31.0)
        val plot = plot(listOf(first, second), start, start + 24 * hour)

        assertEquals(0f, plot.buckets[0].xStart, 0.001f)
        assertEquals(500f, plot.buckets[1].xStart, 0.001f)
        assertEquals(2, plot.segments.size)
    }

    @Test
    fun `thirty days with 24 buckets keeps time proportion`() {
        val buckets = (0 until 24).map { index ->
            ChartBucket(
                start = start + index * day,
                end = start + (index + 1) * day,
                min = 18.0 + index % 3,
                max = 22.0 + index % 3,
                avg = 20.0 + index % 3,
            )
        }
        val plot = plot(buckets, start, start + 30 * day)

        assertEquals(24, plot.buckets.size)
        // 每天一格：600px / 30 天 = 20px；下标 23 落在 460px。
        assertEquals(20f, plot.buckets[1].xStart, 0.01f)
        assertEquals(460f, plot.buckets[23].xStart, 0.01f)
        // 24 个桶都有数据 → 一条连续折线。
        assertEquals(1, plot.segments.size)
        assertEquals(24, plot.segments.single().xs.size)
    }

    @Test
    fun `y axis adapts to real min and max`() {
        val buckets = listOf(
            ChartBucket(start, start + hour, 0.0, 5.0, 2.0),
            ChartBucket(start + hour, start + 2 * hour, 40.0, 60.0, 50.0),
        )
        val plot = plot(buckets, start, start + 2 * hour)

        assertEquals(0.0, plot.minValue, 0.001)
        assertEquals(60.0, plot.maxValue, 0.001)
        // 最高值顶点、最低值底端。
        assertEquals(0f, plot.buckets[1].yMax, 0.001f)
        assertEquals(300f, plot.buckets[0].yMin, 0.001f)
    }

    @Test
    fun `non finite buckets are filtered out`() {
        val buckets = listOf(
            ChartBucket(start, start + hour, Double.NaN, Double.NaN, Double.NaN),
            ChartBucket(start + hour, start + 2 * hour, 20.0, 22.0, 21.0),
            ChartBucket(start + 2 * hour, start + 3 * hour, Double.NaN, Double.NaN, Double.POSITIVE_INFINITY),
        )
        val plot = plot(buckets, start, start + 3 * hour)

        assertEquals(1, plot.buckets.size)
        assertEquals(21.0, plot.buckets.single().bucket.avg, 0.001)
    }

    @Test
    fun `non finite min max falls back to avg`() {
        val bucket = ChartBucket(start, start + hour, Double.NaN, Double.NaN, 21.0)
        val plot = plot(listOf(bucket), start, start + hour)

        assertEquals(21.0, plot.buckets.single().bucket.min, 0.001)
        assertEquals(21.0, plot.buckets.single().bucket.max, 0.001)
    }

    @Test
    fun `flat series gets a padded y axis`() {
        val buckets = listOf(
            ChartBucket(start, start + hour, 20.0, 20.0, 20.0),
            ChartBucket(start + hour, start + 2 * hour, 20.0, 20.0, 20.0),
        )
        val plot = plot(buckets, start, start + 2 * hour)

        assertTrue(plot.maxValue > plot.minValue)
        assertEquals(20.0, plot.buckets.singleOrNull()?.bucket?.avg ?: 20.0, 0.001)
    }

    @Test
    fun `invalid window returns empty plot instead of crashing`() {
        assertTrue(plot(emptyList(), start, start).isEmpty)
        assertTrue(plot(emptyList(), start, start - hour).isEmpty)
    }
}
