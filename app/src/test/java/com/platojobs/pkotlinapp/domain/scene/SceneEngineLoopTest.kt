package com.platojobs.pkotlinapp.domain.scene

import com.platojobs.pkotlinapp.data.scene.SceneTriggerRunner
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.reading.ReadingSample
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRepository
import com.platojobs.pkotlinapp.domain.repository.SceneRunRepository
import com.platojobs.pkotlinapp.domain.rule.RuleAlertSink
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import com.platojobs.pkotlinapp.domain.rule.RuleTextProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎到场景的完整闭环：越界读数 -> RuleEngine 命中 -> SceneTriggerRunner 执行动作
 * -> 写 scene_runs -> 本地通知只发一次；同一冷却窗口内不重复，停用场景不再执行。
 */
class SceneEngineLoopTest {

    private val mac = "AA:BB:CC:DD:EE:01"
    private val sceneId = 42L
    private val ruleId = 7L

    @Test
    fun `out of range reading triggers scene once and writes a real run log`() = runBlocking {
        var now = 1_000_000L
        val scene = scene(enabled = true)
        val runtime = FakeRuntime()
        val runs = FakeSceneRunRepository()
        val scenes = FakeSceneRepository(scene)
        val runner = SceneTriggerRunner(
            sceneRepository = scenes,
            sceneRunRepository = runs,
            executor = SceneExecutor(runtime, canConnect = { true }),
            now = { now },
        )
        val events = FakeEventRepository()
        val sink = RecordingAlertSink()
        val engine = RuleEngine(
            ruleRepository = FakeRuleRepository(listOf(rule())),
            eventRepository = events,
            textProvider = FakeTextProvider(),
            alertSink = sink,
            triggerObserver = runner,
            now = { now },
        )

        engine.onReading(mac, "客厅温度计", reading(31.0, now))

        // 动作真的执行了：本地通知只发一次，设备真的被标记。
        assertEquals(1, runtime.notifications)
        assertEquals(setOf(mac), runtime.marked)
        // 执行记录由 runner 真正写入，而非界面伪造。
        assertEquals(1, runs.runs.size)
        val run = runs.runs.single()
        assertEquals(sceneId, run.sceneId)
        assertEquals("高温告警", run.sceneName)
        assertEquals(RuleType.METRIC_THRESHOLD.name, run.triggerType)
        assertEquals(0, run.failureCount)
        assertTrue(run.detail.contains("NOTIFY"))
        // 场景规则不走普通告警通道，避免同一次触发弹两条通知。
        assertEquals(0, sink.triggers)

        // 冷却期内再次越界：不重复执行。
        now += 1_000
        engine.onReading(mac, "客厅温度计", reading(32.0, now))
        assertEquals(1, runtime.notifications)
        assertEquals(1, runs.runs.size)

        // 冷却结束后停用场景：规则仍在引擎缓存里，但 runner 兜底拒绝执行。
        now += 61_000
        scenes.scenes[sceneId] = scene.copy(enabled = false)
        engine.onReading(mac, "客厅温度计", reading(33.0, now))
        assertEquals(1, runtime.notifications)
        assertEquals(1, runs.runs.size)
        // 三次越界里引擎只写了两条事件（第二次在冷却期内被丢弃）。
        assertEquals(2, events.events.size)
    }

    @Test
    fun `disabled scene rule is not loaded by the engine`() = runBlocking {
        val runtime = FakeRuntime()
        val runner = SceneTriggerRunner(
            sceneRepository = FakeSceneRepository(scene(enabled = false)),
            sceneRunRepository = FakeSceneRunRepository(),
            executor = SceneExecutor(runtime, canConnect = { true }),
        )
        val engine = RuleEngine(
            ruleRepository = FakeRuleRepository(listOf(rule(enabled = false))),
            eventRepository = FakeEventRepository(),
            textProvider = FakeTextProvider(),
            alertSink = RecordingAlertSink(),
            triggerObserver = runner,
        )

        engine.onReading(mac, "客厅温度计", reading(31.0, 1_000))

        assertEquals(0, runtime.notifications)
    }

    private fun rule(enabled: Boolean = true) = DeviceRule(
        id = ruleId,
        type = RuleType.METRIC_THRESHOLD,
        threshold = 0,
        enabled = enabled,
        createdAt = 0L,
        lastTriggeredTimestamp = 0L,
        metric = ReadingMetrics.TEMPERATURE,
        comparator = RuleComparator.GREATER,
        thresholdValue = 30.0,
        consecutive = 1,
        sceneId = sceneId,
    )

    private fun scene(enabled: Boolean) = Scene(
        id = sceneId,
        name = "高温告警",
        actions = listOf(
            SceneAction(SceneActionType.NOTIFY),
            SceneAction(SceneActionType.MARK_DEVICE, deviceMac = mac),
        ),
        enabled = enabled,
        trigger = SceneTrigger.MetricThreshold(
            metric = ReadingMetrics.TEMPERATURE,
            comparator = RuleComparator.GREATER,
            thresholdValue = 30.0,
            consecutive = 1,
        ),
    )

    private fun reading(value: Double, timestamp: Long) = ReadingSample(
        metric = ReadingMetrics.TEMPERATURE,
        value = value,
        unit = "°C",
        recordedAt = timestamp,
    )

    private class FakeRuleRepository(initial: List<DeviceRule>) : RuleRepository {
        private val rules = initial.toMutableList()

        override fun observeRules(): Flow<List<DeviceRule>> = flowOf(rules.toList())

        override suspend fun getRules(): List<DeviceRule> = rules.toList()

        override suspend fun addRule(type: RuleType, threshold: Int): Long = 0L

        override suspend fun addMetricRule(
            metric: String,
            comparator: RuleComparator,
            thresholdValue: Double,
            consecutive: Int,
        ): Long = 0L

        override suspend fun setEnabled(id: Long, enabled: Boolean) = Unit

        override suspend fun deleteRule(id: Long) = Unit

        override suspend fun markTriggered(id: Long, timestamp: Long) = Unit
    }

    private class FakeEventRepository : RuleEventRepository {
        val events = mutableListOf<RuleEvent>()

        override fun observeRecent(limit: Int): Flow<List<RuleEvent>> = flowOf(events.toList())

        override fun observeCountBetween(from: Long, to: Long): Flow<Int> =
            flowOf(events.count { it.timestamp in from..to })

        override fun observeMetricViolationCountBetween(from: Long, to: Long): Flow<Int> =
            flowOf(events.count { it.timestamp in from..to && it.metric != null })

        override fun observeLatestBetween(from: Long, to: Long): Flow<RuleEvent?> =
            flowOf(events.filter { it.timestamp in from..to }.maxByOrNull { it.timestamp })

        override suspend fun getRecent(limit: Int): List<RuleEvent> = events.takeLast(limit)

        override suspend fun add(event: RuleEvent): Long {
            events += event
            return events.size.toLong()
        }

        override suspend fun clear() {
            events.clear()
        }
    }

    private class RecordingAlertSink : RuleAlertSink {
        var triggers = 0

        override suspend fun onTriggered(
            rule: DeviceRule,
            mac: String,
            displayName: String,
            rssi: Int?,
            timestamp: Long,
        ) {
            triggers += 1
        }

        override suspend fun onRecovered(
            rule: DeviceRule,
            mac: String,
            displayName: String,
            rssi: Int,
        ) = Unit
    }

    private class FakeTextProvider : RuleTextProvider {
        override fun lowRssi(deviceName: String, rssi: Int, threshold: Int): String =
            "$deviceName $rssi < $threshold"

        override fun disconnected(deviceName: String): String = "$deviceName 已断开"

        override fun bondLost(deviceName: String): String = "$deviceName 绑定丢失"

        override fun scheduled(intervalMinutes: Int): String = "$intervalMinutes 分钟"

        override fun metricThreshold(
            deviceName: String,
            metric: String,
            value: Double,
            unit: String,
            comparator: RuleComparator,
            threshold: Double,
        ): String = "$deviceName $metric $value$unit"
    }

    private class FakeSceneRepository(vararg initial: Scene) : SceneRepository {
        val scenes = initial.associateBy { it.id }.toMutableMap()

        override fun observeScenes(): Flow<List<Scene>> = flowOf(scenes.values.toList())

        override suspend fun getScenes(): List<Scene> = scenes.values.toList()

        override suspend fun getScene(id: Long): Scene? = scenes[id]

        override suspend fun findSceneByRuleId(ruleId: Long): Scene? =
            scenes.values.firstOrNull { it.id == sceneIdOf(ruleId) }

        override suspend fun saveScene(
            id: Long?,
            name: String,
            actions: List<SceneAction>,
            trigger: SceneTrigger?,
            enabled: Boolean,
        ): Long = 0L

        override suspend fun setEnabled(id: Long, enabled: Boolean) = Unit

        override suspend fun deleteScene(id: Long) = Unit

        private fun sceneIdOf(ruleId: Long): Long = if (ruleId == 7L) 42L else -1L
    }

    private class FakeSceneRunRepository : SceneRunRepository {
        val runs = mutableListOf<SceneRun>()

        override fun observeRecent(limit: Int): Flow<List<SceneRun>> = flowOf(runs.toList())

        override fun observeLatest(): Flow<SceneRun?> = flowOf(runs.lastOrNull())

        override suspend fun getRecent(limit: Int): List<SceneRun> = runs.takeLast(limit)

        override suspend fun add(run: SceneRun): Long {
            runs += run
            return runs.size.toLong()
        }

        override suspend fun clear() {
            runs.clear()
        }
    }

    private class FakeRuntime : SceneRuntime {
        var notifications = 0
        val marked = mutableSetOf<String>()

        override suspend fun connectDevice(mac: String): Boolean = true

        override suspend fun disconnectDevice(mac: String): Boolean = true

        override suspend fun connectedMac(): String? = null

        override fun collectionActive(): Boolean = false

        override fun activeCollectionMac(): String? = null

        override suspend fun startCollection(mac: String): Boolean = true

        override fun stopCollection(): Boolean = false

        override fun muteAlerts(minutes: Int) = Unit

        override fun syncNow() = Unit

        override suspend fun notify(sceneName: String, details: String): Boolean {
            notifications += 1
            return true
        }

        override suspend fun markDevice(mac: String): Boolean {
            if (mac.isBlank()) return false
            marked += mac
            return true
        }
    }
}
