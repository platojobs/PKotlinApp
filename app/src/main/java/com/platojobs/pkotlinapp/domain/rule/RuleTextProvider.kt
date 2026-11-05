package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.RuleComparator

/**
 * 规则事件文案由 UI 层注入，避免 domain 层直接依赖 Android 资源。
 */
interface RuleTextProvider {
    fun lowRssi(deviceName: String, rssi: Int, threshold: Int): String

    fun disconnected(deviceName: String): String

    fun bondLost(deviceName: String): String

    /** 指标阈值命中文案，数值与单位都由调用方给出（domain 不依赖 Android 资源）。 */
    fun metricThreshold(
        deviceName: String,
        metric: String,
        value: Double,
        unit: String,
        comparator: RuleComparator,
        threshold: Double,
    ): String
}
