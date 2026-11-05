package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.domain.repository.RuleEventRepository
import com.platojobs.pkotlinapp.domain.repository.RuleRepository

/**
 * 本地规则引擎：规则配置存 Room，触发结果同样落库。
 * 同一规则 + 同一设备在冷却窗口内只记录一次，避免扫描高频回调刷屏。
 * 通知投递交给 [RuleAlertSink]；规则事件始终记录，不受通知开关影响。
 */
class RuleEngine(
    private val ruleRepository: RuleRepository,
    private val eventRepository: RuleEventRepository,
    private val textProvider: RuleTextProvider,
    private val alertSink: RuleAlertSink? = null,
) {

    private var cachedRules: List<DeviceRule> = emptyList()
    private val lastTriggeredAt = mutableMapOf<String, Long>()
    /** 已触发且尚未恢复的信号规则（`ruleId:mac`），用于发一次「已恢复」通知。 */
    private val activeLowRssi = mutableSetOf<String>()

    suspend fun refresh() {
        cachedRules = ruleRepository.getRules().filter { it.enabled }
    }

    suspend fun onScan(device: ScannedDevice, displayName: String): List<RuleEvent> {
        val lowRssiRules = cachedRules.filter { it.type == RuleType.LOW_RSSI }
        val events = lowRssiRules.mapNotNull { rule ->
            if (device.rssi < rule.threshold) {
                trigger(rule, device.mac, displayName, device.rssi, textProvider.lowRssi(displayName, device.rssi, rule.threshold))
            } else {
                recover(rule, device.mac, displayName, device.rssi)
                null
            }
        }
        return events
    }

    suspend fun onDisconnected(mac: String, displayName: String): List<RuleEvent> {
        val matched = cachedRules.filter { it.type == RuleType.DISCONNECTED }
        return matched.mapNotNull { rule ->
            trigger(rule, mac, displayName, null, textProvider.disconnected(displayName))
        }
    }

    suspend fun onKeyMissing(mac: String, displayName: String): List<RuleEvent> {
        val matched = cachedRules.filter { it.type == RuleType.BOND_LOST }
        return matched.mapNotNull { rule ->
            trigger(rule, mac, displayName, null, textProvider.bondLost(displayName))
        }
    }

    private suspend fun trigger(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int?,
        message: String,
    ): RuleEvent? {
        val now = System.currentTimeMillis()
        val key = "${rule.id}:$mac"
        val last = lastTriggeredAt[key]
        if (last != null && now - last < COOLDOWN_MS) return null

        lastTriggeredAt[key] = now
        if (rule.type == RuleType.LOW_RSSI) activeLowRssi.add(key)
        ruleRepository.markTriggered(rule.id, now)
        val event = RuleEvent(
            id = 0,
            ruleType = rule.type,
            mac = mac,
            message = message,
            timestamp = now,
        )
        val id = eventRepository.add(event)
        alertSink?.onTriggered(rule, mac, displayName, rssi, now)
        return event.copy(id = id)
    }

    private suspend fun recover(rule: DeviceRule, mac: String, displayName: String, rssi: Int) {
        val key = "${rule.id}:$mac"
        if (!activeLowRssi.remove(key)) return
        lastTriggeredAt.remove(key)
        alertSink?.onRecovered(rule, mac, displayName, rssi)
    }

    private companion object {
        const val COOLDOWN_MS = 60_000L
    }
}
