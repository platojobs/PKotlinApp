package com.platojobs.pkotlinapp.data.ble

enum class BleError {
    UNSUPPORTED,
    BLUETOOTH_DISABLED,
    SCAN_FAILED,
    MISSING_PERMISSION,
}

class BleException(
    val error: BleError,
    val code: Int = 0,
) : Exception("BLE error: $error (code=$code)")

/** 一次 RSSI 读取的结果；[STALE] 表示本次失败，当前展示的仍是上次成功值。 */
enum class RssiReadState { IDLE, CURRENT, STALE }

sealed interface BleConnectionState {
    data object Idle : BleConnectionState

    data class Connecting(val mac: String) : BleConnectionState

    data class Connected(val mac: String) : BleConnectionState

    data class Disconnected(val mac: String) : BleConnectionState

    /** Android 16 ACTION_KEY_MISSING 后进入该状态，手动重试前禁止重连。 */
    data class KeyMissing(val mac: String) : BleConnectionState
}
