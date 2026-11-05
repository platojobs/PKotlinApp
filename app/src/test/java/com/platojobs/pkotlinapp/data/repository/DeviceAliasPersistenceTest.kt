package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.domain.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 别名脏标记的落库行为：
 * - 用户显式清空别名后，空串必须写入，且 aliasDirty 保持置位；
 * - 扫描只更新广播名，不得覆盖用户别名或置脏。
 */
class DeviceAliasPersistenceTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    @Test
    fun `explicit alias clear persists empty name and dirty flag`() = runBlocking {
        val dao = FakeDeviceDao()
        dao.insertOrUpdate(entity(name = "客厅温湿度计", advertisedName = "PJ-Sensor"))
        val repository = DeviceRepositoryImpl(dao)

        val existing = repository.getDevice(mac)!!
        repository.saveDevice(
            existing.copy(name = "", aliasDirty = true, updatedAt = 300),
        )

        val saved = dao.rows.getValue(mac)
        assertEquals("", saved.name)
        assertTrue(saved.aliasDirty)
        // 清空别名不影响广播名。
        assertEquals("PJ-Sensor", saved.advertisedName)
    }

    @Test
    fun `scan updates advertisedName without touching alias or dirty flag`() = runBlocking {
        val dao = FakeDeviceDao()
        dao.insertOrUpdate(entity(name = "客厅温湿度计", advertisedName = "PJ-Sensor-1"))
        val repository = DeviceRepositoryImpl(dao)

        repository.saveDevice(
            Device(
                mac = mac,
                // 扫描记录没有用户别名，也不带脏标记。
                advertisedName = "PJ-Sensor-2",
                lastRssi = -60,
                lastSeenTimestamp = 300,
            ),
        )

        val saved = dao.rows.getValue(mac)
        assertEquals("客厅温湿度计", saved.name)
        assertFalse(saved.aliasDirty)
        assertEquals("PJ-Sensor-2", saved.advertisedName)
    }

    @Test
    fun `dirty flag stays sticky across later scans`() = runBlocking {
        val dao = FakeDeviceDao()
        dao.insertOrUpdate(entity(name = "", advertisedName = "PJ-Sensor-1", aliasDirty = true))
        val repository = DeviceRepositoryImpl(dao)

        repository.saveDevice(
            Device(
                mac = mac,
                advertisedName = "PJ-Sensor-2",
                lastRssi = -70,
                lastSeenTimestamp = 300,
            ),
        )

        val saved = dao.rows.getValue(mac)
        assertEquals("", saved.name)
        assertTrue(saved.aliasDirty)
        assertEquals("PJ-Sensor-2", saved.advertisedName)
    }

    private fun entity(
        name: String,
        advertisedName: String?,
        aliasDirty: Boolean = false,
    ) = DeviceEntity(
        mac = mac,
        name = name,
        lastRssi = -55,
        lastSeenTimestamp = 200,
        id = "client-uuid",
        platform = "android",
        externalId = mac,
        updatedAt = 200,
        deletedAt = null,
        syncState = "synced",
        advertisedName = advertisedName,
        aliasDirty = aliasDirty,
    )

    private class FakeDeviceDao : DeviceDao {
        val rows = LinkedHashMap<String, DeviceEntity>()

        override suspend fun insertOrUpdate(device: DeviceEntity) {
            rows[device.mac] = device
        }

        override suspend fun getAllDevices(): List<DeviceEntity> =
            rows.values.filter { it.deletedAt == null }.sortedByDescending { it.lastSeenTimestamp }

        override fun observeDevices(): Flow<List<DeviceEntity>> = flowOf(getAllDevicesSync())

        override suspend fun find(mac: String): DeviceEntity? =
            rows[mac]?.takeIf { it.deletedAt == null }

        override fun observe(mac: String): Flow<DeviceEntity?> = flowOf(findSync(mac))

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

        override suspend fun queueRoomAssignment(
            mac: String,
            roomId: Long?,
            remoteRoomId: String?,
            pendingLocalId: Long?,
            householdRemoteId: String?,
        ) = Unit

        override suspend fun pendingRoomPatches(): List<DeviceEntity> = emptyList()

        override suspend fun finishRoomPatch(mac: String) = Unit

        override suspend fun applyServerMeta(
            mac: String,
            householdId: Long?,
            remoteRoomId: String?,
            roomId: Long?,
        ) = Unit

        override suspend fun sharedDevicesForHousehold(householdId: Long): List<DeviceEntity> =
            emptyList()

        override suspend fun deleteByMacs(macs: List<String>) = Unit

        override suspend fun deleteShared() = Unit

        override suspend fun clearHouseholdAssignments(householdId: Long) = Unit

        override suspend fun clearHouseholdLinks() = Unit

        override suspend fun tombstone(mac: String, timestamp: Long) {
            rows[mac]?.let {
                rows[mac] = it.copy(
                    deletedAt = timestamp,
                    updatedAt = timestamp,
                    syncState = "pending",
                )
            }
        }

        override suspend fun pendingDevices(limit: Int): List<DeviceEntity> =
            rows.values.filter { it.syncState == "pending" }
                .sortedBy { it.updatedAt }
                .take(limit)

        override fun observePendingCount(): Flow<Int> = flowOf(
            rows.values.count { it.syncState == "pending" },
        )

        override suspend fun pendingCount(): Int =
            rows.values.count { it.syncState == "pending" }

        override suspend fun markSyncedByExternalIds(externalIds: List<String>) {
            rows.values.filter { it.externalId in externalIds }.forEach {
                rows[it.mac] = it.copy(syncState = "synced")
            }
        }

        override suspend fun updateEnrich(
            externalId: String,
            deviceType: String?,
            vendor: String?,
            firmwareVersion: String?,
            compatibility: String?,
            deviceTypeName: String?,
            advertisedName: String?,
        ) {
            rows.values.filter { it.externalId == externalId }.forEach {
                rows[it.mac] = it.copy(
                    deviceType = deviceType,
                    vendor = vendor,
                    firmwareVersion = firmwareVersion,
                    compatibility = compatibility,
                    deviceTypeName = deviceTypeName ?: it.deviceTypeName,
                    advertisedName = advertisedName ?: it.advertisedName,
                )
            }
        }

        override suspend fun updateGroup(externalId: String, group: String?) {
            rows.values.filter { it.externalId == externalId }.forEach {
                rows[it.mac] = it.copy(group = group)
            }
        }

        override suspend fun markAllPending() {
            rows.values.forEach { rows[it.mac] = it.copy(syncState = "pending") }
        }

        override suspend fun deleteByExternalId(externalId: String) {
            rows.values.filter { it.externalId == externalId }.forEach { rows.remove(it.mac) }
        }

        private fun getAllDevicesSync(): List<DeviceEntity> =
            rows.values.filter { it.deletedAt == null }.sortedByDescending { it.lastSeenTimestamp }

        private fun findSync(mac: String): DeviceEntity? =
            rows[mac]?.takeIf { it.deletedAt == null }
    }
}
