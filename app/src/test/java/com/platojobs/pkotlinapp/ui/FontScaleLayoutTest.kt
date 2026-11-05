package com.platojobs.pkotlinapp.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.NestedScrollView
import com.google.android.material.materialswitch.MaterialSwitch
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.ui.chart.ReadingChartView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 200% 字号下的布局测量：关键视图必须有正高度、不超过父宽，
 * 规则弹窗可滚动，图表 Y 轴缩为 3 个刻度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class FontScaleLayoutTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val density get() = app.resources.displayMetrics.density

    @Before
    fun setUp() {
        RuntimeEnvironment.setFontScale(2.0f)
        assertEquals(2.0f, app.resources.configuration.fontScale)
    }

    private fun inflate(layout: Int): View {
        val themed = ContextThemeWrapper(app, R.style.Theme_PKotlinApp)
        return LayoutInflater.from(themed).inflate(layout, null, false)
    }

    private fun measure(view: View, widthPx: Int, heightPx: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test
    fun `首页设备卡片在 200% 字号下不被截断`() {
        val card = inflate(R.layout.item_device_card)
        val name = card.findViewById<TextView>(R.id.deviceName)
        val value = card.findViewById<TextView>(R.id.readingValue)
        val label = card.findViewById<TextView>(R.id.readingLabel)
        name.text = "Temperature and humidity sensor with a long model name"
        value.text = "24.6 °C"
        label.text = "Last reading at 12:30"

        listOf(name, value, label).forEach { textView ->
            assertTrue("${textView.id} 开启了省略", textView.maxLines > 1)
        }

        val parentWidth = app.resources.displayMetrics.widthPixels
        measure(card, parentWidth, app.resources.displayMetrics.heightPixels)
        assertTrue("卡片高度为 0", card.measuredHeight > 0)
        assertTrue("卡片超出父宽", card.measuredWidth <= parentWidth)
    }

    @Test
    fun `规则列表开关与删除按钮触控区不小于 48dp`() {
        val row = inflate(R.layout.item_rule)
        val switch = row.findViewById<MaterialSwitch>(R.id.ruleSwitch)
        val delete = row.findViewById<View>(R.id.deleteRuleButton)
        assertTrue("开关触控高度不足 48dp", switch.minimumHeight >= (48 * density).toInt())
        assertTrue("开关触控宽度不足 48dp", switch.minimumWidth >= (48 * density).toInt())
        assertTrue("删除按钮触控宽度不足 48dp", delete.layoutParams.width >= (48 * density).toInt())
        assertTrue("删除按钮触控高度不足 48dp", delete.layoutParams.height >= (48 * density).toInt())

        measure(row, app.resources.displayMetrics.widthPixels, app.resources.displayMetrics.heightPixels)
        assertTrue("规则行高度为 0", row.measuredHeight > 0)
        assertTrue("规则行超出父宽", row.measuredWidth <= app.resources.displayMetrics.widthPixels)
    }

    @Test
    fun `规则弹窗在 200% 字号下可滚动且不超出父宽`() {
        val root = inflate(R.layout.dialog_rule_add)
        assertTrue("规则弹窗未使用可滚动容器", root is NestedScrollView)
        val width = app.resources.displayMetrics.widthPixels
        val height = app.resources.displayMetrics.heightPixels
        measure(root, width, height)
        assertTrue("弹窗高度为 0", root.measuredHeight > 0)
        assertTrue("弹窗高度超出父容器", root.measuredHeight <= height)
        assertTrue("弹窗宽度超出父容器", root.measuredWidth <= width)
        assertTrue("弹窗内容不可滚动", (root as NestedScrollView).isFillViewport)
    }

    @Test
    fun `图表在大字号下 Y 轴只保留 3 个刻度`() {
        val themed = ContextThemeWrapper(app, R.style.Theme_PKotlinApp)
        val chart = ReadingChartView(themed)
        assertEquals("大字号 Y 轴刻度数不为 3", 3, chart.yTickIntervals + 1)
        assertTrue(
            "图表高度未随字号增长",
            chart.minimumHeight >= (220 * density).toInt(),
        )
    }
}
