package com.platojobs.pkotlinapp.domain.scene

/**
 * 触发上下文：用于渲染服务端 notify 模板中的占位符。
 *
 * 占位符约定（写入 docs/releasenotes.md）：
 * - `{device}`：触发设备的本地别名；没有别名时回落到 MAC。
 * - `{metric}`：归一化后的指标键（temperature / humidity / ...）。
 * - `{value}`：触发值，保留 1 位小数且不带单位；没有数值时替换为空串。
 * 未知占位符原样保留，避免吞掉用户文本。
 */
data class SceneTriggerContext(
    val deviceMac: String? = null,
    val deviceName: String = "",
    val metric: String? = null,
    val value: Double? = null,
    val unit: String = "",
) {
    val deviceLabel: String
        get() = deviceName.takeIf { it.isNotBlank() } ?: deviceMac.orEmpty()
}

object ScenePlaceholders {

    fun render(template: String?, context: SceneTriggerContext): String {
        if (template.isNullOrEmpty()) return ""
        return template
            .replace("{device}", context.deviceLabel)
            .replace("{metric}", context.metric.orEmpty())
            .replace("{value}", context.value?.let(::formatValue).orEmpty())
    }

    /** 与指标阈值展示保持一致：整数不带小数点，其余保留 1 位。 */
    fun formatValue(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", value)
}
