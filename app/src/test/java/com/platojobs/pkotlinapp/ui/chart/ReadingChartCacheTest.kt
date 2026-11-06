package com.platojobs.pkotlinapp.ui.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * onDraw 性能契约：几何/轴标签只在数据或尺寸变化时重建一次；
 * 重复绘制与点选命中都复用缓存，避免每帧重新分配 Plot/Segment/标签字符串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReadingChartCacheTest {

    private val buckets = listOf(
        ChartBucket(start = 1_000L, end = 2_000L, min = 18.0, max = 22.0, avg = 20.0, count = 3),
        ChartBucket(start = 3_000L, end = 4_000L, min = 19.0, max = 25.0, avg = 23.0, count = 2),
        ChartBucket(start = 5_000L, end = 6_000L, min = 17.0, max = 21.0, avg = 19.0, count = 4),
    )

    @Test
    fun `geometry is built once and reused across draws`() {
        val view = ReadingChartView(RuntimeEnvironment.getApplication())
        view.setSeries(buckets, fromMillis = 1_000L, toMillis = 6_000L, unitLabel = "°C")

        layout(view, width = 600, height = 300)
        drawInto(view, width = 600, height = 300)
        val buildsAfterFirstDraw = view.geometryBuildCount
        assertEquals(1, buildsAfterFirstDraw)

        // 连续重绘不应重建几何或重新格式化标签。
        drawInto(view, width = 600, height = 300)
        drawInto(view, width = 600, height = 300)
        assertEquals(buildsAfterFirstDraw, view.geometryBuildCount)

        // 尺寸变化只重建一次。
        layout(view, width = 800, height = 320)
        drawInto(view, width = 800, height = 320)
        assertEquals(buildsAfterFirstDraw + 1, view.geometryBuildCount)
    }

    @Test
    fun `data change invalidates cached geometry`() {
        val view = ReadingChartView(RuntimeEnvironment.getApplication())
        view.setSeries(buckets, 1_000L, 6_000L, "°C")
        layout(view, 600, 300)
        drawInto(view, 600, 300)

        view.setSeries(
            listOf(ChartBucket(start = 1_000L, end = 2_000L, min = 30.0, max = 34.0, avg = 32.0, count = 1)),
            1_000L,
            2_000L,
            "°C",
        )
        drawInto(view, 600, 300)
        assertEquals(2, view.geometryBuildCount)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun drawInto(view: View, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }
}
