package com.platojobs.pkotlinapp.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.platojobs.pkotlinapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 代码自绘折线图：onDraw 直接绘制坐标轴、网格、折线与数据点，不依赖第三方图表库。
 * 几何计算委托给 [ReadingChartGeometry]，0/1/60 个点都不会除零或越界。
 */
class ReadingChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePath = Path()
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var points: List<ChartPoint> = emptyList()

    init {
        gridPaint.color = ContextCompat.getColor(context, R.color.chart_grid)
        gridPaint.strokeWidth = 1.dp
        axisPaint.color = ContextCompat.getColor(context, R.color.chart_axis)
        axisPaint.strokeWidth = 1.dp
        linePaint.color = ContextCompat.getColor(context, R.color.chart_line)
        linePaint.strokeWidth = 2.dp
        linePaint.strokeCap = Paint.Cap.ROUND
        pointPaint.color = ContextCompat.getColor(context, R.color.chart_point)
        labelPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        labelPaint.textSize = 10.sp
        updateContentDescription()
    }

    fun setPoints(values: List<ChartPoint>) {
        points = values
        updateContentDescription()
        invalidate()
    }

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

        val plot = ReadingChartGeometry.plot(points, left, top, right, bottom)
        if (plot.isEmpty) return

        // Y 轴上下界标签，便于读数量级；时间标签只标首尾。
        canvas.drawText(
            formatValue(plot.maxValue),
            paddingLeft.toFloat(),
            top + labelPaint.textSize,
            labelPaint,
        )
        canvas.drawText(
            formatValue(plot.minValue),
            paddingLeft.toFloat(),
            bottom,
            labelPaint,
        )
        if (points.size >= 2) {
            canvas.drawText(
                formatTime(points.first().recordedAt),
                left,
                (height - paddingBottom).toFloat(),
                labelPaint,
            )
            val last = formatTime(points.last().recordedAt)
            canvas.drawText(
                last,
                right - labelPaint.measureText(last),
                (height - paddingBottom).toFloat(),
                labelPaint,
            )
        }

        if (plot.xs.size == 1) {
            // 单点：只画数据点，不画折线。
            canvas.drawCircle(plot.xs[0], plot.ys[0], POINT_RADIUS_DP.dp, pointPaint)
            return
        }

        linePath.reset()
        linePath.moveTo(plot.xs[0], plot.ys[0])
        for (index in 1 until plot.xs.size) {
            linePath.lineTo(plot.xs[index], plot.ys[index])
        }
        canvas.drawPath(linePath, linePaint)
        for (index in plot.xs.indices) {
            canvas.drawCircle(plot.xs[index], plot.ys[index], POINT_RADIUS_DP.dp, pointPaint)
        }
    }

    private fun formatValue(value: Double): String =
        if (kotlin.math.abs(value - value.toLong()) < 0.05) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }

    private fun formatTime(timestamp: Long): String =
        if (timestamp <= 0L) "" else timeFormatter.format(Date(timestamp))

    private fun updateContentDescription() {
        contentDescription = context.getString(R.string.readings_chart_desc, points.size)
    }

    private val Int.dp: Float
        get() = this * resources.displayMetrics.density

    private val Int.sp: Float
        get() = this * resources.displayMetrics.scaledDensity

    private companion object {
        const val LABEL_WIDTH_DP = 34
        const val LABEL_HEIGHT_DP = 16
        const val GRID_LINES = 3
        const val POINT_RADIUS_DP = 3
    }
}
