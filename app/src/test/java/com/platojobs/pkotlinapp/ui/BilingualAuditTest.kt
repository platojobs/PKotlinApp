package com.platojobs.pkotlinapp.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 双语与无障碍静态审计：
 * 1. 中英文资源 key 集合一致、占位符一致、无空值；
 * 2. UI 层 Kotlin 源码不出现中文字面量（注释与日志除外）；
 * 3. 关键文字配色对比度 ≥ 4.5:1；
 * 4. 图标有 contentDescription，规则开关等触控区 ≥ 48dp。
 */
class BilingualAuditTest {

    private val moduleDir: File = run {
        val candidates = generateSequence(File("").absoluteFile) { it.parentFile }.toList()
        val relative = "src/main/java/com/platojobs/pkotlinapp"
        candidates.firstOrNull { File(it, relative).isDirectory }
            ?: candidates.firstOrNull { File(it, "app/$relative").isDirectory }
                ?.let { File(it, "app") }
            ?: File(".").absoluteFile
    }
    private val resDir = File(moduleDir, "src/main/res")
    private val uiDir = File(moduleDir, "src/main/java/com/platojobs/pkotlinapp/ui")

    private data class Entry(val text: String, val placeholders: List<String>)

    private fun readStrings(file: File, skipUntranslatable: Boolean): Pair<Map<String, Entry>, Map<String, Map<String, Entry>>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<String, Entry>()
        val plurals = mutableMapOf<String, Map<String, Entry>>()
        val nodes = document.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            when (element.tagName) {
                "string" -> {
                    if (skipUntranslatable && element.getAttribute("translatable") == "false") continue
                    strings[element.getAttribute("name")] = Entry(element.textContent, placeholders(element.textContent))
                }
                "plurals" -> {
                    val quantities = mutableMapOf<String, Entry>()
                    val children = element.getElementsByTagName("item")
                    for (childIndex in 0 until children.length) {
                        val item = children.item(childIndex) as? Element ?: continue
                        quantities[item.getAttribute("quantity")] = Entry(item.textContent, placeholders(item.textContent))
                    }
                    plurals[element.getAttribute("name")] = quantities
                }
            }
        }
        return strings to plurals
    }

    private fun placeholders(value: String): List<String> {
        val positional = Regex("""%(\d+)\$([sdf])""")
        val everyPercent = Regex("%")
        assertEquals(
            "存在未使用命名参数的占位符：$value",
            everyPercent.findAll(value).count(),
            positional.findAll(value).count(),
        )
        return positional.findAll(value)
            .map { match -> "${match.groupValues[1]}:${match.groupValues[2]}" }
            .toList()
    }

    @Test
    fun `中英文资源 key 与占位符一致且无空值`() {
        val (zhStrings, zhPlurals) = readStrings(File(resDir, "values/strings.xml"), skipUntranslatable = true)
        val (enStrings, enPlurals) = readStrings(File(resDir, "values-en/strings.xml"), skipUntranslatable = false)

        assertEquals("string key 集合不一致", zhStrings.keys, enStrings.keys)
        assertEquals("plurals key 集合不一致", zhPlurals.keys, enPlurals.keys)

        (zhStrings.keys + enStrings.keys).forEach { key ->
            val zh = zhStrings[key]
            val en = enStrings[key]
            assertTrue("缺少 $key", zh != null && en != null)
            assertEquals("「$key」占位符不一致", zh!!.placeholders, en!!.placeholders)
            assertTrue("「$key」中文为空", zh.text.isNotBlank())
            assertTrue("「$key」英文为空", en.text.isNotBlank())
        }
        (zhPlurals.keys + enPlurals.keys).forEach { key ->
            val zh = zhPlurals.getValue(key)
            val en = enPlurals.getValue(key)
            assertEquals("「$key」复数数量分支不一致", zh.keys, en.keys)
            zh.keys.forEach { quantity ->
                val zhEntry = zh.getValue(quantity)
                val enEntry = en.getValue(quantity)
                assertEquals("「$key:$quantity」占位符不一致", zhEntry.placeholders, enEntry.placeholders)
                assertTrue("「$key:$quantity」中文为空", zhEntry.text.isNotBlank())
                assertTrue("「$key:$quantity」英文为空", enEntry.text.isNotBlank())
            }
        }

        val enFile = File(resDir, "values-en/strings.xml").readText()
        assertFalse("英文资源不得包含 translatable=\"false\"", enFile.contains("translatable=\"false\""))

        val zhValues = zhStrings.values.map { it.text } + zhPlurals.values.flatMap { it.values.map(Entry::text) }
        val enValues = enStrings.values.map { it.text } + enPlurals.values.flatMap { it.values.map(Entry::text) }
        assertTrue("中文资源缺少条目", zhValues.isNotEmpty())
        assertTrue("英文资源缺少条目", enValues.isNotEmpty())
    }

    @Test
    fun `英文资源使用 Transfer Code 术语`() {
        val file = File(resDir, "values-en/strings.xml")
        val text = file.readText().lowercase()
        assertFalse("英文资源不得出现 pairing code", text.contains("pairing code"))
        assertTrue("英文资源缺少 Transfer Code", file.readText().contains("Transfer Code"))
    }

    @Test
    fun `UI 层 Kotlin 源码不含中文字面量`() {
        val cjk = Regex("[\u4e00-\u9fff]")
        val violations = mutableListOf<String>()
        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            var inBlockComment = false
            file.readLines().forEachIndexed { index, line ->
                var code = line
                while (true) {
                    if (inBlockComment) {
                        val end = code.indexOf("*/")
                        if (end < 0) {
                            code = ""
                            break
                        }
                        code = code.substring(end + 2)
                        inBlockComment = false
                    } else {
                        val start = code.indexOf("/*")
                        if (start < 0) break
                        val end = code.indexOf("*/", start + 2)
                        if (end < 0) {
                            code = code.substring(0, start)
                            inBlockComment = true
                            break
                        }
                        code = code.removeRange(start, end + 2)
                    }
                }
                val withoutLineComment = code.substringBefore("//")
                if (cjk.containsMatchIn(withoutLineComment)) {
                    violations += "${file.relativeTo(moduleDir)}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue("UI 层出现中文字面量：\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `布局文字统一走 string 资源`() {
        val cjk = Regex("[\u4e00-\u9fff]")
        val literalText = Regex("""android:(text|hint|contentDescription)="([^"]+)"""")
        val violations = File(resDir, "layout").listFiles { file -> file.extension == "xml" }.orEmpty()
            .mapNotNull { file ->
                val hit = file.readLines().firstOrNull { line ->
                    @Suppress("UNCHECKED_CAST")
                    literalText.find(line)?.groupValues?.get(2)?.let { value ->
                        cjk.containsMatchIn(value) && !value.startsWith("@string/")
                    } ?: false
                }
                hit?.let { "${file.name}: $it" }
            }
        assertTrue("布局直接写了中文：\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `关键文字配色对比度不低于 4_5`() {
        val pairs = listOf(
            "text_primary" to "canvas",
            "text_primary" to "surface",
            "text_secondary" to "surface",
            "text_tertiary" to "surface",
            "text_tertiary" to "surface_sunken",
            "primary_text" to "chart_band",
            "on_primary" to "primary",
            "on_primary" to "danger_fill",
            "success" to "success_bg",
            "warning" to "warning_bg",
            "danger" to "danger_bg",
            "toast_text" to "toast_surface",
            "chart_axis" to "canvas",
        )
        listOf("values/colors.xml", "values-night/colors.xml").forEach { path ->
            val colors = readColors(File(resDir, path))
            pairs.forEach { (foreground, background) ->
                val ratio = contrast(colors.getValue(foreground), colors.getValue(background))
                assertTrue(
                    "$path 「$foreground on $background」对比度 ${"%.2f".format(ratio)} < 4.5",
                    ratio >= 4.5,
                )
            }
        }
    }

    private fun readColors(file: File): Map<String, Int> {
        val regex = Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{6})</color>""")
        return regex.findAll(file.readText()).associate { match ->
            match.groupValues[1] to match.groupValues[2].toInt(16)
        }
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        val red = channel((color shr 16) and 0xFF)
        val green = channel((color shr 8) and 0xFF)
        val blue = channel(color and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    @Test
    fun `关键操作触控区不小于 48dp`() {
        val dimens = File(resDir, "values/dimens.xml").readText()
        val buttonHeight = Regex("""<dimen name="button_h">(\d+)dp</dimen>""").find(dimens)!!.groupValues[1].toInt()
        val inputHeight = Regex("""<dimen name="input_h">(\d+)dp</dimen>""").find(dimens)!!.groupValues[1].toInt()
        assertTrue("button_h 小于 48dp", buttonHeight >= 48)
        assertTrue("input_h 小于 48dp", inputHeight >= 48)

        val rule = File(resDir, "layout/item_rule.xml").readText()
        assertTrue("规则开关触控区不足 48dp", Regex("""android:id="@\+id/ruleSwitch"[\s\S]*?android:minHeight="48dp"""").containsMatchIn(rule))
        assertTrue("删除按钮触控区不足 48dp", Regex("""android:id="@\+id/deleteRuleButton"[\s\S]*?android:layout_width="48dp"""").containsMatchIn(rule))
    }

    @Test
    fun `图标都有 contentDescription 且市场筛选支持换行`() {
        val icon = Regex("""<(?:ImageView|ImageButton)[\s\S]*?/>""")
        val violations = File(resDir, "layout").listFiles { file -> file.extension == "xml" }.orEmpty()
            .flatMap { file ->
                icon.findAll(file.readText()).mapNotNull { match ->
                    val block = match.value
                    if ("android:contentDescription=" in block) null else file.name
                }.toList()
            }
        assertTrue("图标缺少 contentDescription：${violations.joinToString()}", violations.isEmpty())

        val market = File(resDir, "layout/fragment_market.xml").readText()
        assertFalse("市场筛选不应放在 HorizontalScrollView 中（无法换行）", market.contains("HorizontalScrollView"))
        assertTrue("市场筛选 ChipGroup 应允许换行", market.contains("""android:id="@+id/vendorFilters""""))
        assertTrue("市场筛选 ChipGroup 应关闭单行", Regex("""android:id="@\+id/vendorFilters"[\s\S]*?singleLine="false"""").containsMatchIn(market))
    }
}
