package com.platojobs.pkotlinapp.ui.common

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.platojobs.pkotlinapp.R

/**
 * 帮助中心弹窗：本地结构化 FAQ，每条可展开。
 * 「我的」与「市场」共用同一份条目，避免两套内容逐渐分叉。
 */
object HelpCenterDialog {

    data class Faq(
        @StringRes val question: Int,
        @StringRes val answer: Int,
    )

    /** 连接排障用到的两条（连不上 / 读数不刷新）。 */
    val troubleshooting: List<Faq> = listOf(
        Faq(R.string.help_faq_connect_q, R.string.help_faq_connect_a),
        Faq(R.string.help_faq_readings_q, R.string.help_faq_readings_a),
    )

    val all: List<Faq> = listOf(
        Faq(R.string.help_faq_pairing_q, R.string.help_faq_pairing_a),
        Faq(R.string.help_faq_connect_q, R.string.help_faq_connect_a),
        Faq(R.string.help_faq_readings_q, R.string.help_faq_readings_a),
        Faq(R.string.help_faq_sync_q, R.string.help_faq_sync_a),
        Faq(R.string.help_faq_leave_q, R.string.help_faq_leave_a),
    )

    fun show(context: Context, faqs: List<Faq> = all) {
        val padding = context.resources.getDimensionPixelSize(R.dimen.card_padding)
        val gap = context.resources.getDimensionPixelSize(R.dimen.space_8)
        val touch = context.resources.getDimensionPixelSize(R.dimen.touch_min)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, gap, padding, gap)
        }
        faqs.forEach { faq ->
            val question = TextView(context).apply {
                setText(faq.question)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
                )
                minHeight = touch
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(faq.question)
            }
            val answer = TextView(context).apply {
                setText(faq.answer)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                )
                visibility = View.GONE
            }
            question.setOnClickListener {
                answer.isVisible = !answer.isVisible
            }
            container.addView(question)
            container.addView(answer)
        }

        val scroll = ScrollView(context).apply { addView(container) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.help_title)
            .setView(scroll)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }
}
