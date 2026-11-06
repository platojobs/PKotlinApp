package com.platojobs.pkotlinapp.domain.scene

import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType

/** 场景动作：只允许真实可执行的集合，拒绝「播放音乐」这类无法落地的假动作。 */
enum class SceneActionType {
    CONNECT_DEVICE,
    DISCONNECT_DEVICE,
    START_COLLECTION,
    STOP_COLLECTION,
    MUTE_ALERTS,
    SYNC_NOW,

    /** 本地通知：由系统通知栏真实投递，失败会记录原因。 */
    NOTIFY,

    /** 记录日志：在执行记录里留下本次触发与动作结果。 */
    RECORD_LOG,

    /** 标记设备：把目标设备写入 markedAt，设备卡片展示标记。 */
    MARK_DEVICE,
}

val SCENE_MUTE_MINUTES = listOf(10, 30, 60)

/** 定时触发可选间隔（分钟）。 */
val SCENE_SCHEDULE_INTERVALS = listOf(15, 30, 60, 120)

/**
 * 场景触发条件：复用 rules 表的字段，最终由 RuleEngine 判定，场景只消费触发结果。
 * [Manual] 表示仅手动运行（历史场景没有触发规则）。
 *
 * 服务端条件与本地判定的映射：
 * - device_offline -> [DeviceOffline]（按 lastSeen 与 offlineSeconds 的定时离线检查）
 * - rssi_below -> [LowRssi]（可绑定 deviceMac）
 * - metric_threshold -> [MetricThreshold]（可绑定 deviceMac，连续条数 1–30）
 * - schedule -> [Schedule]（timeOfDay + timezone + daysOfWeek 的本地调度）
 */
sealed interface SceneTrigger {
    val ruleType: RuleType

    data object Manual : SceneTrigger {
        override val ruleType: RuleType = RuleType.DISCONNECTED
    }

    /** 旧的本机事件触发：任一台设备断开时执行，不绑定具体设备。 */
    data object Disconnected : SceneTrigger {
        override val ruleType: RuleType = RuleType.DISCONNECTED
    }

    /** 服务端 device_offline：指定设备超过 [offlineSeconds] 未出现即触发。 */
    data class DeviceOffline(
        val deviceMac: String,
        val offlineSeconds: Int = 300,
    ) : SceneTrigger {
        override val ruleType: RuleType = RuleType.DEVICE_OFFLINE
    }

    data class LowRssi(
        val threshold: Int = -85,
        val deviceMac: String? = null,
    ) : SceneTrigger {
        override val ruleType: RuleType = RuleType.LOW_RSSI
    }

    data class MetricThreshold(
        val metric: String = "",
        val comparator: RuleComparator = RuleComparator.GREATER,
        val thresholdValue: Double = 0.0,
        val consecutive: Int = 1,
        val deviceMac: String? = null,
        /** 服务端目录校验用的型号键；本机判定不需要，但 PUT 时需要原样回传。 */
        val deviceType: String? = null,
    ) : SceneTrigger {
        override val ruleType: RuleType = RuleType.METRIC_THRESHOLD
    }

    /**
     * 定时：本地旧场景用 [intervalMinutes]；服务端场景用 [timeOfDay]+[timezone]+[daysOfWeek]。
     * 两者互斥，[timeOfDay] 非空时按本地时刻调度。
     */
    data class Schedule(
        val intervalMinutes: Int = 30,
        val timeOfDay: String? = null,
        val timezone: String = "UTC",
        /** ISO 星期，1=周一；空集表示每天。 */
        val daysOfWeek: Set<Int> = emptySet(),
    ) : SceneTrigger {
        override val ruleType: RuleType = RuleType.SCHEDULE
    }

    /** 保存前的最终校验；返回 null 表示可落库。 */
    fun invalidReason(): String? = when (this) {
        SceneTrigger.Manual,
        SceneTrigger.Disconnected,
        -> null

        is SceneTrigger.DeviceOffline -> when {
            deviceMac.isBlank() -> "请选择要监控的设备"
            offlineSeconds !in 60..86400 -> "离线秒数需在 60 到 86400 之间"
            else -> null
        }

        is SceneTrigger.LowRssi ->
            if (threshold in -100..0) null else "RSSI 阈值需在 -100 到 0 之间"

        is SceneTrigger.MetricThreshold -> when {
            metric.isBlank() -> "请选择要监控的指标"
            !thresholdValue.isFinite() -> "阈值必须是有效数字"
            consecutive !in 1..30 -> "连续读数需在 1 到 30 条之间"
            else -> null
        }

        is SceneTrigger.Schedule -> if (timeOfDay != null) {
            if (SceneSchedule.isValid(timeOfDay, timezone, daysOfWeek)) {
                null
            } else {
                "定时条件需为 HH:MM、有效 IANA 时区与 1–7 的星期"
            }
        } else {
            if (intervalMinutes in SCENE_SCHEDULE_INTERVALS) null else "定时间隔只支持 15/30/60/120 分钟"
        }
    }
}

/** 单个动作；设备类动作必须带 [deviceMac]，静音必须带合法的 [muteMinutes]。 */
data class SceneAction(
    val type: SceneActionType,
    val deviceMac: String? = null,
    val muteMinutes: Int? = null,
    /** 服务端 notify 模板。 */
    val title: String? = null,
    val body: String? = null,
    /** 服务端 log 模板与级别（info / warn）。 */
    val message: String? = null,
    val level: String? = null,
    /** 服务端 mark_device 的标记名。 */
    val mark: String? = null,
) {
    /** 存储/展示前的最终校验；不合法时返回原因，避免把假动作写进 Room。 */
    fun invalidReason(): String? = when (type) {
        SceneActionType.CONNECT_DEVICE,
        SceneActionType.DISCONNECT_DEVICE,
        SceneActionType.MARK_DEVICE,
        -> if (deviceMac.isNullOrBlank()) "请选择要操作的设备" else null

        SceneActionType.MUTE_ALERTS ->
            if (muteMinutes in SCENE_MUTE_MINUTES) null else "静音时长只支持 10/30/60 分钟"

        SceneActionType.START_COLLECTION,
        SceneActionType.STOP_COLLECTION,
        SceneActionType.SYNC_NOW,
        SceneActionType.NOTIFY,
        SceneActionType.RECORD_LOG,
        -> null
    }

    /** 服务端可表达的三种动作；其余动作只能留在本机。 */
    val serverKind: String?
        get() = when (type) {
            SceneActionType.NOTIFY -> "notify"
            SceneActionType.RECORD_LOG -> "log"
            SceneActionType.MARK_DEVICE -> "mark_device"
            else -> null
        }
}

/** 服务端同步状态；`LOCAL_ONLY` 表示服务端模型无法表达但已被明确保留。 */
enum class SceneSyncState {
    PENDING,
    SYNCED,
    LOCAL_ONLY,
    ;

    /**
     * Room 列值统一小写：迁移默认值与 DAO 里的 `'pending'` / `'synced'` 字面量
     * 必须完全一致，否则 SQLite 大小写敏感比较会让新场景进不了上传队列。
     */
    val storage: String get() = name.lowercase()

    companion object {
        fun parse(raw: String?): SceneSyncState =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

data class Scene(
    val id: Long,
    val name: String,
    val actions: List<SceneAction>,
    val createdAt: Long = 0L,
    val enabled: Boolean = true,
    /** 触发条件；null 表示仅手动运行。 */
    val trigger: SceneTrigger? = null,
    val remoteId: String? = null,
    val syncState: SceneSyncState = SceneSyncState.PENDING,
    /** 触发条件绑定的设备已不存在；列表标注「设备已移除」，执行时跳过。 */
    val deviceMissing: Boolean = false,
    val cooldownSeconds: Int = 300,
) {
    fun invalidReason(): String? {
        if (name.isBlank()) return "场景名称不能为空"
        if (actions.isEmpty()) return "至少添加一个动作"
        trigger?.invalidReason()?.let { return "触发条件：$it" }
        actions.forEachIndexed { index, action ->
            action.invalidReason()?.let { return "第 ${index + 1} 个动作：$it" }
        }
        return null
    }

    /** 自动触发要求：场景启用且存在非手动触发条件。 */
    val autoTriggerable: Boolean get() = enabled && trigger != null && trigger != SceneTrigger.Manual
}

/** 单个动作的执行结果；失败必须带可读原因，且不阻断后续动作。 */
data class SceneActionOutcome(
    val action: SceneAction,
    val success: Boolean,
    val message: String,
)

/** 整场执行汇总：成功/失败计数与逐条结果。 */
data class SceneRunResult(
    val sceneId: Long,
    val outcomes: List<SceneActionOutcome>,
) {
    val successCount: Int get() = outcomes.count { it.success }
    val failureCount: Int get() = outcomes.count { !it.success }
    val allSucceeded: Boolean get() = outcomes.isNotEmpty() && failureCount == 0

    /** toast/汇总文案：全部成功、部分失败、全部失败三种口径。 */
    fun summary(sceneName: String): String = when {
        outcomes.isEmpty() -> "「$sceneName」没有可执行的动作"
        allSucceeded -> "「$sceneName」执行成功（$successCount 个动作）"
        successCount == 0 -> "「$sceneName」执行失败（$failureCount 个动作均失败）"
        else -> "「$sceneName」部分成功：$successCount 成功 / $failureCount 失败"
    }
}

/**
 * 一条真实的场景执行记录：由引擎触发或用户手动运行后写入，界面只读展示。
 */
data class SceneRun(
    val id: Long,
    val sceneId: Long,
    val sceneName: String,
    val timestamp: Long,
    /** RuleType 名称或 MANUAL。 */
    val triggerType: String,
    /** 触发值快照。 */
    val triggerValue: String,
    val successCount: Int,
    val failureCount: Int,
    val failureReason: String? = null,
    val detail: String,
) {
    val allSucceeded: Boolean get() = failureCount == 0
}

