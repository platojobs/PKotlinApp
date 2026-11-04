package com.platojobs.pkotlinapp.ui.common

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.platojobs.pkotlinapp.R

/**
 * 完全由 Kotlin 代码构建的空状态视图（不依赖 XML 布局）。
 * 双 View 构建约定中“代码建 View”的代表：图标 + 标题 + 说明 + 可选操作按钮。
 */
class EmptyStateView(
    context: Context,
    title: CharSequence? = null,
    hint: CharSequence? = null,
    @DrawableRes iconRes: Int = R.drawable.ic_empty_ble,
) : LinearLayout(context) {

    private val iconView = ImageView(context)
    private val titleView = TextView(context)
    private val hintView = TextView(context)
    private val actionButton = MaterialButton(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        iconView.layoutParams = LayoutParams(56.dp, 56.dp)
        iconView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iconView.setImageResource(iconRes)
        iconView.imageTintList =
            ContextCompat.getColorStateList(context, R.color.text_secondary)
        iconView.contentDescription = context.getString(R.string.empty_state_icon_desc)

        titleView.apply {
            text = title
            gravity = Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.dp }
        }

        hintView.apply {
            text = hint
            gravity = Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 4.dp }
        }

        actionButton.apply {
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.dp }
        }

        addView(iconView)
        addView(titleView)
        addView(hintView)
        addView(actionButton)
        setPadding(16.dp, 24.dp, 16.dp, 24.dp)

        setTitle(title)
        setHint(hint)
    }

    fun setIcon(@DrawableRes resId: Int) {
        iconView.setImageResource(resId)
    }

    fun setTitle(text: CharSequence?) {
        titleView.text = text
        titleView.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setHint(text: CharSequence?) {
        hintView.text = text
        hintView.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setAction(text: CharSequence?, onClick: (() -> Unit)? = null) {
        if (text.isNullOrEmpty()) {
            actionButton.visibility = GONE
            actionButton.setOnClickListener(null)
            return
        }
        actionButton.text = text
        actionButton.visibility = VISIBLE
        actionButton.setOnClickListener { onClick?.invoke() }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
