package com.platojobs.pkotlinapp.data.scene

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.repository.SceneRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRunRepository
import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import com.platojobs.pkotlinapp.domain.rule.RuleTriggerObserver
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneExecutor
import com.platojobs.pkotlinapp.domain.scene.SceneRun
import com.platojobs.pkotlinapp.domain.scene.SceneRunResult
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
            val last = lastRunAt[sceneId]
            if (last != null && runAt - last < SCENE_COOLDOWN_MS) return
            val scene = sceneRepository.getScene(sceneId) ?: return
            // 停用场景时引擎规则也不会加载；这里再兜底一次，避免直接调用路径绕过启停。
            if (!scene.enabled) return
            lastRunAt[sceneId] = runAt
            val result = executor.run(scene)
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
        val result = executor.run(scene, onActionStart)
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
            "RSSI $actual ${RuleComparator.LESS.symbol} ${rule.threshold}"
        }

        RuleType.SCHEDULE -> "${rule.intervalMinutes ?: 0} min"

        RuleType.DISCONNECTED, RuleType.BOND_LOST -> mac
    }

    private companion object {
        const val SCENE_COOLDOWN_MS = 60_000L
        const val MANUAL_TRIGGER = "MANUAL"
        const val MANUAL_VALUE = "-"
    }
}
