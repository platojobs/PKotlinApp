package com.platojobs.pkotlinapp.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.platojobs.pkotlinapp.R

/** 错误块：文案 + 可重试按钮；由 [ThreeStateHost] 插到页面内容区。 */
class ErrorBlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val title: TextView
    private val hint: TextView
    private val retry: MaterialButton

    init {
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_error_block, this, true)
        title = findViewById(R.id.errorTitle)
        hint = findViewById(R.id.errorHint)
        retry = findViewById(R.id.errorRetry)
    }

    fun bind(message: CharSequence?, onRetry: () -> Unit) {
        title.setText(R.string.state_error_title)
        hint.text = message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.state_error_hint)
        retry.setOnClickListener { onRetry() }
    }
}
