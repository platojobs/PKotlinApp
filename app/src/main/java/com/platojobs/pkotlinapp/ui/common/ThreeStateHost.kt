package com.platojobs.pkotlinapp.ui.common

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.platojobs.pkotlinapp.R

/**
 * 五模块统一的三态控制：
 * - 骨架：延迟 300ms 再出现，且至少展示 300ms，避免闪烁；行高与真实内容一致。
 * - 空态：真实文案 + 主行动按钮。
 * - 错误：文案 + 重试。
 *
 * 任何状态切换都会先取消未执行的骨架，防止旧请求把新页面覆盖。
 */
class ThreeStateHost(
    private val content: View,
    private val skeleton: SkeletonView,
    private val overlay: FrameLayout,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSkeleton: Runnable? = null
    private var shownAt = 0L
    private var emptyView: EmptyStateView? = null
    private var errorView: ErrorBlockView? = null

    fun showLoading() {
        pendingSkeleton?.let(handler::removeCallbacks)
        hideEmptyAndError()
        content.visibility = View.GONE
        skeleton.visibility = View.GONE
        val delay = content.context.resources.getInteger(R.integer.skeleton_delay_ms).toLong()
        pendingSkeleton = Runnable {
            if (pendingSkeleton != null) {
                skeleton.visibility = View.VISIBLE
                shownAt = System.currentTimeMillis()
            }
        }.also { handler.postDelayed(it, delay) }
    }

    fun showContent() {
        pendingSkeleton?.let(handler::removeCallbacks)
        pendingSkeleton = null
        hideEmptyAndError()
        content.visibility = View.VISIBLE
        hideSkeletonWithMinimumDuration()
    }

    fun showEmpty(
        title: CharSequence,
        hint: CharSequence?,
        action: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ) {
        pendingSkeleton?.let(handler::removeCallbacks)
        pendingSkeleton = null
        skeleton.visibility = View.GONE
        content.visibility = View.GONE
        removeError()
        val view = emptyView ?: EmptyStateView(overlay.context).also {
            emptyView = it
            overlay.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER,
            ))
        }
        view.visibility = View.VISIBLE
        view.setTitle(title)
        view.setHint(hint)
        view.setAction(action, onAction)
    }

    fun showError(message: CharSequence?, onRetry: () -> Unit) {
        pendingSkeleton?.let(handler::removeCallbacks)
        pendingSkeleton = null
        skeleton.visibility = View.GONE
        content.visibility = View.GONE
        removeEmpty()
        val view = errorView ?: ErrorBlockView(overlay.context).also {
            errorView = it
            overlay.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER,
            ).apply {
                leftMargin = overlay.resources.getDimensionPixelSize(R.dimen.page_margin)
                rightMargin = leftMargin
            })
        }
        view.visibility = View.VISIBLE
        view.bind(message, onRetry)
    }

    private fun hideSkeletonWithMinimumDuration() {
        if (skeleton.visibility != View.VISIBLE) {
            skeleton.visibility = View.GONE
            return
        }
        val minShow = overlay.resources.getInteger(R.integer.skeleton_min_show_ms).toLong()
        val elapsed = System.currentTimeMillis() - shownAt
        if (elapsed >= minShow) {
            skeleton.visibility = View.GONE
        } else {
            handler.postDelayed({ skeleton.visibility = View.GONE }, minShow - elapsed)
        }
    }

    private fun hideEmptyAndError() {
        emptyView?.visibility = View.GONE
        errorView?.visibility = View.GONE
    }

    private fun removeEmpty() {
        emptyView?.let(overlay::removeView)
        emptyView = null
    }

    private fun removeError() {
        errorView?.let(overlay::removeView)
        errorView = null
    }
}
