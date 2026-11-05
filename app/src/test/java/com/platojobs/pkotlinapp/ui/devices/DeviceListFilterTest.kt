package com.platojobs.pkotlinapp.ui.devices

import com.platojobs.pkotlinapp.data.prefs.DeviceSortMode
import com.platojobs.pkotlinapp.domain.model.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceListFilterTest {

    private fun device(
        mac: String,
        name: String = "",
        advertisedName: String? = null,
        typeName: String? = null,
        rssi: Int = -60,
        seen: Long = 0L,
        group: String? = null,
        added: Long = 0L,
    ) = Device(
        mac = mac,
        name = name,
        lastRssi = rssi,
        lastSeenTimestamp = seen,
        advertisedName = advertisedName,
        deviceTypeName = typeName,
        group = group,
        createdAt = added,
    )

    private val living = device(
        mac = "AA:BB:CC:11:22:33",
        name = "客厅温度计",
        advertisedName = "Living Sensor",
        typeName = "TH-01",
        rssi = -55,
        seen = 300,
        group = "客厅",
        added = 100,
    )
    private val bedroom = device(
        mac = "DD:EE:FF:44:55:66",
        name = "",
        advertisedName = "Bedroom Lamp",
        typeName = "LAMP-2",
        rssi = -85,
        seen = 500,
        group = "卧室",
        added = 300,
    )
    private val ungrouped = device(
        mac = "11:22:33:AA:BB:CC",
        name = "备用机",
        rssi = -70,
        seen = 100,
        group = null,
        added = 200,
    )

    private val all = listOf(living, bedroom, ungrouped)

    @Test
    fun `搜索按别名广播名型号与 MAC 模糊匹配且大小写不敏感`() {
        assertTrue(DeviceListFiltering.matches(living, "客厅"))
        assertTrue(DeviceListFiltering.matches(living, "living sensor"))
        assertTrue(DeviceListFiltering.matches(living, "TH-01"))
        assertTrue(DeviceListFiltering.matches(living, "aa:bb"))
        assertTrue(DeviceListFiltering.matches(living, "aabbcc112233"))
        assertFalse(DeviceListFiltering.matches(living, "卧室"))
    }

    @Test
    fun `默认按最近出现倒序`() {
        val sorted = DeviceListFiltering.apply(all, DeviceListFilter())
        assertEquals(listOf("DD:EE:FF:44:55:66", "AA:BB:CC:11:22:33", "11:22:33:AA:BB:CC"), sorted.map { it.mac })
    }

    @Test
    fun `按名称排序时无展示名的设备排最后`() {
        val unnamed = device(mac = "FF:00:00:00:00:01", name = "")
        val sorted = DeviceListFiltering.apply(
            all + unnamed,
            DeviceListFilter(sort = DeviceSortMode.NAME),
        )
        assertEquals(
            listOf("DD:EE:FF:44:55:66", "11:22:33:AA:BB:CC", "AA:BB:CC:11:22:33", "FF:00:00:00:00:01"),
            sorted.map { it.mac },
        )
    }

    @Test
    fun `按信号强度从强到弱`() {
        val sorted = DeviceListFiltering.apply(all, DeviceListFilter(sort = DeviceSortMode.RSSI))
        assertEquals(listOf(-55, -70, -85), sorted.map { it.lastRssi })
    }

    @Test
    fun `按添加时间倒序`() {
        val sorted = DeviceListFiltering.apply(all, DeviceListFilter(sort = DeviceSortMode.ADDED))
        assertEquals(listOf(300L, 200L, 100L), sorted.map { it.createdAt })
    }

    @Test
    fun `分组选项来自真实分组并追加未分组`() {
        assertEquals(
            listOf("卧室", "客厅", DeviceListFiltering.GROUP_UNGROUPED),
            DeviceListFiltering.groupOptions(all),
        )
    }

    @Test
    fun `按真实分组与未分组筛选`() {
        val livingOnly = DeviceListFiltering.apply(
            all,
            DeviceListFilter(group = "客厅"),
        )
        assertEquals(listOf("AA:BB:CC:11:22:33"), livingOnly.map { it.mac })

        val ungroupedOnly = DeviceListFiltering.apply(
            all,
            DeviceListFilter(group = DeviceListFiltering.GROUP_UNGROUPED),
        )
        assertEquals(listOf("11:22:33:AA:BB:CC"), ungroupedOnly.map { it.mac })
    }

    @Test
    fun `筛选无结果时源列表仍非空`() {
        val filtered = DeviceListFiltering.apply(all, DeviceListFilter(search = "不存在的设备"))
        assertTrue(filtered.isEmpty())
        assertTrue(all.isNotEmpty())
        assertTrue(DeviceListFilter(search = "不存在的设备").isActive)
        assertFalse(DeviceListFilter().isActive)
    }
}
