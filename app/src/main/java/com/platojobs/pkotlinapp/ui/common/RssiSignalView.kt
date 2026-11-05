package com.platojobs.pkotlinapp.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import com.platojobs.pkotlinapp.R

/**
 * 自绘 RSSI 信号强度 View：onMeasure 计算所需尺寸，onDraw 绘制柱状信号条。
 * 支持自定义属性 rsv_activeColor / rsv_inactiveColor / rsv_barCount。
 */
class RssiSignalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // 复用绘制矩形，避免 onDraw 每帧分配对象。
    private val barRect = RectF()
    private val barCount: Int
    private var rssi: Int? = null
    private var activeBars = 0

    init {
        var configuredBarCount = DEFAULT_BAR_COUNT
        context.withStyledAttributes(attrs, R.styleable.RssiSignalView, defStyleAttr, 0) {
            activePaint.color = getColor(
                R.styleable.RssiSignalView_rsv_activeColor,
                ContextCompat.getColor(context, R.color.success),
            )
            inactivePaint.color = getColor(
                R.styleable.RssiSignalView_rsv_inactiveColor,
                ContextCompat.getColor(context, R.color.divider),
            )
            configuredBarCount = getInt(
                R.styleable.RssiSignalView_rsv_barCount,
                DEFAULT_BAR_COUNT,
            )
        }
        barCount = configuredBarCount.coerceAtLeast(2)
        updateContentDescription()
    }

    fun setRssi(value: Int?) {
        if (rssi == value) return
        rssi = value
        activeBars = RssiLevel.bars(value, barCount)
        updateContentDescription()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val barWidth = 6.dp
        val gap = 4.dp
        val desiredWidth = barCount * barWidth + (barCount - 1) * gap +
            paddingLeft + paddingRight
        val desiredHeight = 24.dp + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        val gap = minOf(4.dp.toFloat(), availableWidth / (barCount * 4f))
        val barWidth = (availableWidth - gap * (barCount - 1)) / barCount
        if (barWidth <= 0f) return

        val minBarHeight = availableHeight * 0.35f
        val radius = barWidth / 2f
        for (index in 0 until barCount) {
            val ratio = (index + 1f) / barCount
            val barHeight = minBarHeight + (availableHeight - minBarHeight) * ratio
            val left = paddingLeft + index * (barWidth + gap)
            val top = paddingTop + availableHeight - barHeight
            val paint = if (index < activeBars) activePaint else inactivePaint
            barRect.set(left, top, left + barWidth, top + barHeight)
            canvas.drawRoundRect(barRect, radius, radius, paint)
        }
    }

    private fun updateContentDescription() {
        contentDescription = context.getString(
            R.string.rssi_content_desc,
            context.getString(RssiLevel.descriptionRes(rssi)),
        )
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_BAR_COUNT = 4
    }
}
