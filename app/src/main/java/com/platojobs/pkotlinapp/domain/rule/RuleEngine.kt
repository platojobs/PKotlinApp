package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.reading.ReadingSample
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地规则引擎：规则配置存 Room，触发结果同样落库。
 * 同一规则 + 同一设备在冷却窗口内只记录一次，避免扫描/读数高频回调刷屏。
 * 指标规则要求连续 N 条读数满足条件（防抖），中间任一条不满足即清零并恢复。
 * 通知投递交给 [RuleAlertSink]；规则事件始终记录，不受通知开关影响。
 */
class RuleEngine(
    private val ruleRepository: RuleRepository,
    private val eventRepository: RuleEventRepository,
    private val textProvider: RuleTextProvider,
    private val alertSink: RuleAlertSink? = null,
    private val triggerObserver: RuleTriggerObserver? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private var cachedRules: List<DeviceRule> = emptyList()
    private var loaded: Boolean = false
    private val lastTriggeredAt = mutableMapOf<String, Long>()
    /** 每个 `ruleId:mac` 当前连续满足的读数条数。 */
    private val streaks = mutableMapOf<String, Int>()
    /** 已触发且尚未恢复的规则，用于「确实告警过才恢复」的语义。 */
    private val activeAlerts = mutableSetOf<String>()
    /** 读数与扫描可能并发回调，防抖计数与节流状态必须串行更新。 */
    private val mutex = Mutex()

    suspend fun refresh() = mutex.withLock { loadRules() }

    private suspend fun loadRules() {
        val enabled = ruleRepository.getRules().filter { it.enabled }
        loaded = true
        cachedRules = enabled
        val enabledIds = enabled.map { it.id }.toSet()
        activeAlerts.removeAll { key -> key.substringBefore(':').toLongOrNull() !in enabledIds }
    }

    suspend fun onScan(device: ScannedDevice, displayName: String): List<RuleEvent> = mutex.withLock {
        if (!loaded) loadRules()
        val lowRssiRules = cachedRules.filter { it.type == RuleType.LOW_RSSI }
        lowRssiRules.mapNotNull { rule ->
            if (device.rssi < rule.threshold) {
                trigger(rule, device.mac, displayName, device.rssi, textProvider.lowRssi(displayName, device.rssi, rule.threshold))
            } else {
                recover(rule, device.mac, displayName, device.rssi)
                null
            }
        }
    }

    /**
     * 新读数入库后调用；只匹配该指标的指标阈值规则。
     * 返回本次真正新写入的规则事件（冷却/防抖未达标时为空）。
     */
    suspend fun onReading(mac: String, displayName: String, sample: ReadingSample): List<RuleEvent> = mutex.withLock {
        if (!loaded) loadRules()
        if (!sample.value.isFinite()) return@withLock emptyList()
        val metric = ReadingMetrics.normalize(sample.metric)
        if (metric == ReadingMetrics.OTHER) return@withLock emptyList()
        val matched = cachedRules.filter {
            it.type == RuleType.METRIC_THRESHOLD && it.metric == metric
        }
        if (matched.isEmpty()) return@withLock emptyList()

        val unit = MetricThreshold.displayUnit(metric, sample.unit)
        val events = mutableListOf<RuleEvent>()
        matched.forEach { rule ->
            val key = keyOf(rule, mac)
            if (rule.comparator.matches(sample.value, rule.thresholdValue)) {
                val streak = (streaks[key] ?: 0) + 1
                streaks[key] = streak
                if (streak >= MetricThreshold.sanitizeConsecutive(rule.consecutive)) {
                    trigger(
                        rule = rule,
                        mac = mac,
                        displayName = displayName,
                        rssi = null,
                        message = textProvider.metricThreshold(
                            deviceName = displayName,
                            metric = metric,
                            value = sample.value,
                            unit = unit,
                            comparator = rule.comparator,
                            threshold = rule.thresholdValue,
                        ),
                        metric = metric,
                        value = sample.value,
                        thresholdValue = rule.thresholdValue,
                        unit = unit,
                    )?.let(events::add)
                }
            } else {
                streaks[key] = 0
                recoverMetric(rule, mac, displayName, metric, sample.value, unit)
            }
        }
        events
    }

    suspend fun onDisconnected(mac: String, displayName: String): List<RuleEvent> = mutex.withLock {
        if (!loaded) loadRules()
        val matched = cachedRules.filter { it.type == RuleType.DISCONNECTED }
        matched.mapNotNull { rule ->
            trigger(rule, mac, displayName, null, textProvider.disconnected(displayName))
        }
    }

    suspend fun onKeyMissing(mac: String, displayName: String): List<RuleEvent> = mutex.withLock {
        if (!loaded) loadRules()
        val matched = cachedRules.filter { it.type == RuleType.BOND_LOST }
        matched.mapNotNull { rule ->
            trigger(rule, mac, displayName, null, textProvider.bondLost(displayName))
        }
    }

    /**
     * 定时规则的判定入口：间隔到达后触发，由后台定时任务调用。
     * 同一条定时规则在间隔内只触发一次；`lastTriggeredTimestamp` 跨进程重启后仍有效。
     */
    suspend fun onScheduleTick(): List<RuleEvent> = mutex.withLock {
        if (!loaded) loadRules()
        val scheduled = cachedRules.filter { it.type == RuleType.SCHEDULE }
        val current = now()
        scheduled.mapNotNull { rule ->
            val interval = rule.intervalMinutes ?: return@mapNotNull null
            if (interval <= 0) return@mapNotNull null
            val key = keyOf(rule, SCHEDULE_MAC)
            val last = lastTriggeredAt[key] ?: rule.lastTriggeredTimestamp
            if (current - last < interval * 60_000L) return@mapNotNull null
            trigger(
                rule = rule,
                mac = SCHEDULE_MAC,
                displayName = "",
                rssi = null,
                message = textProvider.scheduled(interval),
            )
        }
    }

    private suspend fun trigger(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int?,
        message: String,
        metric: String? = null,
        value: Double? = null,
        thresholdValue: Double? = null,
        unit: String? = null,
    ): RuleEvent? {
        val timestamp = now()
        val key = keyOf(rule, mac)
        val last = lastTriggeredAt[key]
        if (last != null && timestamp - last < COOLDOWN_MS) return null

        lastTriggeredAt[key] = timestamp
        activeAlerts.add(key)
        ruleRepository.markTriggered(rule.id, timestamp)
        val event = RuleEvent(
            id = 0,
            ruleType = rule.type,
            mac = mac,
            message = message,
            timestamp = timestamp,
            metric = metric,
            value = value,
            thresholdValue = thresholdValue,
            unit = unit,
        )
        val id = eventRepository.add(event)
        if (rule.sceneId != null) {
            // 场景动作自行决定是否通知；普通告警只对独立规则投递，避免同一次触发发两条通知。
            runCatching {
                triggerObserver?.onRuleTriggered(
                    rule = rule,
                    mac = mac,
                    displayName = displayName,
                    rssi = rssi,
                    metric = metric,
                    value = value,
                    thresholdValue = thresholdValue,
                    unit = unit,
                    timestamp = timestamp,
                )
            }
        } else if (rule.isMetricRule) {
            alertSink?.onMetricTriggered(rule, mac, displayName, metric.orEmpty(), value ?: 0.0, unit.orEmpty(), timestamp)
        } else {
            alertSink?.onTriggered(rule, mac, displayName, rssi, timestamp)
        }
        return event.copy(id = id)
    }

    private suspend fun recover(rule: DeviceRule, mac: String, displayName: String, rssi: Int) {
        val key = keyOf(rule, mac)
        if (!activeAlerts.remove(key)) return
        lastTriggeredAt.remove(key)
        if (rule.sceneId == null) alertSink?.onRecovered(rule, mac, displayName, rssi)
    }

    /** 指标不满足阈值时调用；只在之前确实触发过时才发恢复通知。 */
    private suspend fun recoverMetric(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        metric: String,
        value: Double,
        unit: String,
    ) {
        val key = keyOf(rule, mac)
        if (!activeAlerts.remove(key)) return
        lastTriggeredAt.remove(key)
        if (rule.sceneId == null) {
            alertSink?.onMetricRecovered(rule, mac, displayName, metric, value, unit)
        }
    }

    private fun keyOf(rule: DeviceRule, mac: String) = "${rule.id}:$mac"

    private companion object {
        const val COOLDOWN_MS = 60_000L
        /** 定时规则与设备无关，用固定占位 MAC。 */
        const val SCHEDULE_MAC = ""
    }
}
