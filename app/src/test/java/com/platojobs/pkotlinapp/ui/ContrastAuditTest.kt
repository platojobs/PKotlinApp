package com.platojobs.pkotlinapp.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import androidx.core.content.res.ResourcesCompat
import com.platojobs.pkotlinapp.R
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * WCAG 2.1 对比度审计：浅色与深色两套语义色都要达标。
 * 正文/次要/强调文字 ≥ 4.5:1；大字（≥18pt，或 ≥14pt 粗体）≥ 3:1。
 * 禁用态文字属于非内容状态，按 WCAG 豁免，不纳入断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContrastAuditTest {

    @Test
    fun `body and accent text meet WCAG AA in both themes`() {
        val failures = mutableListOf<String>()
        listOf(false to "light", true to "dark").forEach { (night, themeName) ->
            val resources = resourcesFor(night)
            PAIRS.forEach { pair ->
                val foreground = color(resources, pair.foreground)
                val background = color(resources, pair.background)
                val ratio = contrastRatio(foreground, background)
                val threshold = if (pair.largeText) 3.0 else 4.5
                println(
                    "[contrast] $themeName ${pair.foreground} on ${pair.background} = " +
                        "%.2f:1 (需 ≥ %.1f)".format(ratio, threshold),
                )
                if (ratio < threshold) {
                    failures += "$themeName ${pair.foreground} on ${pair.background}: " +
                        "%.2f:1 < %.1f".format(ratio, threshold)
                }
            }
        }
        assertTrue("WCAG 对比度不足：\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun resourcesFor(night: Boolean): Resources {
        val base = RuntimeEnvironment.getApplication().resources
        val configuration = Configuration(base.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        return RuntimeEnvironment.getApplication().createConfigurationContext(configuration).resources
    }

    private fun color(resources: Resources, name: Int): Int =
        ResourcesCompat.getColor(resources, name, null)

    /** WCAG 相对亮度与对比度。 */
    private fun contrastRatio(first: Int, second: Int): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) {
                normalized / 12.92
            } else {
                Math.pow((normalized + 0.055) / 1.055, 2.4)
            }
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private data class Pair(
        val foreground: Int,
        val background: Int,
        val largeText: Boolean = false,
    )

    private companion object {
        val PAIRS = listOf(
            // 正文 / 次要 / 三级文字
            Pair(R.color.text_primary, R.color.canvas),
            Pair(R.color.text_primary, R.color.surface),
            Pair(R.color.text_primary, R.color.surface_sunken),
            Pair(R.color.text_secondary, R.color.canvas),
            Pair(R.color.text_secondary, R.color.surface),
            Pair(R.color.text_secondary, R.color.surface_sunken),
            Pair(R.color.text_tertiary, R.color.surface),
            Pair(R.color.text_tertiary, R.color.surface_sunken),
            // 强调色 / 主按钮 / 危险按钮
            Pair(R.color.primary_text, R.color.surface),
            Pair(R.color.primary_text, R.color.chart_band),
            Pair(R.color.on_primary, R.color.primary),
            Pair(R.color.on_primary, R.color.danger_fill),
            // 状态标签
            Pair(R.color.success, R.color.success_bg),
            Pair(R.color.warning, R.color.warning_bg),
            Pair(R.color.danger, R.color.danger_bg),
            // 浮层与图表轴
            Pair(R.color.toast_text, R.color.toast_surface),
            Pair(R.color.chart_axis, R.color.canvas),
            // 大字（标题，≥18pt）
            Pair(R.color.text_primary, R.color.canvas, largeText = true),
            Pair(R.color.text_primary, R.color.surface, largeText = true),
        )
    }
}
