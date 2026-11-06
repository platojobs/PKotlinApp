package com.platojobs.pkotlinapp.data.scene

import com.platojobs.pkotlinapp.data.local.RuleEntity
import com.platojobs.pkotlinapp.data.local.SceneActionEntity
import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.remote.SceneActionDto
import com.platojobs.pkotlinapp.data.remote.SceneDeviceRefDto
import com.platojobs.pkotlinapp.data.remote.SceneDto
import com.platojobs.pkotlinapp.data.remote.SceneMetricConditionDto
import com.platojobs.pkotlinapp.data.remote.SceneTriggerDto
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger
import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import java.util.Locale
import com.squareup.moshi.Types

/**
 * 服务端单行模型（trigger + actions JSONB）与本地执行投影之间的唯一转换点。
 *
 * 约定：
 * - 只有这里可以把服务端 DTO 转成 [SceneTrigger]/[SceneAction]，或反向编码；
 * - [encode] 返回 null 时表示**服务端无法表达**（例如连接设备、全局 RSSI 触发），
 *   调用方必须把场景显式标记为「仅本机」，不能丢弃；
 * - trigger/actions 的 JSON 原样保存，PUT 时直接回传，避免本地投影丢字段。
 */
object SceneCanonical {

    private val moshi = ApiClient.moshi()
    private val triggerAdapter = moshi.adapter(SceneTriggerDto::class.java)
    private val actionsAdapter = moshi.adapter<List<SceneActionDto>>(
        Types.newParameterizedType(List::class.java, SceneActionDto::class.java),
    )

    data class Encoded(val triggerJson: String, val actionsJson: String)

    /** 服务端动作种类；用于「动作种类不重复」校验。 */
    fun serverActionKinds(actions: List<SceneAction>): List<String>? {
        val kinds = actions.map { it.serverKind ?: return null }
        return kinds.takeIf { it.size in 1..4 && it.distinct().size == it.size }
    }

    /**
     * 把本地模型编码成服务端可接受的 JSON。
     * [deviceTypeOf] 用于补齐 metric_threshold 必需的型号键（本机历史场景通常没有存）。
     */
    fun encode(
        trigger: SceneTrigger,
        actions: List<SceneAction>,
        deviceTypeOf: (String) -> String? = { null },
    ): Encoded? {
        val actionDtos = actions.mapNotNull { it.toDto() }
        if (actionDtos.size != actions.size) return null
        val kinds = actionDtos.map { it.kind }
        if (kinds.size !in 1..4 || kinds.distinct().size != kinds.size) return null
        val triggerDto = trigger.toDto(deviceTypeOf) ?: return null
        return Encoded(
            triggerJson = triggerAdapter.toJson(triggerDto),
            actionsJson = actionsAdapter.toJson(actionDtos),
        )
    }

    fun triggerJson(dto: SceneTriggerDto): String? =
        runCatching { triggerAdapter.toJson(dto) }.getOrNull()

    fun actionsJson(dtos: List<SceneActionDto>): String? =
        runCatching { actionsAdapter.toJson(dtos) }.getOrNull()

    fun triggerDto(json: String?): SceneTriggerDto? =
        json?.let { runCatching { triggerAdapter.fromJson(it) }.getOrNull() }

    fun actionDtos(json: String?): List<SceneActionDto>? =
        json?.let { runCatching { actionsAdapter.fromJson(it) }.getOrNull() }

    /** 服务端 trigger -> 领域模型；未知 kind 返回 null（该场景保留但不触发）。 */
    fun triggerFrom(dto: SceneTriggerDto?): SceneTrigger? = run { when (dto?.kind) {
        "device_offline" -> SceneTrigger.DeviceOffline(
            deviceMac = normalizeMac(dto.device?.externalId) ?: return null,
            offlineSeconds = (dto.offlineSeconds ?: 300).coerceIn(60, 86400),
        )

        "rssi_below" -> SceneTrigger.LowRssi(
            threshold = (dto.rssiThreshold ?: return null).coerceIn(-100, 0),
            deviceMac = normalizeMac(dto.device?.externalId) ?: return null,
        )

        "metric_threshold" -> {
            val metric = dto.metric ?: return null
            SceneTrigger.MetricThreshold(
                metric = metric.metric.orEmpty(),
                comparator = parseComparator(metric.comparator),
                thresholdValue = metric.threshold ?: return null,
                consecutive = MetricThreshold.sanitizeConsecutive(metric.consecutive ?: 1),
                deviceMac = normalizeMac(dto.device?.externalId) ?: return null,
                deviceType = dto.deviceType,
            )
        }

        "schedule" -> {
            val timeOfDay = dto.timeOfDay ?: return null
            val timezone = dto.timezone ?: "UTC"
            val days = dto.daysOfWeek.orEmpty().filter { it in 1..7 }.toSet()
            SceneTrigger.Schedule(
                intervalMinutes = 30,
                timeOfDay = timeOfDay,
                timezone = timezone,
                daysOfWeek = days,
            )
        }

        else -> null
    } }

    /** 服务端 actions -> 领域模型；mark_device 绑定触发设备。 */
    fun actionsFrom(
        dtos: List<SceneActionDto>?,
        trigger: SceneTrigger?,
    ): List<SceneAction> {
        val deviceMac = trigger?.deviceMac()
        return dtos.orEmpty().mapNotNull { dto ->
            when (dto.kind) {
                "notify" -> SceneAction(
                    type = SceneActionType.NOTIFY,
                    title = dto.title,
                    body = dto.body,
                )

                "log" -> SceneAction(
                    type = SceneActionType.RECORD_LOG,
                    message = dto.message,
                    level = dto.level ?: "info",
                )

                "mark_device" -> SceneAction(
                    type = SceneActionType.MARK_DEVICE,
                    deviceMac = deviceMac,
                    mark = dto.mark,
                )

                else -> null
            }
        }
    }

    /** rules 投影 -> 领域触发条件（旧本机场景首次上传时使用）。 */
    fun triggerFromRule(rule: RuleEntity): SceneTrigger? = when (
        runCatching { RuleType.valueOf(rule.type) }.getOrNull()
    ) {
        RuleType.METRIC_THRESHOLD -> SceneTrigger.MetricThreshold(
            metric = rule.metric.orEmpty(),
            comparator = runCatching { RuleComparator.valueOf(rule.comparator) }
                .getOrDefault(RuleComparator.GREATER),
            thresholdValue = rule.thresholdValue,
            consecutive = MetricThreshold.sanitizeConsecutive(rule.consecutive),
            deviceMac = rule.deviceMac,
        )

        RuleType.LOW_RSSI -> SceneTrigger.LowRssi(rule.threshold, rule.deviceMac)
        RuleType.DEVICE_OFFLINE -> rule.deviceMac?.let {
            SceneTrigger.DeviceOffline(it, rule.offlineSeconds)
        }

        RuleType.SCHEDULE -> SceneTrigger.Schedule(
            intervalMinutes = rule.intervalMinutes ?: 30,
            timeOfDay = rule.timeOfDay,
            timezone = rule.timezone ?: "UTC",
            daysOfWeek = rule.daysOfWeek.orEmpty()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }
                .toSet(),
        )

        RuleType.DISCONNECTED -> SceneTrigger.Disconnected
        RuleType.BOND_LOST, null -> null
    }

    fun actionsFromEntities(entities: List<SceneActionEntity>): List<SceneAction> =
        entities.sortedBy { it.position }.mapNotNull { entity ->
            val type = runCatching { SceneActionType.valueOf(entity.type) }.getOrNull()
                ?: return@mapNotNull null
            SceneAction(
                type = type,
                deviceMac = entity.deviceMac,
                muteMinutes = entity.muteMinutes,
                title = entity.title,
                body = entity.body,
                message = entity.message,
                level = entity.level,
                mark = entity.mark,
            )
        }

    /** 服务端 trigger -> rules 投影（供 RuleEngine 执行）。 */
    fun ruleEntity(trigger: SceneTrigger, enabled: Boolean): RuleEntity? = when (trigger) {
        SceneTrigger.Manual -> null
        SceneTrigger.Disconnected -> RuleEntity(
            type = RuleType.DISCONNECTED.name,
            threshold = 0,
            enabled = enabled,
            createdAt = 0,
            lastTriggeredTimestamp = 0,
        )

        is SceneTrigger.DeviceOffline -> RuleEntity(
            type = RuleType.DEVICE_OFFLINE.name,
            threshold = 0,
            enabled = enabled,
            createdAt = 0,
            lastTriggeredTimestamp = 0,
            deviceMac = trigger.deviceMac,
            offlineSeconds = trigger.offlineSeconds,
        )

        is SceneTrigger.LowRssi -> RuleEntity(
            type = RuleType.LOW_RSSI.name,
            threshold = trigger.threshold,
            enabled = enabled,
            createdAt = 0,
            lastTriggeredTimestamp = 0,
            comparator = RuleComparator.LESS.name,
            thresholdValue = trigger.threshold.toDouble(),
            deviceMac = trigger.deviceMac,
        )

        is SceneTrigger.MetricThreshold -> RuleEntity(
            type = RuleType.METRIC_THRESHOLD.name,
            threshold = 0,
            enabled = enabled,
            createdAt = 0,
            lastTriggeredTimestamp = 0,
            metric = trigger.metric,
            comparator = trigger.comparator.name,
            thresholdValue = trigger.thresholdValue,
            consecutive = MetricThreshold.sanitizeConsecutive(trigger.consecutive),
            deviceMac = trigger.deviceMac,
        )

        is SceneTrigger.Schedule -> RuleEntity(
            type = RuleType.SCHEDULE.name,
            threshold = 0,
            enabled = enabled,
            createdAt = 0,
            lastTriggeredTimestamp = 0,
            intervalMinutes = if (trigger.timeOfDay == null) trigger.intervalMinutes else null,
            timeOfDay = trigger.timeOfDay,
            timezone = trigger.timezone,
            daysOfWeek = trigger.daysOfWeek.takeIf { it.isNotEmpty() }
                ?.sorted()
                ?.joinToString(","),
        )
    }

    fun actionEntities(actions: List<SceneAction>): List<SceneActionEntity> =
        actions.mapIndexed { index, action ->
            SceneActionEntity(
                sceneId = 0,
                position = index,
                type = action.type.name,
                deviceMac = action.deviceMac,
                muteMinutes = action.muteMinutes,
                title = action.title,
                body = action.body,
                message = action.message,
                level = action.level,
                mark = action.mark,
            )
        }

    /** 服务端场景的本地冷启动时间：createdAt/updatedAt 解析失败时用 0。 */
    fun epochOf(dto: SceneDto, now: Long): Long =
        com.platojobs.pkotlinapp.data.remote.SceneTimestamps.parse(dto.updatedAt)
            ?: parseCreated(dto.createdAt)
            ?: now

    private fun parseCreated(raw: String?): Long? =
        com.platojobs.pkotlinapp.data.remote.SceneTimestamps.parse(raw)

    fun normalizeMac(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.US)

    private fun parseComparator(raw: String?): RuleComparator = when (raw) {
        "lt" -> RuleComparator.LESS
        "lte" -> RuleComparator.LESS_OR_EQUAL
        "gte" -> RuleComparator.GREATER_OR_EQUAL
        else -> RuleComparator.GREATER
    }

    private fun comparatorCode(comparator: RuleComparator): String = when (comparator) {
        RuleComparator.LESS -> "lt"
        RuleComparator.LESS_OR_EQUAL -> "lte"
        RuleComparator.GREATER -> "gt"
        RuleComparator.GREATER_OR_EQUAL -> "gte"
    }

    private fun SceneAction.toDto(): SceneActionDto? = run { when (type) {
        SceneActionType.NOTIFY -> {
            val title = title?.takeIf { it.isNotBlank() } ?: return null
            val body = body?.takeIf { it.isNotBlank() } ?: return null
            if (title.length > 80 || body.length > 500) return null
            SceneActionDto(kind = "notify", title = title, body = body)
        }

        SceneActionType.RECORD_LOG -> {
            val text = message?.takeIf { it.isNotBlank() } ?: return null
            if (text.length > 500) return null
            SceneActionDto(
                kind = "log",
                message = text,
                level = level?.takeIf { it.equals("warn", true) } ?: "info",
            )
        }

        SceneActionType.MARK_DEVICE -> {
            val text = mark?.takeIf { it.isNotBlank() } ?: return null
            if (text.length > 32) return null
            SceneActionDto(kind = "mark_device", mark = text)
        }

        else -> null
    } }

    private fun SceneTrigger.toDto(deviceTypeOf: (String) -> String?): SceneTriggerDto? = run { when (this) {
        SceneTrigger.Manual,
        SceneTrigger.Disconnected,
        -> null

        is SceneTrigger.DeviceOffline -> SceneTriggerDto(
            kind = "device_offline",
            device = SceneDeviceRefDto(platform = "android", externalId = normalizeMac(deviceMac)),
            offlineSeconds = offlineSeconds.coerceIn(60, 86400),
        )

        is SceneTrigger.LowRssi -> {
            val mac = normalizeMac(deviceMac) ?: return null
            SceneTriggerDto(
                kind = "rssi_below",
                device = SceneDeviceRefDto(platform = "android", externalId = mac),
                rssiThreshold = threshold.coerceIn(-100, 0),
            )
        }

        is SceneTrigger.MetricThreshold -> {
            val mac = normalizeMac(deviceMac) ?: return null
            val type = deviceType ?: deviceTypeOf(mac) ?: return null
            if (metric.isBlank()) return null
            SceneTriggerDto(
                kind = "metric_threshold",
                device = SceneDeviceRefDto(platform = "android", externalId = mac),
                deviceType = type,
                metric = SceneMetricConditionDto(
                    metric = metric,
                    comparator = comparatorCode(comparator),
                    threshold = thresholdValue,
                    consecutive = MetricThreshold.sanitizeConsecutive(consecutive),
                ),
            )
        }

        is SceneTrigger.Schedule -> {
            val time = timeOfDay ?: return null
            SceneTriggerDto(
                kind = "schedule",
                timeOfDay = time,
                timezone = timezone,
                daysOfWeek = daysOfWeek.sorted().takeIf { it.isNotEmpty() },
            )
        }
    } }

    private fun SceneTrigger.deviceMac(): String? = when (this) {
        is SceneTrigger.DeviceOffline -> deviceMac
        is SceneTrigger.LowRssi -> deviceMac
        is SceneTrigger.MetricThreshold -> deviceMac
        else -> null
    }
}
