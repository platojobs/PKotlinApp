package com.platojobs.pkotlinapp.domain.rule

/**
 * 规则事件文案由 UI 层注入，避免 domain 层直接依赖 Android 资源。
 */
interface RuleTextProvider {
    fun lowRssi(deviceName: String, rssi: Int, threshold: Int): String

    fun disconnected(deviceName: String): String

    fun bondLost(deviceName: String): String
}
