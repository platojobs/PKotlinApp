package com.platojobs.pkotlinapp.ui.common

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * 卡片按下 0.98（120ms），按钮不缩放只变色。
 * 返回 false，保证点击与列表滚动不被拦截；点击仍由系统 performClick 分发。
 */
@SuppressLint("ClickableViewAccessibility")
fun View.enableCardPressScale() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(120).start()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }
}
