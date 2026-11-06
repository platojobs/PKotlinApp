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

    private const val WITH_MILLIS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    private const val WITHOUT_MILLIS = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    fun format(epochMillis: Long): String =
        formatter(WITH_MILLIS).format(Date(epochMillis))

    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        for (pattern in listOf(WITH_MILLIS, WITHOUT_MILLIS)) {
            try {
                return formatter(pattern).parse(raw)?.time
            } catch (_: ParseException) {
                // 尝试下一种格式。
            }
        }
        return null
    }

    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
}
