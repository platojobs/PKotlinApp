package com.platojobs.pkotlinapp.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 折线图几何：0/1/60 个点都必须有确定结果，不出现除零或 NaN。 */
class ReadingChartGeometryTest {

    private val left = 40f
    private val top = 10f
    private val right = 340f
    private val bottom = 170f

    @Test
    fun `zero points yields empty plot`() {
        val plot = ReadingChartGeometry.plot(emptyList(), left, top, right, bottom)
        assertTrue(plot.isEmpty)
        assertTrue(plot.xs.isEmpty())
        assertTrue(plot.ys.isEmpty())
    }

    @Test
    fun `single point is centered without division by zero`() {
        val plot = ReadingChartGeometry.plot(
            listOf(ChartPoint(22.5, 1_760_000_000_000L)),
            left,
            top,
            right,
            bottom,
        )
        assertEquals(1, plot.xs.size)
        assertEquals((left + right) / 2f, plot.xs[0], 0.01f)
        assertTrue(plot.ys[0].isFinite())
        assertTrue(plot.ys[0] in top..bottom)
    }

    @Test
    fun `equal values are padded and stay inside bounds`() {
        val plot = ReadingChartGeometry.plot(
            List(3) { ChartPoint(60.0, 1_760_000_000_000L + it) },
            left,
            top,
            right,
            bottom,
        )
        assertEquals(3, plot.xs.size)
        plot.ys.forEach {
            assertTrue(it.isFinite())
            assertTrue(it in top..bottom)
        }
        assertTrue(plot.maxValue > plot.minValue)
    }

    @Test
    fun `sixty points are evenly spaced and finite`() {
        val points = List(60) { index ->
            ChartPoint(10.0 + index * 0.5, 1_760_000_000_000L + index * 1000L)
        }
        val plot = ReadingChartGeometry.plot(points, left, top, right, bottom)
        assertEquals(60, plot.xs.size)
        assertEquals(60, plot.ys.size)
        assertEquals(left, plot.xs.first(), 0.01f)
        assertEquals(right, plot.xs.last(), 0.01f)
        for (index in 1 until plot.xs.size) {
            assertTrue(plot.xs[index] > plot.xs[index - 1])
        }
        plot.ys.forEach {
            assertTrue(it.isFinite())
            assertTrue(it in top..bottom)
        }
        plot.xs.forEach { assertFalse(it.isNaN()) }
    }

    @Test
    fun `non finite values are dropped`() {
        val plot = ReadingChartGeometry.plot(
            listOf(
                ChartPoint(Double.NaN, 0L),
                ChartPoint(21.0, 1L),
                ChartPoint(Double.POSITIVE_INFINITY, 2L),
            ),
            left,
            top,
            right,
            bottom,
        )
        assertEquals(1, plot.xs.size)
    }
}
