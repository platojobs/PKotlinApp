package com.platojobs.pkotlinapp.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.platojobs.pkotlinapp.R
import kotlin.math.roundToInt

/**
 * 自定义 View：完全用 Kotlin 负责测量与绘制，用来展示待办完成进度。
 *
 * 支持自定义属性：
 * - tpv_trackColor     进度条底色
 * - tpv_progressColor  已完成进度颜色
 * - tpv_labelColor     文字颜色
 */
class TodoProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.progress_track)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.brand_primary)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = dp(14f)
    }

    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelPaint.color
        textSize = dp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val trackHeight = dp(8f)
    private val trackCorner = trackHeight / 2f
    private val labelGap = dp(10f)
    private val trackRect = RectF()

    private var doneCount = 0
    private var totalCount = 0
    private var fraction = 0f

    init {
        val typedArray = context.obtainStyledAttributes(
            attrs,
            R.styleable.TodoProgressView,
            defStyleAttr,
            0,
        )
        try {
            trackPaint.color = typedArray.getColor(
                R.styleable.TodoProgressView_tpv_trackColor,
                trackPaint.color,
            )
            progressPaint.color = typedArray.getColor(
                R.styleable.TodoProgressView_tpv_progressColor,
                progressPaint.color,
            )
            val labelColor = typedArray.getColor(
                R.styleable.TodoProgressView_tpv_labelColor,
                labelPaint.color,
            )
            labelPaint.color = labelColor
            percentPaint.color = labelColor
        } finally {
            typedArray.recycle()
        }
    }

    /**
     * 更新进度并重绘。total 为 0 时展示 0%，不会出现除零。
     */
    fun setProgress(done: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(0)
        val safeDone = done.coerceIn(0, safeTotal)
        if (safeDone == doneCount && safeTotal == totalCount) return

        doneCount = safeDone
        totalCount = safeTotal
        fraction = if (safeTotal == 0) 0f else safeDone.toFloat() / safeTotal
        contentDescription = context.getString(R.string.todo_progress_label, doneCount, totalCount)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val desiredWidth = dp(220f).toInt()
        val width = if (widthMode == MeasureSpec.EXACTLY) widthSize else minOf(desiredWidth, widthSize)

        val fontMetrics = labelPaint.fontMetrics
        val textHeight = (fontMetrics.bottom - fontMetrics.top).toInt()
        val desiredHeight = paddingTop + paddingBottom + textHeight + labelGap.toInt() + trackHeight.toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return

        val fontMetrics = labelPaint.fontMetrics
        val baseline = paddingTop - fontMetrics.top

        val label = context.getString(R.string.todo_progress_label, doneCount, totalCount)
        canvas.drawText(label, left, baseline, labelPaint)

        val percentText = context.getString(R.string.todo_progress_percent, (fraction * 100).roundToInt())
        canvas.drawText(percentText, right - percentPaint.measureText(percentText), baseline, percentPaint)

        val trackTop = paddingTop + (fontMetrics.bottom - fontMetrics.top) + labelGap
        trackRect.set(left, trackTop, right, trackTop + trackHeight)
        canvas.drawRoundRect(trackRect, trackCorner, trackCorner, trackPaint)

        if (fraction > 0f) {
            trackRect.right = left + (right - left) * fraction
            // 进度极小时至少画出一个圆角宽度，避免视觉上像没有进度
            if (trackRect.width() < trackHeight) {
                trackRect.right = trackRect.left + trackHeight
            }
            canvas.drawRoundRect(trackRect, trackCorner, trackCorner, progressPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
