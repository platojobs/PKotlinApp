package com.platojobs.pkotlinapp.data.ble

import android.bluetooth.BluetoothGatt

/**
 * RSSI 读取的在途互斥与超时复位。
 *
 * GATT 同一时刻只接受一个 RSSI 请求；若系统始终不回调（链路忙、栈丢包），
 * 在途标志必须超时复位，否则 2 秒轮询会永久冻结到重连。
 *
 * @param timeoutMs 超时阈值，默认 [DEFAULT_TIMEOUT_MS]。
 * @param schedule 延迟调度，返回取消函数。
 * @param onTimeout 超时回调，调用方负责把读数状态标为 STALE。
 */
internal class RssiReadGuard(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val schedule: (delayMs: Long, action: () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
) {

    @Volatile
    var isInFlight: Boolean = false
        private set

    private var cancelTimeout: (() -> Unit)? = null

    /** 尝试占用在途名额；已有请求在途时返回 false，调用方应直接跳过本次读取。 */
    fun tryStart(): Boolean {
        if (isInFlight) return false
        isInFlight = true
        cancelTimeout = schedule(timeoutMs) {
            if (isInFlight) {
                isInFlight = false
                cancelTimeout = null
                onTimeout()
            }
        }
        return true
    }

    /** 收到回调（成功或失败）后释放名额；重复调用无副作用。 */
    fun finish() {
        release()
    }

    /** 断开连接/清理时释放名额，不触发超时回调。 */
    fun reset() {
        release()
    }

    private fun release() {
        isInFlight = false
        cancelTimeout?.invoke()
        cancelTimeout = null
    }

    companion object {
        /**
         * 8 秒：正常 RSSI 回调在 1~2 秒内返回，慢设备也远小于 GATT 连接超时（约 10 秒）；
         * 取 8 秒既给慢设备留出余量，又能让 2 秒轮询在下一次窗口前解除冻结。
         */
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}

/**
 * 成功回调但返回非负值（如 `0` / `127`）不是真实读数，按本次读取失败处理：
 * 保留上次成功值与时间，不清成未知。
 */
internal fun isRssiReadResultUsable(status: Int, rssi: Int): Boolean =
    status == BluetoothGatt.GATT_SUCCESS && rssi < 0
