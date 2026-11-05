package com.platojobs.pkotlinapp.ui.chart

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePath = Path()
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFormatter = SimpleDateFormat("MM-dd", Locale.getDefault())

    private var buckets: List<ChartBucket> = emptyList()
    private var from: Long = 0L
    private var to: Long = 0L
    private var unit: String = ""

    init {
        gridPaint.color = ContextCompat.getColor(context, R.color.chart_grid)
        gridPaint.strokeWidth = 1.dp
        axisPaint.color = ContextCompat.getColor(context, R.color.chart_axis)
        axisPaint.strokeWidth = 1.dp
        bandPaint.color = ContextCompat.getColor(context, R.color.chart_band)
        linePaint.color = ContextCompat.getColor(context, R.color.chart_line)
        linePaint.strokeWidth = 2.dp
        linePaint.strokeCap = Paint.Cap.ROUND
        pointPaint.color = ContextCompat.getColor(context, R.color.chart_point)
        labelPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        labelPaint.textSize = 10.sp
        updateContentDescription()
    }

    fun setSeries(
        values: List<ChartBucket>,
        fromMillis: Long,
        toMillis: Long,
        unitLabel: String,
    ) {
        buckets = values
        from = fromMillis
        to = toMillis
        unit = unitLabel
        updateContentDescription()
        invalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + LABEL_WIDTH_DP.dp
        val top = paddingTop + 6.dp
        val right = width - paddingRight - 6.dp
        val bottom = height - paddingBottom - LABEL_HEIGHT_DP.dp
        if (right <= left || bottom <= top) return

        // 网格与坐标轴：即使没有数据也画出空白坐标系，避免布局跳动。
        for (index in 0..GRID_LINES) {
            val y = top + (bottom - top) * index.toFloat() / GRID_LINES.toFloat()
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
    }

    private fun formatValue(value: Double): String =
        if (kotlin.math.abs(value - value.toLong()) < 0.05) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }

    private fun updateContentDescription() {
        contentDescription = context.getString(R.string.readings_chart_desc, buckets.size)
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
    }
}
