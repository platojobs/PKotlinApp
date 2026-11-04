package com.platojobs.pkotlinapp.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.platojobs.pkotlinapp.R

/**
 * 代码构建的空状态视图：图标、标题、提示、按钮全部在 Kotlin 中组装，没有对应 XML。
 * 使用方式：`EmptyStateView(context).also { it.setMessage(...); it.onActionClick = ... }`
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onActionClick: (() -> Unit)? = null

    private val iconView = ImageView(context).apply {
        setImageResource(R.drawable.ic_empty_todo)
        imageTintList = ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val titleView = TextView(context).apply {
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
    }

    private val hintView = TextView(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private val actionButton = MaterialButton(context).apply {
        setText(R.string.todo_empty_action)
        setOnClickListener { onActionClick?.invoke() }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(iconView, LayoutParams(dp(64), dp(64)))
        addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        addView(hintView, LayoutParams(dp(280), LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(actionButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        visibility = GONE
    }

    fun setMessage(title: CharSequence, hint: CharSequence) {
        titleView.text = title
        hintView.text = hint
    }

    private fun themeColor(attrRes: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attrRes, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
