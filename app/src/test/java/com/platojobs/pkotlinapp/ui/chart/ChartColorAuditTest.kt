package com.platojobs.pkotlinapp.ui.chart

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 深色模式约束：图表源码不能出现硬编码颜色，所有颜色必须收口到
 * `values/colors.xml` + `values-night/colors.xml`。
 */
class ChartColorAuditTest {

    private val moduleDir: File = run {
        val candidates = generateSequence(File("").absoluteFile) { it.parentFile }.toList()
        val relative = "src/main/java/com/platojobs/pkotlinapp/ui/chart"
        candidates.firstOrNull { File(it, relative).isDirectory }
            ?: candidates.firstOrNull { File(it, "app/$relative").isDirectory }
                ?.let { File(it, "app") }
            ?: File(".").absoluteFile
    }
    private val chartDir = File(moduleDir, "src/main/java/com/platojobs/pkotlinapp/ui/chart")

    @Test
    fun `图表源码不得出现硬编码颜色字面量`() {
        val sources = chartDir.listFiles { file -> file.extension == "kt" }.orEmpty()
        assertTrue("图表源码目录不存在或为空: $chartDir", sources.isNotEmpty())
        val forbidden = Regex("0x[0-9a-fA-F]{6,8}|Color\\.(parseColor|rgb|argb)|#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
        sources.forEach { file ->
            val hit = file.readLines().withIndex().firstOrNull { (_, line) ->
                forbidden.containsMatchIn(line)
            }
            assertTrue(
                "${file.name}:${hit?.index?.plus(1) ?: 0} 出现硬编码颜色，请改用 R.color 主题属性",
                hit == null,
            )
        }
    }

    @Test
    fun `图表颜色在日间与夜间资源中都有定义`() {
        val names = listOf(
            "chart_line",
            "chart_point",
            "chart_grid",
            "chart_axis",
            "chart_band",
            "text_secondary",
        )
        val day = File(moduleDir, "src/main/res/values/colors.xml").readText()
        val night = File(moduleDir, "src/main/res/values-night/colors.xml").readText()
        names.forEach { name ->
            assertTrue("values/colors.xml 缺少 $name", day.contains("name=\"$name\""))
            assertTrue("values-night/colors.xml 缺少 $name", night.contains("name=\"$name\""))
        }
    }

    @Test
    fun `布局不得硬编码文字或背景颜色`() {
        val layouts = File(moduleDir, "src/main/res/layout").listFiles { file ->
            file.extension == "xml"
        }.orEmpty()
        val forbidden = Regex("(textColor|background|cardBackgroundColor|chipBackgroundColor)=\"#")
        layouts.forEach { file ->
            assertTrue(
                "${file.name} 出现硬编码颜色，请改用主题属性或 @color 资源",
                !forbidden.containsMatchIn(file.readText()),
            )
        }
    }
}
