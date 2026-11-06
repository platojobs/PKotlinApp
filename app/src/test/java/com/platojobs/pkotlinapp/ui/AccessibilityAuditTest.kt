package com.platojobs.pkotlinapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
 * 无障碍静态审计：遍历所有布局的 View 树。
 * - 可点击控件：必须有 contentDescription 或可朗读文本（自身或后代）；
 * - ImageView：必须有描述，或显式声明为装饰（importantForAccessibility=no）；
 * - 可点击 bounds 两个方向都必须 ≥ 48dp（Material Chip 的触摸扩展在代码创建时另行保证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class AccessibilityAuditTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val density get() = app.resources.displayMetrics.density

    private fun inflate(layout: Int): View =
        LayoutInflater.from(ContextThemeWrapper(app, R.style.Theme_PKotlinApp))
            .inflate(layout, null, false)

    @Test
    fun `clickable controls expose a label`() {
        val failures = mutableListOf<String>()
        LAYOUTS.forEach { layout ->
            val root = inflate(layout)
            fillMissingStaticText(root)
            measure(root)
            walk(root) { view ->
                if (view.isClickable && view.isVisibleInHierarchy() && !view.hasLabel()) {
                    failures += "${app.resources.getResourceEntryName(layout)} -> ${view.describe()}"
                }
            }
        }
        assertTrue("可点击控件缺少可朗读文本/描述：\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun `images are described or explicitly decorative`() {
        val failures = mutableListOf<String>()
        LAYOUTS.forEach { layout ->
            val root = inflate(layout)
            fillMissingStaticText(root)
            measure(root)
            walk(root) { view ->
                if (view is ImageView && view.isVisibleInHierarchy()) {
                    val described = !view.contentDescription.isNullOrBlank()
                    val decorative = view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO ||
                        view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    // 图标位于带文本/描述的可点击父控件内部时，语义由父控件承担（如底部导航）。
                    val labelledByClickableAncestor = view.ancestors()
                        .any { it.isClickable && it.hasLabel() }
                    if (!described && !decorative && !labelledByClickableAncestor) {
                        failures += "${app.resources.getResourceEntryName(layout)} -> ${view.describe()}"
                    }
                }
            }
        }
        assertTrue("ImageView 缺少描述且未标记装饰：\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun `clickable bounds are at least 48dp`() {
        val failures = mutableListOf<String>()
        val minTouch = (48 * density).toInt()
        LAYOUTS.forEach { layout ->
            val root = inflate(layout)
            fillMissingStaticText(root)
            measure(root)
            walk(root) { view ->
                if (view.isClickable && view.isVisibleInHierarchy()) {
                    if (view.width < minTouch || view.height < minTouch) {
                        failures += "${app.resources.getResourceEntryName(layout)} -> " +
                            "${view.describe()} ${view.width}x${view.height}px (需 ${minTouch}px)"
                    }
                }
            }
        }
        assertTrue("触控目标小于 48dp：\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun measure(view: View) {
        val width = app.resources.displayMetrics.widthPixels
        val height = app.resources.displayMetrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        // 高于屏幕的区块（如「我的」里的账号区）会被 AT_MOST 压扁最后几个子项；
        // 用 UNSPECIFIED 重量一次，拿到真实自然高度，避免误报触控目标过小。
        if (view.measuredHeight >= height) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
        }
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    /** 布局里的文案运行时由适配器绑定；审计时补占位文本，验证“有文本可读”的结构。 */
    private fun fillMissingStaticText(root: View) {
        walk(root) { view ->
            if (view is TextView && view.text.isNullOrEmpty()) view.text = "内容"
        }
    }

    private fun View.hasLabel(): Boolean {
        if (!contentDescription.isNullOrBlank()) return true
        var found = false
        walk(this) { view ->
            if (view is TextView && view.visibility == View.VISIBLE && !view.text.isNullOrBlank()) {
                found = true
            }
        }
        return found
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

    private fun View.ancestors(): Sequence<View> = generateSequence(parent) { it.parent }
        .filterIsInstance<View>()

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
            R.layout.profile_about_card,
            R.layout.item_device_card,
            R.layout.item_feedback_footer,
            R.layout.item_feedback_history,
            R.layout.item_home_activity,
            R.layout.item_home_alert,
            R.layout.item_home_overview,
            R.layout.item_home_room,
            R.layout.item_home_scenes,
            R.layout.item_home_section,
            R.layout.item_home_status,
            R.layout.item_live_device,
            R.layout.item_product,
            R.layout.item_reading,
            R.layout.item_room_empty,
            R.layout.item_rule_event,
            R.layout.item_rule,
            R.layout.item_scan_log,
            R.layout.item_scene_card,
            R.layout.item_scene_edit_action,
            R.layout.item_scene_row,
            R.layout.item_scene_run,
        )
    }
}
