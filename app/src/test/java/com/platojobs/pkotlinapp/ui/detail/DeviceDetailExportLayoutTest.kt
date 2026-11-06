package com.platojobs.pkotlinapp.ui.detail

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.NestedScrollView
import com.platojobs.pkotlinapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 设备详情导出区的布局约束：说明独占整行、按钮另起一行，
 * 滚动容器底部预留按钮高度 + 间距，避免内容滚到底后仍落在按钮/手势条下方。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class DeviceDetailExportLayoutTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val density get() = app.resources.displayMetrics.density

    private fun inflateDetail(): View {
        val themed = ContextThemeWrapper(app, R.style.Theme_PKotlinApp)
        return LayoutInflater.from(themed).inflate(R.layout.activity_device_detail, null, false)
    }

    @Test
    fun `导出说明与按钮纵向排列且按钮右对齐`() {
        val row = inflateDetail().findViewById<LinearLayout>(R.id.readingsExportRow)

        assertEquals(LinearLayout.VERTICAL, row.orientation)
        assertEquals(2, row.childCount)

        val description = row.getChildAt(0)
        val button = row.getChildAt(1)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, description.layoutParams.width)
        assertEquals(Gravity.END, (button.layoutParams as LinearLayout.LayoutParams).gravity)
        assertEquals((8 * density).roundToInt(), (button.layoutParams as LinearLayout.LayoutParams).topMargin)
    }

    @Test
    fun `滚动容器底部预留按钮高度与间距`() {
        val scroll = inflateDetail().findViewById<NestedScrollView>(R.id.scrollContent)

        // XML 初始值 48dp = 按钮 40dp + 间距 8dp，运行时再叠加 systemBars.bottom。
        assertEquals((48 * density).roundToInt(), scroll.paddingBottom)
        assertFalse(scroll.clipToPadding)
    }
}
