package com.platojobs.pkotlinapp.ui.chart

import kotlin.math.max
import kotlin.math.min

/** 图表桶：与服务端 series 响应对应，已按 start 升序；[count]/[last] 用于点选详情。 */
data class ChartBucket(
    val start: Long,
    val end: Long,
    val min: Double,
    val max: Double,
    val avg: Double,
    val count: Long = 0L,
    val last: Double = Double.NaN,
)

/**
 * 长周期折线图几何（纯 Kotlin，无 Android 依赖）。
 *
 * 关键口径：
 * - X 轴按桶 `start` 在 [from, to] 中的**真实时间比例**定位，不按数组下标等距；
 * - 服务端只返回有数据的桶，桶间存在缺口时折线必须**断开**，不能跨越空桶连线；
 * - Y 轴按区间内真实 min/max 自适应；
 * - 非有限值（NaN/Inf）桶直接丢弃，缺 min/max 时回退到 avg。
 */
object ReadingChartGeometry {

    /** 一个桶在画布上的几何位置；[yMin]/[yMax] 用于绘制 min–max 区间带。 */
    data class PlottedBucket(
        val bucket: ChartBucket,
        val xStart: Float,
        val xEnd: Float,
        val yAvg: Float,
        val yMin: Float,
        val yMax: Float,
    )

    /** 连续桶组成的一段折线；缺口两侧分属不同 segment。 */
    data class Segment(val xs: List<Float>, val ys: List<Float>)

    data class Plot(
        val buckets: List<PlottedBucket>,
        val segments: List<Segment>,
        val minValue: Double,
        val maxValue: Double,
    ) {
        val isEmpty: Boolean get() = buckets.isEmpty()
    }

    private const val EPSILON = 1e-9
    private const val MIN_SPAN = 1.0

    fun plot(
        buckets: List<ChartBucket>,
        from: Long,
        to: Long,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Plot {
        val empty = Plot(emptyList(), emptyList(), 0.0, 0.0)
        if (right <= left || bottom <= top || to <= from) return empty

        val valid = buckets
            .filter { it.avg.isFinite() && it.start > 0L && it.end >= it.start }
            .sortedBy { it.start }
            .map { bucket ->
                // min/max 缺失或非有限时回退到 avg，避免画出 NaN。
                val lo = if (bucket.min.isFinite()) min(bucket.min, bucket.avg) else bucket.avg
                val hi = if (bucket.max.isFinite()) max(bucket.max, bucket.avg) else bucket.avg
                bucket.copy(min = lo, max = hi)
            }
        if (valid.isEmpty()) return empty

        var lo = valid.first().min
        var hi = valid.first().max
        valid.forEach {
            if (it.min < lo) lo = it.min
            if (it.max > hi) hi = it.max
        }
        // 同值或单桶：围绕该值展开固定范围，轴线仍然可绘制。
        if (hi - lo < EPSILON) {
            val halfSpan = maxOf(kotlin.math.abs(lo) * 0.1, MIN_SPAN / 2.0)
            lo -= halfSpan
            hi += halfSpan
        }

        val span = hi - lo
        val window = (to - from).toFloat()
        val width = right - left

        fun xOf(time: Long): Float {
            val ratio = (time - from).toFloat() / window
            return (left + ratio * width).coerceIn(left, right)
        }

        fun yOf(value: Double): Float {
            val ratio = ((value - lo) / span).toFloat()
            return (bottom - ratio * (bottom - top)).coerceIn(top, bottom)
        }

        val plotted = valid.map { bucket ->
            PlottedBucket(
                bucket = bucket,
                xStart = xOf(bucket.start),
                xEnd = xOf(bucket.end),
                yAvg = yOf(bucket.avg),
                yMin = yOf(bucket.min),
                yMax = yOf(bucket.max),
            )
        }

        // 缺口判定：上一桶的 end 与下一桶的 start 不连续时断开。
        val segments = mutableListOf<Segment>()
        var xs = mutableListOf<Float>()
        var ys = mutableListOf<Float>()
        plotted.forEachIndexed { index, point ->
            if (index > 0 && point.bucket.start > plotted[index - 1].bucket.end) {
                segments += Segment(xs, ys)
                xs = mutableListOf()
                ys = mutableListOf()
            }
            xs += point.xStart
            ys += point.yAvg
        }
        if (xs.isNotEmpty()) segments += Segment(xs, ys)

        return Plot(plotted, segments, lo, hi)
    }

    /**
     * 点选命中：取水平距离最近的桶；落在桶的 [xStart, xEnd] 内距离为 0。
     * 超过 [tolerancePx] 视为点空白，返回 null（调用方据此取消选中）。
     * 纯函数，不依赖 Android，便于单测最近桶 / 阈值内 / 空白三种情形。
     */
    fun hitTest(plot: Plot, x: Float, tolerancePx: Float): PlottedBucket? {
        if (plot.isEmpty || tolerancePx < 0f) return null
        var nearest: PlottedBucket? = null
        var nearestDistance = Float.MAX_VALUE
        plot.buckets.forEach { point ->
            val left = min(point.xStart, point.xEnd)
            val right = max(point.xStart, point.xEnd)
            val distance = when {
                x < left -> left - x
                x > right -> x - right
                else -> 0f
            }
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = point
            }
        }
        return nearest?.takeIf { nearestDistance <= tolerancePx }
    }
}
