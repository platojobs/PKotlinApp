package com.platojobs.pkotlinapp.data.push

/**
 * 服务端推送 data 载荷：告警创建接口会写入 `alertId`/`kind`/`severity`，
 * 设备类告警的 payload 里带 `mac`（或 `externalId`）与可选 `deviceName`。
 */
data class PushMessage(
    val alertId: String?,
    val kind: String?,
    val severity: String?,
    val data: Map<String, String>,
)

/** 点击通知后的落点；当前无真实推送，先用纯函数预留并可单测。 */
sealed interface PushDestination {
    data class DeviceDetail(val mac: String, val name: String?) : PushDestination

    /** 场景执行/场景类告警统一进智能模块的场景列表。 */
    data object Scenes : PushDestination

    /** 缺失可路由字段时返回 null，交给系统默认打开 App。 */
    data object Unknown : PushDestination
}

/**
 * 解析推送 data 并决定跳转目标。
 *
 * 只做纯逻辑，不依赖 Android，便于单测覆盖：
 * - `device_offline` / `rule_alert` / `metric_threshold` / `low_rssi` 等设备类 -> 设备详情；
 * - `scene_triggered` / `scene_*` -> 场景页；
 * - 未知 kind 但带 mac 时仍可进设备详情，否则回落到场景页；
 * - 没有 alertId 也没有 kind 的 data 不视为推送消息。
 */
object PushMessageHandler {

    fun parse(data: Map<String, String>): PushMessage? {
        val normalized = data.mapKeys { it.key.trim() }.mapValues { it.value.trim() }
        val alertId = normalized["alertId"]?.takeIf { it.isNotBlank() }
        val kind = normalized["kind"]?.takeIf { it.isNotBlank() }
        if (alertId == null && kind == null) return null
        return PushMessage(
            alertId = alertId,
            kind = kind,
            severity = normalized["severity"]?.takeIf { it.isNotBlank() },
            data = normalized,
        )
    }

    fun destination(message: PushMessage): PushDestination {
        val kind = message.kind?.lowercase().orEmpty()
        val mac = macOf(message.data)
        when {
            kind.startsWith("scene") -> return PushDestination.Scenes
            kind.startsWith("device") ||
                kind.startsWith("rule") ||
                kind.startsWith("metric") ||
                kind == "low_rssi" ->
                return mac?.let { PushDestination.DeviceDetail(it, nameOf(message.data)) }
                    ?: PushDestination.Scenes
            mac != null -> return PushDestination.DeviceDetail(mac, nameOf(message.data))
            else -> return PushDestination.Scenes
        }
    }

    private fun macOf(data: Map<String, String>): String? =
        (data["mac"] ?: data["deviceMac"] ?: data["externalId"])
            ?.takeIf { it.isNotBlank() }

    private fun nameOf(data: Map<String, String>): String? =
        (data["deviceName"] ?: data["name"])?.takeIf { it.isNotBlank() }
}
