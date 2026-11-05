package com.platojobs.pkotlinapp.domain.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 导出用的单条读数。 */
data class ReadingCsvRow(
    val recordedAt: Long,
    val metric: String,
    val value: Double,
    val unit: String,
)

/**
 * CSV 生成（纯 Kotlin）：
 * - UTF-8 BOM 开头，Excel 直接识别中文；
 * - 表头固定 `recordedAt,metric,value,unit`；
 * - 时间统一 ISO8601 UTC（如 `2026-01-01T04:00:00Z`）；
 * - 逗号 / 引号 / 换行按 RFC 4180 转义。
 */
object ReadingCsv {

    const val HEADER = "recordedAt,metric,value,unit"
    const val BOM = "\uFEFF"

    fun render(rows: List<ReadingCsvRow>): String {
        val builder = StringBuilder(BOM)
        builder.append(HEADER).append('\n')
        rows.forEach { row ->
            builder.append(formatInstant(row.recordedAt)).append(',')
                .append(escape(row.metric)).append(',')
                .append(escape(valueText(row.value))).append(',')
                .append(escape(row.unit)).append('\n')
        }
        return builder.toString()
    }

    fun escape(field: String): String {
        if (field.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return field
        return '"' + field.replace("\"", "\"\"") + '"'
    }

    /** 整数值不输出 `.0`，其余保留原始 double 表示。 */
    fun valueText(value: Double): String {
        if (!value.isFinite()) return ""
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    fun formatInstant(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }

    /**
     * 文件名规则：`readings_<MAC>_<metric>_<range>_<UTC时间>.csv`。
     * MAC 去分隔符并大写；metric 只保留小写字母数字，其余换成下划线。
     */
    fun fileName(mac: String, metric: String, rangeLabel: String, timestamp: Long): String {
        val safeMac = mac.filter { it.isLetterOrDigit() }.uppercase().ifBlank { "device" }
        val safeMetric = metric.lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .trim('_')
            .ifBlank { "metric" }
        val safeRange = rangeLabel.filter { it.isLetterOrDigit() }.ifBlank { "range" }
        val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val stamp = format.format(Date(timestamp))
        return "readings_${safeMac}_${safeMetric}_${safeRange}_$stamp.csv"
    }
}
