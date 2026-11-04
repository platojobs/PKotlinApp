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
    fun `display name prefers alias then advertised name then model name`() {
        val device = Device(
            mac = "AA:BB:CC:DD:EE:FF",
            name = "",
            advertisedName = "PJ-Sensor",
            lastRssi = -55,
            lastSeenTimestamp = 1_762_300_000_000,
            deviceType = "nordic_uart",
            deviceTypeName = "Nordic UART 温湿度计",
        )

        // 没有用户别名时展示广播名，而不是型号名。
        assertEquals("PJ-Sensor", device.displayName())
        assertEquals("PJ-Sensor", device.toOfflineDeviceInfo().displayName())
        assertEquals("PJ-Sensor", device.toOfflineDeviceInfo().advertisedName)

        // 用户别名优先级最高。
        assertEquals("客厅开发板", device.copy(name = "客厅开发板").displayName())
        // 广播名缺失时回退 catalog 型号名。
        assertEquals("Nordic UART 温湿度计", device.copy(advertisedName = null).displayName())
        assertNull(
            device.copy(name = "", advertisedName = null, deviceTypeName = null).displayName(),
        )
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
