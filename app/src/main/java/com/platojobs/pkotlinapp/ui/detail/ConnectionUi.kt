package com.platojobs.pkotlinapp.ui.detail

import com.platojobs.pkotlinapp.data.ble.BleConnectionState

/** 连接状态区与设备信息提示共用的唯一状态源。 */
enum class ConnectionPhase {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    KEY_MISSING,
}

/** 主操作按钮：未连接显示「连接」，连接中显示「取消」，已连接显示「断开连接」。 */
enum class ConnectionAction {
    NONE,
    CONNECT,
    CANCEL,
    DISCONNECT,
}

/** 「设备信息」卡片的提示文案，与连接状态保持一致，避免一处说连接中、另一处说未连接。 */
enum class ConnectionInfoHint {
    NONE,
    CONNECTING,
    DISCONNECTED,
    LOADING,
}

data class ConnectionUi(
    val phase: ConnectionPhase,
    val action: ConnectionAction,
    val showConnect: Boolean,
    val showDisconnect: Boolean,
    /** 连接中时断开按钮复用为「取消」，避免同时出现稳定的信号值。 */
    val cancelConnect: Boolean,
    val rssiVisible: Boolean,
    val infoHint: ConnectionInfoHint,
) {
    companion object {
        fun from(state: BleConnectionState, mac: String): ConnectionUi = when {
            state is BleConnectionState.Connected && state.mac == mac -> ConnectionUi(
                phase = ConnectionPhase.CONNECTED,
                action = ConnectionAction.DISCONNECT,
                showConnect = false,
                showDisconnect = true,
                cancelConnect = false,
                rssiVisible = true,
                infoHint = ConnectionInfoHint.LOADING,
            )

            state is BleConnectionState.Connecting && state.mac == mac -> ConnectionUi(
                phase = ConnectionPhase.CONNECTING,
                action = ConnectionAction.CANCEL,
                showConnect = false,
                showDisconnect = true,
                cancelConnect = true,
                // 连接尚未建立，扫描阶段的 RSSI 不能当成稳定信号展示。
                rssiVisible = false,
                infoHint = ConnectionInfoHint.CONNECTING,
            )

            state is BleConnectionState.KeyMissing && state.mac == mac -> ConnectionUi(
                phase = ConnectionPhase.KEY_MISSING,
                action = ConnectionAction.NONE,
                showConnect = false,
                showDisconnect = false,
                cancelConnect = false,
                rssiVisible = false,
                infoHint = ConnectionInfoHint.DISCONNECTED,
            )

            else -> ConnectionUi(
                phase = ConnectionPhase.DISCONNECTED,
                action = ConnectionAction.CONNECT,
                showConnect = true,
                showDisconnect = false,
                cancelConnect = false,
                rssiVisible = false,
                infoHint = ConnectionInfoHint.DISCONNECTED,
            )
        }
    }
}
