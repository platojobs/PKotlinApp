package com.platojobs.pkotlinapp.data.collection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 当前后台采集状态；count 是本次会话真实写入的读数条数。 */
data class CollectionStatus(
    val active: Boolean = false,
    val mac: String = "",
    val deviceName: String = "",
    val count: Int = 0,
    val startedAt: Long = 0L,
)

/**
 * 采集会话的唯一事实源：前台服务启动/停止与 UI 状态都读写它。
 * 计数由 AppContainer 的读数回调递增，只有当前设备会被计入。
 */
class CollectionSession {

    private val _status = MutableStateFlow(CollectionStatus())
    val status: StateFlow<CollectionStatus> = _status.asStateFlow()

    fun start(mac: String, deviceName: String, startedAt: Long = System.currentTimeMillis()): Boolean {
        if (mac.isBlank()) return false
        _status.value = CollectionStatus(
            active = true,
            mac = mac,
            deviceName = deviceName,
            count = 0,
            startedAt = startedAt,
        )
        return true
    }

    fun stop() {
        if (_status.value.active) _status.value = CollectionStatus()
    }

    /** 记录一条读数；仅当会话活跃且设备匹配时计数。 */
    fun record(deviceId: String) {
        _status.update { current ->
            if (current.active && current.mac.equals(deviceId, ignoreCase = true)) {
                current.copy(count = current.count + 1)
            } else {
                current
            }
        }
    }

    fun isActiveFor(mac: String): Boolean =
        _status.value.active && _status.value.mac.equals(mac, ignoreCase = true)
}
