package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.reading.ReadingSample
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎口径：
 * - 旧 RSSI 规则升级后仍按 `rssi < threshold` 触发；
 * - 指标规则连续 N 条满足才触发，任一条不满足即清零；
 * - 同一 ruleId + mac 的冷却窗口内只记录一次；
 * - 恢复只在确实触发过之后发一次。
 */
class RuleEngineTest {

    private class FakeRuleRepository(initial: List<DeviceRule>) : RuleRepository {
        val rules = initial.toMutableList()
        val triggered = mutableListOf<Long>()

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

        override suspend fun markTriggered(id: Long, timestamp: Long) {
            triggered += id
        }
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

    private class FakeTextProvider : RuleTextProvider {
        override fun lowRssi(deviceName: String, rssi: Int, threshold: Int): String =
            "$deviceName 信号 $rssi 低于 $threshold"

        override fun disconnected(deviceName: String): String = "$deviceName 已断开"

        override fun bondLost(deviceName: String): String = "$deviceName 绑定丢失"

        override fun scheduled(intervalMinutes: Int): String = "定时规则每 $intervalMinutes 分钟触发"

        override fun metricThreshold(
            deviceName: String,
            metric: String,
            value: Double,
            unit: String,
            comparator: RuleComparator,
            threshold: Double,
        ): String = "$deviceName $metric ${MetricThreshold.formatValue(value)}$unit " +
            "${comparator.symbol} ${MetricThreshold.formatValue(threshold)}$unit"
    }

    private class RecordingAlertSink : RuleAlertSink {
        var rssiRecovered = 0
        val metricTriggers = mutableListOf<Pair<String, Double>>()
        val metricRecoveries = mutableListOf<Pair<String, Double>>()

        override suspend fun onTriggered(
            rule: DeviceRule,
            mac: String,
            displayName: String,
            rssi: Int?,
            timestamp: Long,
        ) = Unit

        override suspend fun onRecovered(
            rule: DeviceRule,
            mac: String,
            displayName: String,
            rssi: Int,
        ) {
            rssiRecovered += 1
        }

        override suspend fun onMetricTriggered(
            rule: DeviceRule,
            mac: String,
            displayName: String,
            metric: String,
            value: Double,
            unit: String,
            timestamp: Long,
        ) {
            metricTriggers += metric to value
        }

        override suspend fun onMetricRecovered(
            rule: DeviceRule,
            mac: String,
            displayName: String,
            metric: String,
            value: Double,
            unit: String,
        ) {
            metricRecoveries += metric to value
        }
    }

    private fun metricRule(
        id: Long,
        metric: String = ReadingMetrics.TEMPERATURE,
        comparator: RuleComparator = RuleComparator.GREATER,
        threshold: Double = 30.0,
        consecutive: Int = 1,
        enabled: Boolean = true,
    ) = DeviceRule(
        id = id,
        type = RuleType.METRIC_THRESHOLD,
        threshold = 0,
        enabled = enabled,
        createdAt = 0L,
        lastTriggeredTimestamp = 0L,
        metric = metric,
        comparator = comparator,
        thresholdValue = threshold,
        consecutive = consecutive,
    )

    private fun reading(metric: String, value: Double, unit: String) = ReadingSample(
        metric = metric,
        value = value,
        unit = unit,
        recordedAt = 1_760_000_000_000L,
    )

    private fun engine(
        rules: List<DeviceRule>,
        events: FakeEventRepository = FakeEventRepository(),
        sink: RecordingAlertSink = RecordingAlertSink(),
    ): Triple<RuleEngine, FakeEventRepository, RecordingAlertSink> = Triple(
        RuleEngine(FakeRuleRepository(rules), events, FakeTextProvider(), sink),
        events,
        sink,
    )

    @Test
    fun `upgraded low rssi rule still triggers`() = runBlocking {
        // 迁移后的旧规则：type/threshold 不变，新增字段为回填值（metric 仍为 null）。
        val legacy = DeviceRule(
            id = 1,
            type = RuleType.LOW_RSSI,
            threshold = -70,
            enabled = true,
            createdAt = 0L,
            lastTriggeredTimestamp = 0L,
            metric = null,
            comparator = RuleComparator.LESS,
            thresholdValue = -70.0,
            consecutive = 1,
        )
        val (engine, events, _) = engine(listOf(legacy))
        val triggered = engine.onScan(
            ScannedDevice(name = "温湿度计", mac = "AA:BB:CC:DD:EE:01", rssi = -85, timestamp = 0L),
            "温湿度计",
        )
        assertEquals(1, triggered.size)
        assertEquals(RuleType.LOW_RSSI, events.events.single().ruleType)
        assertEquals("AA:BB:CC:DD:EE:01", events.events.single().mac)
    }

    @Test
    fun `metric rule needs consecutive samples before triggering`() = runBlocking {
        val (engine, events, sink) = engine(listOf(metricRule(id = 2, consecutive = 3)))
        val samples = listOf(31.0, 32.0, 29.0, 31.0, 32.0, 33.0)

        samples.forEach { value ->
            engine.onReading("AA:BB", "客厅温度计", reading(ReadingMetrics.TEMPERATURE, value, "°C"))
        }

        // 前两条不满足 3 条；29 清零；最后三条再次满足才触发一次。
        assertEquals(1, events.events.size)
        val event = events.events.single()
        assertEquals(ReadingMetrics.TEMPERATURE, event.metric)
        assertEquals(33.0, event.value ?: -1.0, 0.001)
        assertEquals(30.0, event.thresholdValue ?: -1.0, 0.001)
        assertEquals("℃", event.unit)
        assertEquals("客厅温度计 temperature 33℃ > 30℃", event.message)
        assertEquals(1, sink.metricTriggers.size)
    }

    @Test
    fun `metric rule triggers immediately when consecutive is one`() = runBlocking {
        val (engine, events, _) = engine(listOf(metricRule(id = 3, consecutive = 1)))
        engine.onReading("AA:BB", "客厅温度计", reading(ReadingMetrics.TEMPERATURE, 31.2, "°C"))
        assertEquals(1, events.events.size)
        assertEquals(31.2, events.events.single().value ?: -1.0, 0.001)
    }

    @Test
    fun `less comparator triggers below threshold`() = runBlocking {
        val rule = metricRule(
            id = 4,
            metric = ReadingMetrics.BATTERY,
            comparator = RuleComparator.LESS,
            threshold = 20.0,
        )
        val (engine, events, _) = engine(listOf(rule))

        engine.onReading("AA:BB", "手环", reading(ReadingMetrics.BATTERY, 25.0, "%"))
        assertEquals(0, events.events.size)

        engine.onReading("AA:BB", "手环", reading(ReadingMetrics.BATTERY, 18.0, "%"))
        assertEquals(1, events.events.size)
        assertEquals("%", events.events.single().unit)
    }

    @Test
    fun `cooldown suppresses repeated triggers for same rule and device`() = runBlocking {
        val (engine, events, _) = engine(listOf(metricRule(id = 5)))
        engine.onReading("AA:BB", "温度计", reading(ReadingMetrics.TEMPERATURE, 31.0, "°C"))
        engine.onReading("AA:BB", "温度计", reading(ReadingMetrics.TEMPERATURE, 32.0, "°C"))

        assertEquals(1, events.events.size)
    }

    @Test
    fun `recovery only fires once after a real trigger`() = runBlocking {
        val (engine, _, sink) = engine(listOf(metricRule(id = 6)))

        engine.onReading("AA:BB", "温度计", reading(ReadingMetrics.TEMPERATURE, 25.0, "°C"))
        assertEquals(0, sink.metricRecoveries.size)

        engine.onReading("AA:BB", "温度计", reading(ReadingMetrics.TEMPERATURE, 31.0, "°C"))
        assertEquals(1, sink.metricTriggers.size)

        engine.onReading("AA:BB", "温度计", reading(ReadingMetrics.TEMPERATURE, 25.0, "°C"))
        engine.onReading("AA:BB", "温度计", reading(ReadingMetrics.TEMPERATURE, 24.0, "°C"))
        assertEquals(1, sink.metricRecoveries.size)
        assertEquals(25.0, sink.metricRecoveries.single().second, 0.001)
    }

    @Test
    fun `disabled rules never trigger`() = runBlocking {
        val (engine, events, _) = engine(listOf(metricRule(id = 7, enabled = false)))
        engine.onReading("AA:BB", "温度计", reading(ReadingMetrics.TEMPERATURE, 35.0, "°C"))
        assertTrue(events.events.isEmpty())
    }

    @Test
    fun `unknown metric is ignored instead of matching every rule`() = runBlocking {
        val (engine, events, _) = engine(listOf(metricRule(id = 8)))
        engine.onReading("AA:BB", "温度计", reading("mystery", 99.0, ""))
        assertTrue(events.events.isEmpty())
    }
}
