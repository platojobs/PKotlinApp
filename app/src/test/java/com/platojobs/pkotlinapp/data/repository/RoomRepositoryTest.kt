package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.data.local.RoomDao
import com.platojobs.pkotlinapp.data.local.RoomEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 本地房间：排序、重命名、删除后设备回到未分配。 */
class RoomRepositoryTest {

    @Test
    fun `create trims name and appends sort order`() = runBlocking {
        val roomDao = FakeRoomDao()
        val repository = RoomRepositoryImpl(roomDao, FakeDeviceDao())

        val first = repository.createRoom("  客厅  ")
        val second = repository.createRoom("卧室")

        assertEquals(1L, first)
        assertEquals(2L, second)
        val rooms = repository.getRooms()
        assertEquals(listOf("客厅", "卧室"), rooms.map { it.name })
        assertEquals(listOf(0, 1), rooms.map { it.sortOrder })
    }

    @Test
    fun `rename updates the stored name`() = runBlocking {
        val roomDao = FakeRoomDao()
        val repository = RoomRepositoryImpl(roomDao, FakeDeviceDao())
        val id = repository.createRoom("书房")

        repository.renameRoom(id, "  工作间  ")

        assertEquals("工作间", repository.getRooms().single().name)
    }

    @Test
    fun `delete room moves its devices back to unassigned`() = runBlocking {
        val deviceDao = FakeDeviceDao()
        val roomDao = FakeRoomDao()
        val rooms = RoomRepositoryImpl(roomDao, deviceDao)
        val devices = DeviceRepositoryImpl(deviceDao)
        val livingRoom = rooms.createRoom("客厅")
        val bedroom = rooms.createRoom("卧室")
        deviceDao.insertOrUpdate(device("AA:BB:CC:00:00:01", roomId = livingRoom))
        deviceDao.insertOrUpdate(device("AA:BB:CC:00:00:02", roomId = bedroom))
        deviceDao.insertOrUpdate(device("AA:BB:CC:00:00:03", roomId = null))

        rooms.deleteRoom(livingRoom)

        assertNull(deviceDao.rows.getValue("AA:BB:CC:00:00:01").roomId)
        assertEquals(bedroom, deviceDao.rows.getValue("AA:BB:CC:00:00:02").roomId)
        assertNull(deviceDao.rows.getValue("AA:BB:CC:00:00:03").roomId)
        assertEquals(listOf("卧室"), rooms.getRooms().map { it.name })
        assertNull(devices.getDevice("AA:BB:CC:00:00:01")?.roomId)
    }

    @Test
    fun `assign room keeps group and other device fields untouched`() = runBlocking {
        val deviceDao = FakeDeviceDao()
        val devices = DeviceRepositoryImpl(deviceDao)
        deviceDao.insertOrUpdate(device("AA:BB:CC:00:00:01", roomId = null).copy(group = "客厅"))

        devices.assignRoom("AA:BB:CC:00:00:01", 9L)

        val saved = deviceDao.rows.getValue("AA:BB:CC:00:00:01")
        assertEquals(9L, saved.roomId)
        assertEquals("客厅", saved.group)
    }

    private fun device(mac: String, roomId: Long?) = DeviceEntity(
        mac = mac,
        name = "设备",
        lastRssi = -60,
        lastSeenTimestamp = 1_762_300_000_000,
        id = mac,
        platform = "android",
        externalId = mac,
        updatedAt = 0,
        deletedAt = null,
        syncState = "synced",
        createdAt = 1_762_300_000_000,
        roomId = roomId,
    )

    private class FakeRoomDao : RoomDao {
        val rooms = LinkedHashMap<Long, RoomEntity>()
        private var nextId = 1L

        override fun observeRooms(): Flow<List<RoomEntity>> = flowOf(snapshot())

        override suspend fun getRooms(): List<RoomEntity> = snapshot()

        override suspend fun insert(room: RoomEntity): Long {
            val id = nextId++
            rooms[id] = room.copy(id = id)
            return id
        }

        override suspend fun rename(id: Long, name: String) {
            rooms[id]?.let { rooms[id] = it.copy(name = name) }
        }

        override suspend fun delete(id: Long) {
            rooms.remove(id)
        }

        private fun snapshot(): List<RoomEntity> = rooms.values.sortedWith(
            compareBy({ it.sortOrder }, { it.createdAt }, { it.id }),
        )
    }

    private class FakeDeviceDao : DeviceDao {
        val rows = LinkedHashMap<String, DeviceEntity>()

        override suspend fun insertOrUpdate(device: DeviceEntity) {
            rows[device.mac] = device
        }

        override suspend fun getAllDevices(): List<DeviceEntity> = rows.values.toList()

        override fun observeDevices(): Flow<List<DeviceEntity>> = flowOf(rows.values.toList())

        override suspend fun find(mac: String): DeviceEntity? = rows[mac]

        override fun observe(mac: String): Flow<DeviceEntity?> = flowOf(rows[mac])

        override suspend fun findAny(mac: String): DeviceEntity? = rows[mac]

        override suspend fun findByExternalId(externalId: String): DeviceEntity? =
            rows.values.firstOrNull { it.externalId == externalId }

        override suspend fun delete(mac: String) {
            rows.remove(mac)
        }

        override suspend fun assignRoom(mac: String, roomId: Long?) {
            rows[mac]?.let { rows[mac] = it.copy(roomId = roomId) }
        }

        override suspend fun clearRoomAssignments(roomId: Long) {
            rows.values.filter { it.roomId == roomId }.forEach {
                rows[it.mac] = it.copy(roomId = null)
            }
        }

        override suspend fun tombstone(mac: String, timestamp: Long) {
            rows[mac]?.let { rows[mac] = it.copy(deletedAt = timestamp) }
        }

        override suspend fun pendingDevices(limit: Int): List<DeviceEntity> =
            rows.values.filter { it.syncState == "pending" }.take(limit)

        override fun observePendingCount(): Flow<Int> =
            flowOf(rows.values.count { it.syncState == "pending" })

        override suspend fun pendingCount(): Int =
            rows.values.count { it.syncState == "pending" }

        override suspend fun markSyncedByExternalIds(externalIds: List<String>) = Unit

        override suspend fun updateEnrich(
            externalId: String,
            deviceType: String?,
            vendor: String?,
            firmwareVersion: String?,
            compatibility: String?,
            deviceTypeName: String?,
            advertisedName: String?,
        ) = Unit

        override suspend fun updateGroup(externalId: String, group: String?) = Unit

        override suspend fun markAllPending() = Unit

        override suspend fun deleteByExternalId(externalId: String) = Unit
    }
}
