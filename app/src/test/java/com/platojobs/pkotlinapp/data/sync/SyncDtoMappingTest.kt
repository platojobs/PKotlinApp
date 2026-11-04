package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.remote.PushDeviceDto
import com.platojobs.pkotlinapp.data.remote.Reported
import com.platojobs.pkotlinapp.data.remote.SyncDeviceDto
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
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
        assertEquals("BLE Tracker", dto.alias?.value)
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
        assertEquals("iOS Tracker", dto.alias?.value)
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
    fun `group tri-state is mapped to absent empty or value`() {
        val base = SyncRecord(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            name = "BLE Tracker",
            lastRssi = -55,
            lastSeenTimestamp = 1_762_300_000_000,
            updatedAt = 1_762_300_000_000,
        )

        // null = 未上报（absent），不能序列化成空值把服务端分组清掉。
        assertNull(base.toPushDto().group)
        // "" = 显式清除。
        assertEquals("", base.copy(group = "").toPushDto().group?.value)
        // 非空 = 覆盖。
        assertEquals("客厅", base.copy(group = "客厅").toPushDto().group?.value)
    }

    @Test
    fun `scan only reports advertisedName and never overwrites alias`() {
        val scanned = SyncRecord(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            // 扫描写入的记录没有用户别名。
            name = "",
            advertisedName = "PJ-Sensor",
            lastRssi = -55,
            lastSeenTimestamp = 1_762_300_000_000,
            updatedAt = 1_762_300_000_000,
        )

        val dto = scanned.toPushDto()
        assertNull(dto.alias)
        assertEquals("PJ-Sensor", dto.advertisedName?.value)

        // 用户编辑别名后，两条字段仍各自保留自己的值，不会互相覆盖。
        val userEdit = scanned.copy(name = "客厅开发板")
        val editDto = userEdit.toPushDto()
        assertEquals("客厅开发板", editDto.alias?.value)
        assertEquals("PJ-Sensor", editDto.advertisedName?.value)
    }

    @Test
    fun `push json omits absent fields and keeps explicit empty group`() {
        val adapter = ApiClient.moshi().adapter(SyncPushRequest::class.java)
        val base = PushDeviceDto(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            updatedAt = Rfc3339.format(1_762_300_000_000),
        )

        val absentRequest = SyncPushRequest(
            "test",
            listOf(base.copy(alias = null, advertisedName = null, group = null)),
        )
        val absentJson = adapter.toJson(absentRequest)
        assertFalse(absentJson.contains("\"alias\""))
        assertFalse(absentJson.contains("\"advertisedName\""))
        assertFalse(absentJson.contains("\"group\""))
        assertFalse(absentJson.contains("\"deviceType\""))

        val clearJson = adapter.toJson(
            SyncPushRequest("test", listOf(base.copy(group = Reported(""))))
        )
        assertTrue(clearJson.contains("\"group\":\"\""))

        val aliasJson = adapter.toJson(
            SyncPushRequest("test", listOf(base.copy(alias = Reported("客厅开发板"))))
        )
        assertTrue(aliasJson.contains("\"alias\":\"客厅开发板\""))
        assertFalse(aliasJson.contains("\"advertisedName\""))
        assertFalse(aliasJson.contains("\"group\""))
    }

    @Test
    fun `pulled device keeps group and parses absent optional fields`() {
        val record = SyncDeviceDto(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            alias = "用户别名",
            advertisedName = "PJ-Sensor",
            group = Reported("书房"),
        ).toDomain()

        assertEquals("书房", record?.group)
        assertEquals("用户别名", record?.name)
        assertEquals("PJ-Sensor", record?.advertisedName)

        // 冲突响应中可缺省字段会省略：解析为 absent，而不是空串。
        val conflictServer = SyncDeviceDto(
            id = UUID.randomUUID().toString(),
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            updatedAt = Rfc3339.format(1_762_300_000_000),
        ).toDomain()

        assertNull(conflictServer?.group)
        assertNull(conflictServer?.advertisedName)
        assertEquals("", conflictServer?.name)

        // 显式空串仍能区分出来。
        val explicitlyCleared = SyncDeviceDto(
            platform = PLATFORM_ANDROID,
            externalId = "AA:BB:CC:DD:EE:FF",
            group = Reported(""),
        ).toDomain()
        assertEquals("", explicitlyCleared?.group)
    }
}
