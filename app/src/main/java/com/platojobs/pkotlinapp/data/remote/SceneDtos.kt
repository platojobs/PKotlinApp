package com.platojobs.pkotlinapp.data.remote

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** GET /scenes：全量列表，无分页。 */
data class SceneListResponse(val items: List<SceneDto>? = null)

/**
 * 服务端场景（也是 change_log 中 `entity=scene` 的 payload）。
 * 时间字段为 RFC3339；客户端只在本地存毫秒时间戳。
 */
data class SceneDto(
    val id: String? = null,
    val name: String? = null,
    val enabled: Boolean? = null,
    val cooldownSeconds: Int? = null,
    val trigger: SceneTriggerDto? = null,
    val actions: List<SceneActionDto>? = null,
    val version: Long? = null,
    val deviceMissing: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
)

data class SceneDeviceRefDto(
    val platform: String? = null,
    val externalId: String? = null,
)

data class SceneMetricConditionDto(
    val metric: String? = null,
    val comparator: String? = null,
    val threshold: Double? = null,
    val consecutive: Int? = null,
)

data class SceneTriggerDto(
    val kind: String? = null,
    val device: SceneDeviceRefDto? = null,
    val offlineSeconds: Int? = null,
    val rssiThreshold: Int? = null,
    val deviceType: String? = null,
    val metric: SceneMetricConditionDto? = null,
    val timeOfDay: String? = null,
    val timezone: String? = null,
    val daysOfWeek: List<Int>? = null,
)

data class SceneActionDto(
    val kind: String? = null,
    val title: String? = null,
    val body: String? = null,
    val message: String? = null,
    val level: String? = null,
    val mark: String? = null,
)

data class SceneInputDto(
    val name: String,
    val enabled: Boolean,
    val cooldownSeconds: Int,
    val trigger: SceneTriggerDto,
    val actions: List<SceneActionDto>,
)

/** PUT /scenes/{id}：字段级 LWW，每个出现的字段都必须带 updatedAt。 */
data class ScenePatchDto(
    val name: SceneStringFieldDto? = null,
    val enabled: SceneBoolFieldDto? = null,
    val cooldownSeconds: SceneIntFieldDto? = null,
    val trigger: SceneTriggerFieldDto? = null,
    val actions: SceneActionsFieldDto? = null,
)

data class SceneStringFieldDto(val value: String, val updatedAt: String)

data class SceneBoolFieldDto(val value: Boolean, val updatedAt: String)

data class SceneIntFieldDto(val value: Int, val updatedAt: String)

data class SceneTriggerFieldDto(val value: SceneTriggerDto, val updatedAt: String)

data class SceneActionsFieldDto(val value: List<SceneActionDto>, val updatedAt: String)

/** 与 JVM/服务端一致的 RFC3339 毫秒时间；解析失败返回 null，由上层保留本地值。 */
object SceneTimestamps {

    private const val WITH_MILLIS = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
    private const val WITHOUT_MILLIS = "yyyy-MM-dd'T'HH:mm:ssXXX"
    private const val OUTPUT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    fun format(epochMillis: Long): String =
        formatter(OUTPUT).format(Date(epochMillis))

    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val normalized = normalizeFraction(raw)
        for (pattern in listOf(WITH_MILLIS, WITHOUT_MILLIS)) {
            try {
                return formatter(pattern).parse(normalized)?.time
            } catch (_: ParseException) {
                // 尝试下一种格式。
            }
        }
        return null
    }

    /**
     * Go 的 time.Time 会输出 RFC3339Nano（小数位 0–9 位且会去掉末尾 0），
     * 而 SimpleDateFormat 的 SSS 只接受固定 3 位小数。统一补齐/截断到毫秒再解析，
     * 否则服务端的纳秒时间会被判成非法，场景响应会被整条丢弃。
     * 服务端 create 返回 UTC（`Z`），list 返回服务器本地时区（如 `+08:00`），
     * 两种都要能解析，所以小数位截断后再交给带 `XXX` 的格式处理偏移。
     */
    private fun normalizeFraction(raw: String): String {
        val dot = raw.indexOf('.')
        if (dot < 0) return raw
        val plus = raw.indexOf('+', dot)
        val minus = raw.indexOf('-', dot)
        val end = when {
            plus >= 0 -> plus
            minus >= 0 -> minus
            else -> raw.indexOf('Z', dot)
        }
        if (end < 0) return raw
        val millis = (raw.substring(dot + 1, end) + "000").substring(0, 3)
        return raw.substring(0, dot) + "." + millis + raw.substring(end)
    }

    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
}
