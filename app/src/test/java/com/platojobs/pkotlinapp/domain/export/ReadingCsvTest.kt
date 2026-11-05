package com.platojobs.pkotlinapp.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingCsvTest {

    @Test
    fun `CSV 以 BOM 开头且表头固定`() {
        val csv = ReadingCsv.render(emptyList())
        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals("\uFEFFrecordedAt,metric,value,unit\n", csv)
    }

    @Test
    fun `时间是 ISO8601 UTC`() {
        assertEquals("1970-01-01T00:00:00Z", ReadingCsv.formatInstant(0L))
        assertEquals("2026-01-01T04:00:00Z", ReadingCsv.formatInstant(1767240000000L))
    }

    @Test
    fun `逗号与引号按 RFC4180 转义`() {
        assertEquals("\"a,b\"", ReadingCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ReadingCsv.escape("say \"hi\""))
        assertEquals("\"line\nbreak\"", ReadingCsv.escape("line\nbreak"))
        assertEquals("plain", ReadingCsv.escape("plain"))
    }

    @Test
    fun `整数值不输出多余小数`() {
        assertEquals("42", ReadingCsv.valueText(42.0))
        assertEquals("20.9", ReadingCsv.valueText(20.9))
        assertEquals("", ReadingCsv.valueText(Double.NaN))
    }

    @Test
    fun `行内容包含转义后的指标与单位`() {
        val csv = ReadingCsv.render(
            listOf(
                ReadingCsvRow(0L, "temperature", 20.9, "°C"),
                ReadingCsvRow(60_000L, "custom,metric", 1.5, "unit\"q\""),
            ),
        )
        val lines = csv.trimEnd('\n').split('\n')
        assertEquals("\uFEFFrecordedAt,metric,value,unit", lines[0])
        assertEquals("1970-01-01T00:00:00Z,temperature,20.9,°C", lines[1])
        assertEquals("1970-01-01T00:01:00Z,\"custom,metric\",1.5,\"unit\"\"q\"\"\"", lines[2])
    }

    @Test
    fun `文件名规则包含 MAC 指标区间与 UTC 时间`() {
        val name = ReadingCsv.fileName(
            mac = "AA:BB:CC:DD:EE:FF",
            metric = "temperature",
            rangeLabel = "24h",
            timestamp = 1767240000000L,
        )
        assertEquals("readings_AABBCCDDEEFF_temperature_24h_20260101_040000.csv", name)
    }

    @Test
    fun `文件名清洗非法字符`() {
        val name = ReadingCsv.fileName("aa:bb", "battery level", "1h", 0L)
        assertEquals("readings_AABB_battery_level_1h_19700101_000000.csv", name)
    }
}
