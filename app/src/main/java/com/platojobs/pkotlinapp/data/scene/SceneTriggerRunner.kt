package com.platojobs.pkotlinapp.data.scene

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.repository.SceneRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRunRepository
import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import com.platojobs.pkotlinapp.domain.rule.RuleTriggerObserver
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionOutcome
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.domain.scene.SceneExecutor
import com.platojobs.pkotlinapp.domain.scene.SceneRun
import com.platojobs.pkotlinapp.domain.scene.SceneRunResult
import com.platojobs.pkotlinapp.domain.scene.SceneTriggerContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 场景执行协调器：引擎命中场景规则后真正执行动作，并把结果写进 scene_runs。
 * 同一场景在冷却窗口内只执行一次，避免高频读数导致动作重复。
 * 手动运行同样写入执行记录，界面展示的每条记录都来自这里。
 */
class SceneTriggerRunner(
    private val sceneRepository: SceneRepository,
    private val sceneRunRepository: SceneRunRepository,
    private val executor: SceneExecutor,
    private val now: () -> Long = System::currentTimeMillis,
) : RuleTriggerObserver {

    private val mutex = Mutex()
    private val lastRunAt = mutableMapOf<Long, Long>()

    override suspend fun onRuleTriggered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int?,
        metric: String?,
        value: Double?,
        thresholdValue: Double?,
        unit: String?,
        timestamp: Long,
    ) {
        val sceneId = rule.sceneId ?: return
        mutex.withLock {
            val runAt = now()
            val scene = sceneRepository.getScene(sceneId) ?: return
            // 停用场景时引擎规则也不会加载；这里再兜底一次，避免直接调用路径绕过启停。
            if (!scene.enabled) return
            // 冷却按场景自身配置（服务端可配），默认 60 秒。
            val last = lastRunAt[sceneId]
            if (last != null && runAt - last < scene.cooldownSeconds.coerceAtLeast(1) * 1000L) return
            lastRunAt[sceneId] = runAt
            val context = SceneTriggerContext(
                deviceMac = mac.takeIf { it.isNotBlank() },
                deviceName = displayName,
                metric = metric,
                value = value,
                unit = unit.orEmpty(),
            )
            val result = if (scene.deviceMissing) {
                skippedResult(scene)
            } else {
                executor.run(scene, context = context)
            }
            sceneRunRepository.add(
                buildRun(
                    scene = scene,
                    triggerType = rule.type.name,
                    triggerValue = triggerValueOf(rule, mac, rssi, metric, value, thresholdValue, unit),
                    result = result,
                    timestamp = runAt,
                ),
            )
        }
    }

    /** 用户手动运行：不做冷却，但同样落一条真实执行记录。 */
    suspend fun runManually(
        scene: Scene,
        onActionStart: (index: Int, total: Int, action: SceneAction) -> Unit = { _, _, _ -> },
    ): SceneRunResult {
        val result = if (scene.deviceMissing) {
            skippedResult(scene)
        } else {
            executor.run(scene, onActionStart = onActionStart)
        }
        sceneRunRepository.add(
            buildRun(
                scene = scene,
                triggerType = MANUAL_TRIGGER,
                triggerValue = MANUAL_VALUE,
                result = result,
                timestamp = now(),
            ),
        )
        return result
    }

    private fun buildRun(
        scene: Scene,
        triggerType: String,
        triggerValue: String,
        result: SceneRunResult,
        timestamp: Long,
    ): SceneRun {
        val failures = result.outcomes.filter { !it.success }
        val failureReason = failures.joinToString("; ") { it.message }.ifBlank { null }
        val detail = result.outcomes.joinToString("\n") { outcome ->
            "${outcome.action.type.name}: ${outcome.message}"
        }
        return SceneRun(
            id = 0,
            sceneId = scene.id,
            sceneName = scene.name,
            timestamp = timestamp,
            triggerType = triggerType,
            triggerValue = triggerValue,
            successCount = result.successCount,
            failureCount = result.failureCount,
            failureReason = failureReason,
            detail = detail,
        )
    }

    /**
     * 触发设备已被移除：不执行任何动作，但写一条明确的失败记录，
     * 让「设备已移除」在历史里可见，而不是场景静默失效。
     */
    private fun skippedResult(scene: Scene): SceneRunResult {
        val action = scene.actions.firstOrNull()
            ?: SceneAction(type = SceneActionType.NOTIFY)
        return SceneRunResult(
            sceneId = scene.id,
            outcomes = listOf(
                SceneActionOutcome(
                    action = action,
                    success = false,
                    message = "触发设备已移除，已跳过执行",
                ),
            ),
        )
    }

    private fun triggerValueOf(
        rule: DeviceRule,
        mac: String,
        rssi: Int?,
        metric: String?,
        value: Double?,
        thresholdValue: Double?,
        unit: String?,
    ): String = when (rule.type) {
        RuleType.METRIC_THRESHOLD -> {
            val displayUnit = MetricThreshold.displayUnit(metric.orEmpty(), unit.orEmpty())
            "${metric.orEmpty()} ${MetricThreshold.formatValue(value ?: 0.0)}$displayUnit " +
                "${rule.comparator.symbol} " +
                "${MetricThreshold.formatValue(thresholdValue ?: rule.thresholdValue)}$displayUnit"
        }

        RuleType.LOW_RSSI -> {
            val actual = rssi ?: rule.threshold
            // 旧 RSSI 规则始终按「达到阈值即触发」（<=）判定，展示符号必须含等号，与 iOS 一致。
            "RSSI $actual ≤ ${rule.threshold}"
        }

        RuleType.SCHEDULE ->
            rule.timeOfDay?.takeIf { it.isNotBlank() }
                ?.let { "$it ${rule.timezone.orEmpty()}".trim() }
                ?: "${rule.intervalMinutes ?: 0} min"

        RuleType.DEVICE_OFFLINE -> "${rule.offlineSeconds}s $mac".trim()

        RuleType.DISCONNECTED, RuleType.BOND_LOST -> mac
    }

    private companion object {
        const val MANUAL_TRIGGER = "MANUAL"
        const val MANUAL_VALUE = "-"
    }
}
