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
 * [MANUAL] 表示仅手动运行（历史场景没有触发规则）。
 */
sealed interface SceneTrigger {
    val ruleType: RuleType

    data object Manual : SceneTrigger {
        override val ruleType: RuleType = RuleType.DISCONNECTED
    }

    data object Disconnected : SceneTrigger {
        override val ruleType: RuleType = RuleType.DISCONNECTED
    }

    data class LowRssi(val threshold: Int = -85) : SceneTrigger {
        override val ruleType: RuleType = RuleType.LOW_RSSI
    }

    data class MetricThreshold(
        val metric: String = "",
        val comparator: RuleComparator = RuleComparator.GREATER,
        val thresholdValue: Double = 0.0,
        val consecutive: Int = 1,
    ) : SceneTrigger {
        override val ruleType: RuleType = RuleType.METRIC_THRESHOLD
    }

    data class Schedule(val intervalMinutes: Int = 30) : SceneTrigger {
        override val ruleType: RuleType = RuleType.SCHEDULE
    }

    /** 保存前的最终校验；返回 null 表示可落库。 */
    fun invalidReason(): String? = when (this) {
        SceneTrigger.Manual,
        SceneTrigger.Disconnected,
        -> null

        is SceneTrigger.LowRssi ->
            if (threshold in -100..0) null else "RSSI 阈值需在 -100 到 0 之间"

        is SceneTrigger.MetricThreshold -> when {
            metric.isBlank() -> "请选择要监控的指标"
            !thresholdValue.isFinite() -> "阈值必须是有效数字"
            consecutive !in 1..5 -> "连续读数需在 1 到 5 条之间"
            else -> null
        }

        is SceneTrigger.Schedule ->
            if (intervalMinutes in SCENE_SCHEDULE_INTERVALS) null else "定时间隔只支持 15/30/60/120 分钟"
    }
}

/** 单个动作；设备类动作必须带 [deviceMac]，静音必须带合法的 [muteMinutes]。 */
data class SceneAction(
    val type: SceneActionType,
    val deviceMac: String? = null,
    val muteMinutes: Int? = null,
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
}

data class Scene(
    val id: Long,
    val name: String,
    val actions: List<SceneAction>,
    val createdAt: Long = 0L,
    val enabled: Boolean = true,
    /** 触发条件；null 表示仅手动运行。 */
    val trigger: SceneTrigger? = null,
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

