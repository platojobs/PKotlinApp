package com.platojobs.pkotlinapp.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Space
import com.platojobs.pkotlinapp.R

/**
 * 三态里的骨架屏：延迟 300ms 由 [ThreeStateHost] 控制出现；
 * 行高与真实列表一致（56/72/88），避免内容替换时跳动。
 */
class SkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val pulse = ValueAnimator.ofFloat(0.45f, 1f).apply {
        duration = RESOURCES_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { alpha = it.animatedValue as Float }
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        setPadding(pageMargin(), 0, pageMargin(), 0)
        buildRows(rows = 3, rowHeight = resources.getDimensionPixelSize(R.dimen.row_h_2line))
    }

    fun configure(rows: Int, rowHeightPx: Int = resources.getDimensionPixelSize(R.dimen.row_h_2line)) {
        buildRows(rows, rowHeightPx)
    }

    private fun buildRows(rows: Int, rowHeight: Int) {
        removeAllViews()
        repeat(rows) { index ->
            if (index > 0) {
                addView(Space(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(12))
                })
            }
            addView(android.view.View(context).apply {
                setBackgroundResource(R.drawable.bg_skeleton_block)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight)
            })
        }
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (!pulse.isStarted) pulse.start()
        } else {
            pulse.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    private fun pageMargin(): Int = resources.getDimensionPixelSize(R.dimen.page_margin)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val RESOURCES_MS = 900L
    }
}
