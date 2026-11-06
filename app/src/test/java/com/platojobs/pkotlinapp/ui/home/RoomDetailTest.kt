package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.reading.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间详情完全由本机 Room 缓存的设备与最新读数聚合；这里验证计数、在线阈值与读数归属，
 * 防止界面出现与首页不一致的「假统计」。
 */
class RoomDetailTest {

    private val room = Room(id = 5, name = "客厅")

    private fun device(
        mac: String,
        name: String = "",
        lastSeen: Long = ONLINE_NOW,
        roomId: Long? = 5,
        markedAt: Long? = null,
        advertisedName: String? = null,
        syncState: SyncState = SyncState.SYNCED,
    ) = Device(
        mac = mac,
        name = name,
        lastRssi = -60,
        lastSeenTimestamp = lastSeen,
        roomId = roomId,
        markedAt = markedAt,
        advertisedName = advertisedName,
        syncState = syncState,
    )

    private fun reading(mac: String, value: Double = 26.5): Reading = Reading(
        id = "r-$mac",
        deviceId = mac,
        metric = "temperature",
        value = value,
        unit = "℃",
        recordedAt = ONLINE_NOW,
    )

    @Test
    fun `only devices in the room are aggregated`() {
        val devices = listOf(
            device("aa:bb", lastSeen = ONLINE_NOW + 1),
            device("cc:dd", roomId = 6),
            device("ee:ff", roomId = null),
        )

        val detail = RoomDetailBuilder.build(room, devices, emptyMap(), ONLINE_SINCE, "未知设备")

        assertEquals(5L, detail.roomId)
        assertEquals("客厅", detail.roomName)
        assertEquals(1, detail.total)
        assertEquals(listOf("aa:bb"), detail.allMacs)
        assertFalse(detail.isEmpty)
    }

    @Test
    fun `online boundary matches the home page threshold`() {
        val devices = listOf(
            device("online", lastSeen = ONLINE_SINCE),
            device("offline", lastSeen = ONLINE_SINCE - 1),
        )

        val detail = RoomDetailBuilder.build(room, devices, emptyMap(), ONLINE_SINCE, "未知设备")

        assertEquals(2, detail.total)
        assertEquals(1, detail.onlineCount)
        assertEquals(1, detail.offlineCount)
        assertEquals(listOf(true, false), detail.entries.map { it.online })
    }

    @Test
    fun `latest reading is attached by mac and missing devices have none`() {
        val devices = listOf(device("aa:bb"), device("cc:dd"))
        val readings = mapOf("aa:bb" to reading("aa:bb"))

        val detail = RoomDetailBuilder.build(room, devices, readings, ONLINE_SINCE, "未知设备")

        assertEquals(26.5, detail.entries[0].reading?.value)
        assertEquals("℃", detail.entries[0].reading?.unit)
        assertNull(detail.entries[1].reading)
    }

    @Test
    fun `marked devices are counted only when markedAt is present`() {
        val devices = listOf(
            device("a", markedAt = 1L),
            device("b", markedAt = null),
            device("c", markedAt = 0L),
        )

        val detail = RoomDetailBuilder.build(room, devices, emptyMap(), ONLINE_SINCE, "未知设备")

        assertEquals(2, detail.markedCount)
        assertEquals(listOf(true, false, true), detail.entries.map { it.marked })
    }

    @Test
    fun `pending marks are counted separately from synced marks`() {
        val devices = listOf(
            device("a", markedAt = 1L, syncState = SyncState.PENDING),
            device("b", markedAt = 1L),
            device("c", syncState = SyncState.PENDING),
        )

        val detail = RoomDetailBuilder.build(room, devices, emptyMap(), ONLINE_SINCE, "未知设备")

        assertEquals(1, detail.pendingMarkCount)
        assertEquals(2, detail.markedCount)
    }

    @Test
    fun `display name falls back to advertised name before unknown`() {
        val devices = listOf(
            device("a", name = "客厅灯"),
            device("b", advertisedName = "Living-Room"),
            device("c"),
        )

        val detail = RoomDetailBuilder.build(room, devices, emptyMap(), ONLINE_SINCE, "未知设备")

        assertEquals(listOf("客厅灯", "Living-Room", "未知设备"), detail.entries.map { it.name })
    }

    @Test
    fun `empty room reports empty and no batch targets`() {
        val detail = RoomDetailBuilder.build(room, emptyList(), emptyMap(), ONLINE_SINCE, "未知设备")

        assertTrue(detail.isEmpty)
        assertEquals(0, detail.total)
        assertTrue(detail.allMacs.isEmpty())
        assertTrue(detail.entries.isEmpty())
    }

    private companion object {
        const val ONLINE_NOW = 10_000L
        const val ONLINE_SINCE = 9_000L
    }
}
