package com.platojobs.pkotlinapp.ui.chart

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
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
    private var onBucketSelected: ((ChartBucket?) -> Unit)? = null
    private val largeFont = resources.configuration.fontScale >= LARGE_FONT_SCALE

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
        val left = leftEdge()
        val top = paddingTop + 6.dp
        val right = width - paddingRight - 6.dp
        val bottom = height - paddingBottom - LABEL_HEIGHT_DP.dp
        if (right <= left || bottom <= top) return
        val plot = ReadingChartGeometry.plot(buckets, from, to, left, top, right, bottom)
        val hit = ReadingChartGeometry.hitTest(plot, x, HIT_TOLERANCE_DP.dp)
        selectedStart = hit?.bucket?.start
        updateContentDescription()
        invalidate()
        onBucketSelected?.invoke(hit?.bucket)
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = leftEdge()
        val top = paddingTop + 6.dp
        val right = width - paddingRight - 6.dp
        val bottom = height - paddingBottom - LABEL_HEIGHT_DP.dp
        if (right <= left || bottom <= top) return

        // 网格与坐标轴：即使没有数据也画出空白坐标系，避免布局跳动。
        // 200% 字号下 Y 轴只保留 3 个刻度（上下界 + 中位），减少标签相互挤压。
        val intervals = yTickIntervals
        for (index in 0..intervals) {
            val y = top + (bottom - top) * index.toFloat() / intervals.toFloat()
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawLine(left, top, left, bottom, axisPaint)
        canvas.drawLine(left, bottom, right, bottom, axisPaint)

        val plot = ReadingChartGeometry.plot(buckets, from, to, left, top, right, bottom)
        if (plot.isEmpty) return

        // Y 轴上下界标签带真实单位；X 轴只标区间首尾。
        canvas.drawText(
            formatValue(plot.maxValue) + unit,
            paddingLeft.toFloat(),
            top + labelPaint.textSize,
            labelPaint,
        )
        canvas.drawText(
            formatValue(plot.minValue) + unit,
            paddingLeft.toFloat(),
            bottom,
            labelPaint,
        )
        if (largeFont) {
            val midValue = (plot.maxValue + plot.minValue) / 2
            canvas.drawText(
                formatValue(midValue) + unit,
                paddingLeft.toFloat(),
                (top + bottom) / 2 + labelPaint.textSize / 2,
                labelPaint,
            )
        }
        if (from > 0L && to > from) {
            val spanHours = (to - from) / 3_600_000L
            val formatter = if (spanHours > 48) dayFormatter else timeFormatter
            canvas.drawText(
                formatter.format(Date(from)),
                left,
                (height - paddingBottom).toFloat(),
                labelPaint,
            )
            val last = formatter.format(Date(to))
            canvas.drawText(
                last,
                right - labelPaint.measureText(last),
                (height - paddingBottom).toFloat(),
                labelPaint,
            )
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
        val selected = selectedStart?.let { start ->
            plot.buckets.firstOrNull { it.bucket.start == start }
        }
        if (selected != null) {
            val highlightRight = selected.xEnd.coerceAtLeast(selected.xStart + 1f)
            canvas.drawLine(selected.xStart, top, selected.xStart, bottom, linePaint)
            canvas.drawRect(selected.xStart, top, highlightRight, bottom, selectionPaint)
            canvas.drawCircle(selected.xStart, selected.yAvg, SELECTED_POINT_RADIUS_DP.dp, pointPaint)
        }
    }

    private fun formatValue(value: Double): String =
        if (kotlin.math.abs(value - value.toLong()) < 0.05) {
            String.format(Locale.getDefault(), "%.0f", value)
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }

    /**
     * 左侧 Y 轴标签区宽度随字号与数值文本自适应，避免超大字号下标签压到绘图区。
     */
    private fun leftEdge(): Float {
        val finite = buckets.filter { it.min.isFinite() && it.max.isFinite() }
        val high = formatValue(finite.maxOfOrNull { it.max } ?: 0.0) + unit
        val low = formatValue(finite.minOfOrNull { it.min } ?: 0.0) + unit
        val needed = maxOf(labelPaint.measureText(high), labelPaint.measureText(low)) + 4.dp
        return paddingLeft + maxOf(LABEL_WIDTH_DP.dp, needed)
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

    private val Int.dp: Float
        get() = this * resources.displayMetrics.density

    private val Int.sp: Float
        get() = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            toFloat(),
            resources.displayMetrics,
        )

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
