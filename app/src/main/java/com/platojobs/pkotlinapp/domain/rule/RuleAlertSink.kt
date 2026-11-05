package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.DeviceRule

/**
 * 规则告警输出口：规则引擎只负责「什么时候该告警」，
 * 是否真的投递（开关、系统通知权限、节流）由实现方决定。
 */
interface RuleAlertSink {
    suspend fun onTriggered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int?,
        timestamp: Long,
    )

    /** 信号回落到阈值内时调用；只对 LOW_RSSI 规则有意义。 */
    suspend fun onRecovered(
        rule: DeviceRule,
        mac: String,
        displayName: String,
        rssi: Int,
    )
}
