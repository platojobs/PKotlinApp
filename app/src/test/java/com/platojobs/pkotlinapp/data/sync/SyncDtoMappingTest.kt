package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.SyncDeviceDto
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import com.platojobs.pkotlinapp.domain.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SyncDtoMappingTest {

    @Test
    fun `android record maps to openapi push device`() {
        val record = SyncRecord(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "aa:bb:cc:dd:ee:ff",
            name = "BLE Tracker",
            lastRssi = -55,
            lastSeenTimestamp = 1_762_300_000_000,
            updatedAt = 1_762_300_000_000,
        )

        val dto = record.toPushDto()

        assertEquals("AA:BB:CC:DD:EE:FF", dto.externalId)
        assertEquals("aa:bb:cc:dd:ee:ff", dto.mac)
        assertEquals("BLE Tracker", dto.alias)
        assertEquals("android", dto.platform)
        assertFalse(dto.deleted)
        assertEquals(1, dto.scanRecords.size)
        assertEquals(-55, dto.scanRecords.single().rssi)
        assertEquals(Rfc3339.format(record.updatedAt), dto.updatedAt)
        assertEquals(Rfc3339.format(record.lastSeenTimestamp), dto.scanRecords.single().at)
    }

    @Test
    fun `ios record has no mac and uppercase external id`() {
        val record = SyncRecord(
            id = UUID.randomUUID().toString(),
            platform = "ios",
            externalId = "e621e1f8-c36c-495a-93fc-0c247a3e6e5f",
            name = "iOS Tracker",
            lastRssi = 0,
            lastSeenTimestamp = 1_762_300_000_000,
            updatedAt = 1_762_300_000_000,
        )

        val dto = record.toPushDto()

        assertEquals("ios", dto.platform)
        assertEquals("E621E1F8-C36C-495A-93FC-0C247A3E6E5F", dto.externalId)
        assertNull(dto.mac)
        assertEquals("iOS Tracker", dto.alias)
    }

    @Test
    fun `tombstone is pushed as deleted`() {
        val record = SyncRecord(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            name = "BLE Tracker",
            lastRssi = -55,
            lastSeenTimestamp = 1_762_300_000_000,
            updatedAt = 1_762_300_001_000,
            deletedAt = 1_762_300_002_000,
        )

        val dto = record.toPushDto()

        assertTrue(dto.deleted)
        assertEquals(Rfc3339.format(record.updatedAt), dto.updatedAt)
    }

    @Test
    fun `invalid local id falls back to deterministic uuid`() {
        val record = SyncRecord(
            id = "legacy-mac-row",
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            name = "BLE Tracker",
            lastRssi = -55,
            lastSeenTimestamp = 1_762_300_000_000,
            updatedAt = 1_762_300_000_000,
        )

        val first = record.toPushDto().id
        val second = record.toPushDto().id

        assertEquals(first, second)
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test
    fun `group is pushed only when locally known`() {
        val record = SyncRecord(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            name = "BLE Tracker",
            lastRssi = -55,
            lastSeenTimestamp = 1_762_300_000_000,
            updatedAt = 1_762_300_000_000,
            group = "客厅",
        )

        assertEquals("客厅", record.toPushDto().group)

        val ungrouped = record.copy(group = "  ")
        // 空分组不上报，避免把服务端已有分组覆盖成空。
        assertNull(ungrouped.toPushDto().group)
    }

    @Test
    fun `pulled device keeps group for offline display`() {
        val record = SyncDeviceDto(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            group = "书房",
        ).toDomain()

        assertEquals("书房", record?.group)
    }
}
