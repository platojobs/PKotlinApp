package com.platojobs.pkotlinapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.platojobs.pkotlinapp.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.3x 与 2.0x 字号下的关键页面截断审计：
 * 用 Robolectric 真实测量并布局，逐条检查 TextView 的实际文本行高与容器高度，
 * 以及系统是否产生了省略号（横向或纵向截断都算失败）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-xhdpi")
class FontScaleTruncationTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun `key screens do not clip text at 1_3x and 2_0x font scale`() {
        val failures = mutableListOf<String>()
        listOf(1.3f, 2.0f).forEach { scale ->
            RuntimeEnvironment.setFontScale(scale)
            LAYOUTS.forEach { layout ->
                val root = inflate(layout)
                measure(root)
                walk(root) { view ->
                    if (view is TextView &&
                        view.isVisibleInHierarchy() &&
                        !view.text.isNullOrEmpty() &&
                        !view.isMaterialNavigationInternals()
                    ) {
                        val problem = view.truncationProblem()
                        if (problem != null) {
                            failures += "@${"%.1f".format(scale)}x " +
                                "${app.resources.getResourceEntryName(layout)} -> " +
                                "${view.describe()}: $problem"
                        }
                    }
                }
            }
        }
        RuntimeEnvironment.setFontScale(1.0f)
        assertTrue("字号放大后文本被截断：\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun TextView.truncationProblem(): String? {
        val textLayout = layout ?: return "未发生布局"
        if (textLayout.lineCount == 0) return "无文本行"
        for (line in 0 until textLayout.lineCount) {
            if (textLayout.getEllipsisCount(line) > 0) return "第 $line 行出现省略号"
        }
        val textBottom = textLayout.getLineBottom(textLayout.lineCount - 1)
        val available = height - compoundPaddingTop - compoundPaddingBottom
        return if (textBottom > available + 1) {
            "文本高 ${textBottom}px > 可用高 ${available}px"
        } else {
            null
        }
    }

    private fun inflate(layout: Int): View =
        LayoutInflater.from(ContextThemeWrapper(app, R.style.Theme_PKotlinApp))
            .inflate(layout, null, false)

    private fun measure(view: View) {
        val width = app.resources.displayMetrics.widthPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun View.isVisibleInHierarchy(): Boolean {
        if (visibility != View.VISIBLE) return false
        var parent = parent
        while (parent is View) {
            if (parent.visibility != View.VISIBLE) return false
            parent = parent.parent
        }
        return true
    }

    /** NavigationBarView 内部标签由库自行测量，不在本项目的布局责任范围内。 */
    private fun View.isMaterialNavigationInternals(): Boolean {
        if (id == View.NO_ID) return false
        val name = runCatching { app.resources.getResourceEntryName(id) }.getOrNull() ?: return false
        return name.startsWith("navigation_bar_item")
    }

    private fun walk(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) walk(view.getChildAt(index), action)
        }
    }

    private fun View.describe(): String {
        val id = if (id == View.NO_ID) "<no-id>" else app.resources.getResourceEntryName(id)
        return "${this::class.java.simpleName}#$id"
    }

    private companion object {
        val LAYOUTS = listOf(
            R.layout.activity_main,
            R.layout.activity_onboarding,
            R.layout.activity_device_detail,
            R.layout.activity_product_detail,
            R.layout.activity_feedback_history,
            R.layout.fragment_home,
            R.layout.fragment_market,
            R.layout.fragment_products,
            R.layout.fragment_profile,
            R.layout.fragment_profile_account,
            R.layout.fragment_profile_household,
            R.layout.fragment_intelligence,
            R.layout.detail_collection_controls,
            R.layout.dialog_device_edit,
            R.layout.dialog_rule_add,
            R.layout.dialog_scan,
            R.layout.dialog_scene_action,
            R.layout.dialog_scene_edit,
            R.layout.item_device_card,
            R.layout.item_home_room,
            R.layout.item_home_status,
            R.layout.item_product,
            R.layout.item_reading,
            R.layout.item_rule,
            R.layout.item_scene_card,
            R.layout.item_scene_row,
        )
    }
}
