package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.DeviceRule

/**
 * 规则命中后的扩展回调：场景触发规则走这条路径执行动作并写执行记录。
 * 与 [RuleAlertSink] 分开，避免场景动作与普通告警通知各发一次。
 */
interface RuleTriggerObserver {
    suspend fun onRuleTriggered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int?,
        metric: String?,
        value: Double?,
        thresholdValue: Double?,
        unit: String?,
        timestamp: Long,
    )
}
