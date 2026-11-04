package com.platojobs.pkotlinapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDeviceInfoTest {

    @Test
    fun `offline info prefers cached display name and real group`() {
        val device = Device(
            mac = "AA:BB:CC:DD:EE:FF",
            name = "客厅温湿度计",
            lastRssi = -55,
            lastSeenTimestamp = 1_762_300_000_000,
            deviceType = "nordic_uart",
            vendor = "Nordic",
            firmwareVersion = "1.2.0",
            compatibility = "android · ble",
            deviceTypeName = "Nordic UART 温湿度计",
            group = "客厅",
        )

        val info = device.toOfflineDeviceInfo()

        // 优先展示 catalog 缓存名，而不是型号键。
        assertEquals("Nordic UART 温湿度计", info.deviceType)
        assertEquals("nordic_uart", info.deviceTypeKey)
        assertEquals("客厅温湿度计", info.alias)
        // 分组来自 Room，离线时不再一律显示「未分组」。
        assertEquals("客厅", info.group)
        assertEquals("Nordic", info.vendor)
        assertTrue(info.fromLocalCache)
        assertNull(info.unrecognizedDeviceTypeKey())
    }

    @Test
    fun `offline info falls back to model key with unrecognized marker`() {
        val device = Device(
            mac = "AA:BB:CC:DD:EE:FF",
            name = "未知设备",
            lastRssi = -70,
            lastSeenTimestamp = 1_762_300_000_000,
            deviceType = "nordic_uart",
            deviceTypeName = null,
        )

        val info = device.toOfflineDeviceInfo()

        assertNull(info.displayDeviceTypeName())
        // 只有型号键时由 UI 渲染「nordic_uart（未识别的型号）」。
        assertEquals("nordic_uart", info.unrecognizedDeviceTypeKey())
        assertEquals("", info.group)
    }

    @Test
    fun `offline info without any model renders unknown`() {
        val device = Device(
            mac = "AA:BB:CC:DD:EE:FF",
            name = "未知设备",
            lastRssi = -70,
            lastSeenTimestamp = 1_762_300_000_000,
        )

        val info = device.toOfflineDeviceInfo()

        assertNull(info.displayDeviceTypeName())
        assertNull(info.unrecognizedDeviceTypeKey())
        assertEquals("", info.group)
    }
}
