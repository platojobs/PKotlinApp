package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceLinkStatus
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.model.cloudReadingKey
import com.platojobs.pkotlinapp.domain.reading.CloudReading
import com.platojobs.pkotlinapp.domain.reading.CloudReadingState
import com.platojobs.pkotlinapp.domain.reading.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间详情由本机设备缓存聚合：
 * 状态本机连接优先，其次服务端 presence（15 分钟窗口），unknown 单独一档；
 * 读数本机优先，缺失时才展示云端四态。
 */
class RoomDetailTest {

    private val room = Room(id = 5, name = "客厅")

    private fun device(
        mac: String,
        name: String = "",
        roomId: Long? = 5,
        markedAt: Long? = null,
        advertisedName: String? = null,
        syncState: SyncState = SyncState.SYNCED,
        platform: String = PLATFORM_ANDROID,
        externalId: String = "",
        lastSeenAt: Long? = null,
    ) = Device(
        mac = mac,
        name = name,
        lastRssi = -60,
        lastSeenTimestamp = NOW,
        roomId = roomId,
        markedAt = markedAt,
        advertisedName = advertisedName,
        syncState = syncState,
        platform = platform,
        externalId = externalId,
        lastSeenAt = lastSeenAt,
    )

    private fun reading(mac: String, value: Double = 26.5): Reading = Reading(
        id = "r-$mac",
        deviceId = mac,
        metric = "temperature",
        value = value,
        unit = "℃",
        recordedAt = NOW,
    )

    private fun cloud(value: Double = 24.0): CloudReadingState.Available =
        CloudReadingState.Available(
            CloudReading(
                metric = "temperature",
                value = value,
                unit = "℃",
                recordedAt = CLOUD_AT,
            ),
        )

    @Test
    fun `only devices in the room are aggregated`() {
        val devices = listOf(
            device("aa:bb"),
            device("cc:dd", roomId = 6),
            device("ee:ff", roomId = null),
        )

        val detail = RoomDetailBuilder.build(
            room, devices, emptyMap(), emptyMap(), CONNECTED_MAC, "未知设备",
        )

        assertEquals(5L, detail.roomId)
        assertEquals("客厅", detail.roomName)
        assertEquals(1, detail.total)
        assertEquals(listOf("aa:bb"), detail.allMacs)
        assertFalse(detail.isEmpty)
    }

    @Test
    fun `status prefers local connection then server presence`() {
        val devices = listOf(
            // 本机已连接：即使服务端最近上报已超时，也以本机连接为准。
            device(CONNECTED_MAC, lastSeenAt = NOW - 10 * 60_000L),
            device("recent", lastSeenAt = NOW - 60_000L),
            device("stale", lastSeenAt = NOW - 16 * 60_000L),
            device("never", lastSeenAt = null),
        )

        val detail = RoomDetailBuilder.build(
            room, devices, emptyMap(), emptyMap(), CONNECTED_MAC, "未知设备", NOW,
        )

        assertEquals(4, detail.total)
        assertEquals(1, detail.localConnectedCount)
        assertEquals(1, detail.cloudRecentCount)
        assertEquals(1, detail.cloudStaleCount)
        assertEquals(1, detail.cloudUnknownCount)
        assertEquals(
            listOf(
                DeviceLinkStatus.LOCAL_CONNECTED,
                DeviceLinkStatus.CLOUD_RECENT,
                DeviceLinkStatus.CLOUD_STALE,
                DeviceLinkStatus.CLOUD_UNKNOWN,
            ),
            detail.entries.map { it.status },
        )
        assertEquals(
            listOf(NOW - 10 * 60_000L, NOW - 60_000L, NOW - 16 * 60_000L, null),
            detail.entries.map { it.lastSeenAt },
        )
    }

    @Test
    fun `presence window boundary is inclusive at 900 seconds`() {
        val devices = listOf(
            device("edge", lastSeenAt = NOW - 900_000L),
            device("over", lastSeenAt = NOW - 900_001L),
        )

        val detail = RoomDetailBuilder.build(
            room, devices, emptyMap(), emptyMap(), null, "未知设备", NOW,
        )

        assertEquals(DeviceLinkStatus.CLOUD_RECENT, detail.entries[0].status)
        assertEquals(DeviceLinkStatus.CLOUD_STALE, detail.entries[1].status)
    }

    @Test
    fun `latest local reading is attached by mac and missing devices have none`() {
        val devices = listOf(device("aa:bb"), device("cc:dd"))
        val readings = mapOf("aa:bb" to reading("aa:bb"))

        val detail = RoomDetailBuilder.build(
            room, devices, readings, emptyMap(), null, "未知设备",
        )

        assertEquals(26.5, detail.entries[0].reading?.value)
        assertEquals("℃", detail.entries[0].reading?.unit)
        assertNull(detail.entries[1].reading)
    }

    @Test
    fun `local reading wins over cloud state`() {
        val device = device("aa:bb")
        val detail = RoomDetailBuilder.build(
            room = room,
            devices = listOf(device),
            readings = mapOf("aa:bb" to reading("aa:bb")),
            cloudReadings = mapOf(device.cloudReadingKey to cloud(30.0)),
            connectedMac = null,
            unknownName = "未知设备",
        )

        assertEquals(26.5, detail.entries[0].reading?.value)
        assertNull(detail.entries[0].cloudReading)
    }

    @Test
    fun `cloud states are preserved one to one when local reading is missing`() {
        val states = listOf(
            "a" to cloud(24.0),
            "b" to CloudReadingState.Loading,
            "c" to CloudReadingState.Empty,
            "d" to CloudReadingState.Failed,
        )
        val devices = states.map { (mac) -> device(mac) }
        val cloudReadings = states.associate { (mac, state) ->
            devices.first { it.mac == mac }.cloudReadingKey to state
        }

        val detail = RoomDetailBuilder.build(
            room, devices, emptyMap(), cloudReadings, null, "未知设备",
        )

        assertEquals(states.map { it.second }, detail.entries.map { it.cloudReading })
        // 失败必须保持失败，不能被降级成「暂无读数」或离线。
        assertEquals(CloudReadingState.Failed, detail.entries[3].cloudReading)
    }

    @Test
    fun `marked devices are counted only when markedAt is present`() {
        val devices = listOf(
            device("a", markedAt = 1L),
            device("b", markedAt = null),
            device("c", markedAt = 0L),
        )

        val detail = RoomDetailBuilder.build(
            room, devices, emptyMap(), emptyMap(), null, "未知设备",
        )

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

        val detail = RoomDetailBuilder.build(
            room, devices, emptyMap(), emptyMap(), null, "未知设备",
        )

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

        val detail = RoomDetailBuilder.build(
            room, devices, emptyMap(), emptyMap(), null, "未知设备",
        )

        assertEquals(listOf("客厅灯", "Living-Room", "未知设备"), detail.entries.map { it.name })
    }

    @Test
    fun `empty room reports empty and no batch targets`() {
        val detail = RoomDetailBuilder.build(
            room, emptyList(), emptyMap(), emptyMap(), null, "未知设备",
        )

        assertTrue(detail.isEmpty)
        assertEquals(0, detail.total)
        assertTrue(detail.allMacs.isEmpty())
        assertTrue(detail.entries.isEmpty())
    }

    private companion object {
        const val NOW = 10_000L
        const val CLOUD_AT = 20_000L
        const val CONNECTED_MAC = "aa:bb"
    }
}
