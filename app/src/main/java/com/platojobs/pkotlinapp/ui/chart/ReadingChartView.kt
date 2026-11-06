package com.platojobs.pkotlinapp.ui.chart

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.platojobs.pkotlinapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 代码自绘长周期折线图：
 * - 按桶 `start` 的真实时间比例定位 X 轴；
 * - 缺口处折线断开，不跨越空桶连线；
 * - 每个桶画 avg 折线，并用浅色区间带标出 min–max；
 * - 点击/长按选中桶，高亮并回调详情；点空白取消选中；
 * - 几何计算委托给 [ReadingChartGeometry]，空数据/单桶/缺口均安全。
 *
 * 性能约定：几何、轴标签与 X 轴日期只在数据/尺寸变化时计算一次并缓存，
 * `onDraw` 只做绘制，不再创建对象；Paint 与 Path 全部复用。
 */
class ReadingChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePath = Path()
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFormatter = SimpleDateFormat("MM-dd", Locale.getDefault())

    private var buckets: List<ChartBucket> = emptyList()
    private var from: Long = 0L
    private var to: Long = 0L
    private var unit: String = ""
    private var metricLabel: String = ""
    private var rangeLabel: String = ""
    private var selectedStart: Long? = null
    private var selectedPlotted: ReadingChartGeometry.PlottedBucket? = null
    private var onBucketSelected: ((ChartBucket?) -> Unit)? = null
    private val largeFont = resources.configuration.fontScale >= LARGE_FONT_SCALE

    /** 只在尺寸/数据/单位变化时重建；onDraw 直接读取。 */
    private var renderCache: RenderCache? = null

    /** 测试用：几何构建次数，验证 onDraw 不会重复计算。 */
    internal var geometryBuildCount: Int = 0
        private set

    /** 大字号下 Y 轴刻度区间数：2 段即 3 个刻度。 */
    internal val yTickIntervals: Int
        get() = if (largeFont) 2 else GRID_LINES

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                performClick()
                selectAt(e.x)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performClick()
                selectAt(e.x)
            }
        },
    )

    init {
        gridPaint.color = ContextCompat.getColor(context, R.color.chart_grid)
        gridPaint.strokeWidth = 1.dp
        axisPaint.color = ContextCompat.getColor(context, R.color.chart_axis)
        axisPaint.strokeWidth = 1.dp
        bandPaint.color = ContextCompat.getColor(context, R.color.chart_band)
        linePaint.color = ContextCompat.getColor(context, R.color.chart_line)
        linePaint.strokeWidth = 2.dp
        linePaint.strokeCap = Paint.Cap.ROUND
        pointPaint.color = ContextCompat.getColor(context, R.color.chart_line_2)
        selectionPaint.color = ContextCompat.getColor(context, R.color.chart_point)
        selectionPaint.style = Paint.Style.STROKE
        selectionPaint.strokeWidth = 2.dp
        labelPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        labelPaint.textSize = resources.getDimension(R.dimen.chart_label_text_size)
        minimumHeight = (if (largeFont) 220 else 160).dp.toInt()
        isClickable = true
        updateContentDescription()
    }

    fun setSeries(
        values: List<ChartBucket>,
        fromMillis: Long,
        toMillis: Long,
        unitLabel: String,
        metricLabelText: String = "",
        rangeLabelText: String = "",
    ) {
        buckets = values
        from = fromMillis
        to = toMillis
        unit = unitLabel
        metricLabel = metricLabelText
        rangeLabel = rangeLabelText
        renderCache = null
        selectedPlotted = null
        val selected = selectedStart?.let { start -> buckets.firstOrNull { it.start == start } }
        if (selected == null) {
            selectedStart = null
            onBucketSelected?.invoke(null)
        }
        updateContentDescription()
        invalidate()
    }

    fun setOnBucketSelectedListener(listener: ((ChartBucket?) -> Unit)?) {
        onBucketSelected = listener
    }

    fun currentSelection(): ChartBucket? = selectedStart?.let { start ->
        buckets.firstOrNull { it.start == start }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    private fun selectAt(x: Float) {
        val render = ensureRender() ?: return
        val hit = ReadingChartGeometry.hitTest(render.plot, x, HIT_TOLERANCE_DP.dp)
        selectedStart = hit?.bucket?.start
        selectedPlotted = hit
        updateContentDescription()
        invalidate()
        onBucketSelected?.invoke(hit?.bucket)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val render = ensureRender() ?: return

        // 网格与坐标轴：即使没有数据也画出空白坐标系，避免布局跳动。
        // 200% 字号下 Y 轴只保留 3 个刻度（上下界 + 中位），减少标签相互挤压。
        val intervals = yTickIntervals
        for (index in 0..intervals) {
            val y = render.top + (render.bottom - render.top) * index.toFloat() / intervals.toFloat()
            canvas.drawLine(render.left, y, render.right, y, gridPaint)
        }
        canvas.drawLine(render.left, render.top, render.left, render.bottom, axisPaint)
        canvas.drawLine(render.left, render.bottom, render.right, render.bottom, axisPaint)

        val plot = render.plot
        if (plot.isEmpty) return
        val maxLabel = render.maxLabel ?: return
        val minLabel = render.minLabel ?: return

        // Y 轴上下界标签带真实单位；X 轴只标区间首尾。文本已缓存，绘制不再分配。
        canvas.drawText(maxLabel, paddingLeft.toFloat(), render.top + labelPaint.textSize, labelPaint)
        canvas.drawText(minLabel, paddingLeft.toFloat(), render.bottom, labelPaint)
        render.midLabel?.let { mid ->
            canvas.drawText(mid, paddingLeft.toFloat(), (render.top + render.bottom) / 2 + labelPaint.textSize / 2, labelPaint)
        }
        if (render.fromLabel != null) {
            canvas.drawText(render.fromLabel, render.left, (height - paddingBottom).toFloat(), labelPaint)
            render.toLabel?.let { label ->
                canvas.drawText(
                    label,
                    render.right - render.toLabelWidth,
                    (height - paddingBottom).toFloat(),
                    labelPaint,
                )
            }
        }

        // min–max 区间带：先铺浅色带，再画 avg 折线与数据点。
        plot.buckets.forEach { point ->
            val bandLeft = point.xStart
            val bandRight = point.xEnd.coerceAtLeast(bandLeft + 1f)
            canvas.drawRect(bandLeft, point.yMax, bandRight, point.yMin, bandPaint)
        }

        plot.segments.forEach { segment ->
            if (segment.xs.size == 1) {
                canvas.drawCircle(segment.xs[0], segment.ys[0], POINT_RADIUS_DP.dp, pointPaint)
                return@forEach
            }
            linePath.reset()
            linePath.moveTo(segment.xs[0], segment.ys[0])
            for (index in 1 until segment.xs.size) {
                linePath.lineTo(segment.xs[index], segment.ys[index])
            }
            canvas.drawPath(linePath, linePaint)
        }
        plot.buckets.forEach { point ->
            canvas.drawCircle(point.xStart, point.yAvg, POINT_RADIUS_DP.dp, pointPaint)
        }

        // 选中桶：竖线 + 描边 + 放大的点，全部复用现有图表语义色。
        selectedPlotted?.let { selected ->
            val highlightRight = selected.xEnd.coerceAtLeast(selected.xStart + 1f)
            canvas.drawLine(selected.xStart, render.top, selected.xStart, render.bottom, linePaint)
            canvas.drawRect(selected.xStart, render.top, highlightRight, render.bottom, selectionPaint)
            canvas.drawCircle(selected.xStart, selected.yAvg, SELECTED_POINT_RADIUS_DP.dp, pointPaint)
        }
    }

    /**
     * 几何与标签缓存：尺寸或数据变化才重建，普通重绘直接命中。
     */
    private fun ensureRender(): RenderCache? {
        val left = paddingLeft + computeAxisLabelWidth()
        val top = paddingTop + 6.dp
        val right = width - paddingRight - 6.dp
        val bottom = height - paddingBottom - LABEL_HEIGHT_DP.dp
        if (right <= left || bottom <= top) return null

        val cached = renderCache
        if (cached != null &&
            cached.left == left &&
            cached.top == top &&
            cached.right == right &&
            cached.bottom == bottom
        ) {
            return cached
        }

        val plot = ReadingChartGeometry.plot(buckets, from, to, left, top, right, bottom)
        geometryBuildCount++
        val render = if (plot.isEmpty) {
            RenderCache(left, top, right, bottom, plot, null, null, null, null)
        } else {
            val maxLabel = formatValue(plot.maxValue) + unit
            val minLabel = formatValue(plot.minValue) + unit
            val midLabel = if (largeFont) {
                formatValue((plot.maxValue + plot.minValue) / 2) + unit
            } else {
                null
            }
            val fromLabel: String?
            val toLabel: String?
            if (from > 0L && to > from) {
                val spanHours = (to - from) / 3_600_000L
                val formatter = if (spanHours > 48) dayFormatter else timeFormatter
                fromLabel = formatter.format(Date(from))
                toLabel = formatter.format(Date(to))
            } else {
                fromLabel = null
                toLabel = null
            }
            RenderCache(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                plot = plot,
                maxLabel = maxLabel,
                minLabel = minLabel,
                midLabel = midLabel,
                fromLabel = fromLabel,
                toLabel = toLabel,
                toLabelWidth = toLabel?.let { labelPaint.measureText(it) } ?: 0f,
            )
        }
        renderCache = render
        selectedPlotted = selectedStart?.let { start ->
            plot.buckets.firstOrNull { it.bucket.start == start }
        }
        return render
    }

    private fun formatValue(value: Double): String =
        if (kotlin.math.abs(value - value.toLong()) < 0.05) {
            String.format(Locale.getDefault(), "%.0f", value)
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }

    /**
     * 左侧 Y 轴标签区宽度随字号与数值文本自适应，避免超大字号下标签压到绘图区。
     * 只在缓存重建时调用。
     */
    private fun computeAxisLabelWidth(): Float {
        val finite = buckets.filter { it.min.isFinite() && it.max.isFinite() }
        val high = formatValue(finite.maxOfOrNull { it.max } ?: 0.0) + unit
        val low = formatValue(finite.minOfOrNull { it.min } ?: 0.0) + unit
        val needed = maxOf(labelPaint.measureText(high), labelPaint.measureText(low)) + 4.dp
        return maxOf(LABEL_WIDTH_DP.dp, needed)
    }

    private fun updateContentDescription() {
        if (buckets.isEmpty()) {
            contentDescription = resources.getQuantityString(
                R.plurals.readings_chart_desc,
                0,
                0,
            )
            return
        }
        val selected = selectedStart?.let { start -> buckets.firstOrNull { it.start == start } }
        contentDescription = if (selected != null) {
            bucketDescription(selected)
        } else {
            summaryDescription()
        }
    }

    private fun summaryDescription(): String {
        val finite = buckets.filter { it.min.isFinite() && it.max.isFinite() }
        val low = finite.minOfOrNull { it.min } ?: Double.NaN
        val high = finite.maxOfOrNull { it.max } ?: Double.NaN
        val points = buckets.sumOf { if (it.count > 0L) it.count else 1L }
        return context.getString(
            R.string.readings_chart_a11y_summary,
            metricLabel,
            rangeLabel,
            formatValue(low) + unit,
            formatValue(high) + unit,
            points,
        )
    }

    private fun bucketDescription(bucket: ChartBucket): String {
        val last = if (bucket.last.isFinite()) bucket.last else bucket.avg
        val time = timeLabel(bucket.start, bucket.end)
        val count = if (bucket.count > 0L) bucket.count.toString() else UNKNOWN_COUNT
        return context.getString(
            R.string.readings_chart_a11y_bucket,
            metricLabel,
            time,
            formatValue(bucket.min) + unit,
            formatValue(bucket.max) + unit,
            formatValue(bucket.avg) + unit,
            formatValue(last) + unit,
            count,
        )
    }

    /** 本地时区展示；同一天省略重复日期。 */
    private fun timeLabel(startMs: Long, endMs: Long): String {
        val dateFormat = SimpleDateFormat(
            context.getString(R.string.time_format),
            Locale.getDefault(),
        )
        val timeOnly = SimpleDateFormat(
            context.getString(R.string.time_format_time),
            Locale.getDefault(),
        )
        val startText = dateFormat.format(Date(startMs))
        val endText = if (dateFormat.format(Date(endMs)) == startText) {
            timeOnly.format(Date(endMs))
        } else {
            dateFormat.format(Date(endMs))
        }
        return "$startText – $endText"
    }

    private data class RenderCache(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val plot: ReadingChartGeometry.Plot,
        val maxLabel: String?,
        val minLabel: String?,
        val midLabel: String?,
        val fromLabel: String?,
        val toLabel: String? = null,
        val toLabelWidth: Float = 0f,
    )

    private val Int.dp: Float
        get() = this * resources.displayMetrics.density

    private companion object {
        const val LABEL_WIDTH_DP = 40
        const val LABEL_HEIGHT_DP = 16
        const val GRID_LINES = 3
        const val POINT_RADIUS_DP = 3
        const val SELECTED_POINT_RADIUS_DP = 5
        const val HIT_TOLERANCE_DP = 16
        const val UNKNOWN_COUNT = "—"
        const val LARGE_FONT_SCALE = 1.5f
    }
}
