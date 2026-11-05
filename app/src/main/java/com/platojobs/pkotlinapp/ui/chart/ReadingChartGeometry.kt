package com.platojobs.pkotlinapp.ui.chart

/** 图表数据点；只保留有限值。 */
data class ChartPoint(val value: Double, val recordedAt: Long)

/**
 * 折线图几何计算（纯 Kotlin，无 Android 依赖）：
 * - 0 个点时返回空；
 * - 1 个点或所有点同值时，纵向范围围绕该值展开，避免除零；
 * - 横坐标按序号等距分布，单点居中。
 */
object ReadingChartGeometry {

    data class Plot(
        val xs: List<Float>,
        val ys: List<Float>,
        val minValue: Double,
        val maxValue: Double,
    ) {
        val isEmpty: Boolean get() = xs.isEmpty()
    }

    private const val EPSILON = 1e-9
    private const val MIN_SPAN = 1.0

    fun plot(
        points: List<ChartPoint>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Plot {
        val values = points.map { it.value }.filter { it.isFinite() }
        if (values.isEmpty() || right <= left || bottom <= top) {
            return Plot(emptyList(), emptyList(), 0.0, 0.0)
        }

        var lo = values.first()
        var hi = values.first()
        values.forEach {
            if (it < lo) lo = it
            if (it > hi) hi = it
        }
        // 同值或单点：围绕该值展开固定范围，轴线仍然可绘制。
        if (hi - lo < EPSILON) {
            val halfSpan = maxOf(kotlin.math.abs(lo) * 0.1, MIN_SPAN / 2.0)
            lo -= halfSpan
            hi += halfSpan
        }

        val span = hi - lo
        val count = values.size
        val xs = ArrayList<Float>(count)
        val ys = ArrayList<Float>(count)
        values.forEachIndexed { index, value ->
            val x = if (count == 1) {
                (left + right) / 2f
            } else {
                left + (right - left) * index / (count - 1)
            }
            val y = bottom - ((value - lo) / span).toFloat() * (bottom - top)
            xs += x
            ys += y
        }
        return Plot(xs, ys, lo, hi)
    }
}
