package com.platojobs.pkotlinapp.ui.detail

import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUiTest {

    private val mac = "C8:47:8C:3D:9E:01"

    @Test
    fun connectingShowsCancelAndHidesRssi() {
        val ui = ConnectionUi.from(BleConnectionState.Connecting(mac), mac)

        assertEquals(ConnectionPhase.CONNECTING, ui.phase)
        assertEquals(ConnectionAction.CANCEL, ui.action)
        assertFalse(ui.showConnect)
        assertTrue(ui.showDisconnect)
        assertTrue(ui.cancelConnect)
        // 连接中不能把扫描阶段的 RSSI 当成稳定信号展示。
        assertFalse(ui.rssiVisible)
        assertEquals(ConnectionInfoHint.CONNECTING, ui.infoHint)
    }

    @Test
    fun connectedShowsDisconnectAndRssi() {
        val ui = ConnectionUi.from(BleConnectionState.Connected(mac), mac)

        assertEquals(ConnectionPhase.CONNECTED, ui.phase)
        assertEquals(ConnectionAction.DISCONNECT, ui.action)
        assertFalse(ui.showConnect)
        assertTrue(ui.showDisconnect)
        assertFalse(ui.cancelConnect)
        assertTrue(ui.rssiVisible)
        // 连接后设备信息尚未加载时提示「正在读取」，而不是「当前未连接」。
        assertEquals(ConnectionInfoHint.LOADING, ui.infoHint)
    }

    @Test
    fun disconnectedShowsConnectAndDisconnectedHint() {
        val ui = ConnectionUi.from(BleConnectionState.Disconnected(mac), mac)

        assertEquals(ConnectionPhase.DISCONNECTED, ui.phase)
        assertEquals(ConnectionAction.CONNECT, ui.action)
        assertTrue(ui.showConnect)
        assertFalse(ui.showDisconnect)
        assertFalse(ui.cancelConnect)
        assertFalse(ui.rssiVisible)
        assertEquals(ConnectionInfoHint.DISCONNECTED, ui.infoHint)
    }

    @Test
    fun otherDeviceStateIsTreatedAsDisconnected() {
        val ui = ConnectionUi.from(BleConnectionState.Connecting("C8:47:8C:00:00:99"), mac)

        assertEquals(ConnectionPhase.DISCONNECTED, ui.phase)
        assertTrue(ui.showConnect)
        assertFalse(ui.rssiVisible)
    }

    @Test
    fun keyMissingHidesBothActions() {
        val ui = ConnectionUi.from(BleConnectionState.KeyMissing(mac), mac)

        assertEquals(ConnectionPhase.KEY_MISSING, ui.phase)
        assertEquals(ConnectionAction.NONE, ui.action)
        assertFalse(ui.showConnect)
        assertFalse(ui.showDisconnect)
        assertFalse(ui.rssiVisible)
        assertEquals(ConnectionInfoHint.DISCONNECTED, ui.infoHint)
    }
}
