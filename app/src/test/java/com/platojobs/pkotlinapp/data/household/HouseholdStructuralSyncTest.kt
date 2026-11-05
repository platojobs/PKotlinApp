package com.platojobs.pkotlinapp.data.household

import androidx.room.Room
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.data.sync.SyncPrefs
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitation
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitationInfo
import com.platojobs.pkotlinapp.domain.household.HouseholdMember
import com.platojobs.pkotlinapp.domain.household.HouseholdRemote
import com.platojobs.pkotlinapp.domain.household.HouseholdRole
import com.platojobs.pkotlinapp.domain.household.HouseholdRoom
import com.platojobs.pkotlinapp.domain.household.HouseholdSnapshot
import com.platojobs.pkotlinapp.domain.household.RemoteDeviceRef
import com.platojobs.pkotlinapp.domain.sync.StructuralChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * change_log 结构增量同步（后端 v0.15.0）的本机落库行为：
 * 家庭改名/删除、房间 upsert/delete、缺家庭触发全量基线，以及本地 pending 优先。
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class HouseholdStructuralSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var remote: FakeStructuralRemote
    private lateinit var repo: HouseholdRepository
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = SyncPrefs(context)
        prefs.currentHouseholdRemoteId = FAMILY
        remote = FakeStructuralRemote()
        repo = HouseholdRepository(
            householdDao = db.householdDao(),
            roomDao = db.roomDao(),
            deviceDao = db.deviceDao(),
            remote = remote,
            prefs = prefs,
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `household upsert updates local name role and member count`() = runBlocking {
        seedFamily()
        assertEquals(2, db.householdDao().findByRemoteId(FAMILY)?.memberCount)

        repo.applyStructural(
            listOf(
                StructuralChange.Household(
                    householdRemoteId = FAMILY,
                    name = "改名后的家",
                    ownerUserId = "user-2",
                    role = HouseholdRole.MEMBER,
                    memberCount = 1,
                    deleted = false,
                ),
            ),
        )

        val row = requireNotNull(db.householdDao().findByRemoteId(FAMILY))
        assertEquals("改名后的家", row.name)
        assertEquals("member", row.role)
        assertEquals(1, row.memberCount)
        // 已有基线时不应额外打一次 GET /households。
        assertEquals(0, remote.baselineCalls)
    }

    @Test
    fun `household upsert for unknown family pulls a full baseline`() = runBlocking {
        // 本地完全没有该家庭（模拟刚被邀请加入，后端不回放历史）。
        remote.householdRows[FAMILY] = familySnapshot(members = 2)
        remote.rooms[FAMILY] = mutableListOf(HouseholdRoom("r-1", FAMILY, "客厅", 0, NOW))

        repo.applyStructural(
            listOf(
                StructuralChange.Household(
                    householdRemoteId = FAMILY,
                    name = "全家",
                    ownerUserId = "user-1",
                    role = HouseholdRole.MEMBER,
                    memberCount = 2,
                    deleted = false,
                ),
            ),
        )

        assertEquals(1, remote.baselineCalls)
        assertNotNull(db.householdDao().findByRemoteId(FAMILY))
        assertEquals("客厅", db.roomDao().findByRemoteId("r-1")?.name)
    }

    @Test
    fun `household delete removes rooms shared devices and assignments in one pass`() = runBlocking {
        seedFamily()
        val familyLocalId = requireNotNull(db.householdDao().findByRemoteId(FAMILY)).id
        val roomLocalId = requireNotNull(db.roomDao().findByRemoteId("r-1")).id
        db.deviceDao().insertOrUpdate(
            ownedDevice(mac = OWNED_MAC, roomId = roomLocalId, householdId = familyLocalId),
        )
        assertNotNull(db.deviceDao().find(SHARED_MAC))

        repo.applyStructural(
            listOf(
                StructuralChange.Household(
                    householdRemoteId = FAMILY,
                    name = "全家",
                    ownerUserId = "user-1",
                    role = HouseholdRole.OWNER,
                    memberCount = 2,
                    deleted = true,
                ),
            ),
        )

        assertNull(db.householdDao().findByRemoteId(FAMILY))
        assertTrue(db.roomDao().getRoomsForHousehold(familyLocalId).isEmpty())
        assertNull(db.deviceDao().find(SHARED_MAC))
        // 自己的设备保留，只清空家庭/房间归属。
        val owned = requireNotNull(db.deviceDao().find(OWNED_MAC))
        assertNull(owned.householdId)
        assertNull(owned.roomId)
        assertNull(owned.remoteRoomId)
    }

    @Test
    fun `room upsert inserts and then applies server name and order`() = runBlocking {
        seedFamily()

        repo.applyStructural(
            listOf(roomUpsert("r-3", "书房", 2)),
        )
        assertEquals("书房", db.roomDao().findByRemoteId("r-3")?.name)

        repo.applyStructural(
            listOf(roomUpsert("r-1", "大客厅", 5)),
        )
        val renamed = requireNotNull(db.roomDao().findByRemoteId("r-1"))
        assertEquals("大客厅", renamed.name)
        assertEquals(5, renamed.sortOrder)
    }

    @Test
    fun `room delete clears device ownership but keeps the device`() = runBlocking {
        seedFamily()
        assertNotNull(db.deviceDao().find(SHARED_MAC))

        repo.applyStructural(
            listOf(
                StructuralChange.Room(
                    roomRemoteId = "r-1",
                    householdRemoteId = FAMILY,
                    name = "客厅",
                    sortOrder = 0,
                    deleted = true,
                ),
            ),
        )

        assertNull(db.roomDao().findByRemoteId("r-1"))
        // 共享设备本身不随房间删除：它仍在设备列表里，只是回到未分配。
        assertNotNull(db.deviceDao().find(SHARED_MAC))
        assertNull(db.deviceDao().find(SHARED_MAC)?.roomId)
        assertNull(db.deviceDao().find(SHARED_MAC)?.remoteRoomId)
    }

    @Test
    fun `local pending room edit wins and server value is staged for next merge`() = runBlocking {
        seedFamily()
        val local = requireNotNull(db.roomDao().findByRemoteId("r-1"))
        db.roomDao().rename(local.id, "本地改名")
        db.roomDao().markPendingEdit(local.id)

        repo.applyStructural(
            listOf(roomUpsert("r-1", "服务端改名", 7)),
        )

        val row = requireNotNull(db.roomDao().findByRemoteId("r-1"))
        assertEquals("本地改名", row.name)
        assertEquals(local.sortOrder, row.sortOrder)
        assertTrue(row.pendingEdit)
        // 服务端值已落库，等本地 pending 合并后再采用。
        assertEquals("服务端改名", row.serverName)
        assertEquals(7, row.serverSortOrder)
    }

    @Test
    fun `removed member stops receiving structural changes without purging history`() = runBlocking {
        seedFamily()
        // 离开前已同步的历史房间行（m-1）属于正常历史数据，不应被当作脏数据清理。
        val familyLocalId = requireNotNull(db.householdDao().findByRemoteId(FAMILY)).id
        db.roomDao().insert(
            com.platojobs.pkotlinapp.data.local.RoomEntity(
                name = "历史房间",
                sortOrder = 9,
                createdAt = NOW,
                remoteId = "m-1",
                householdId = familyLocalId,
            ),
        )

        // 成员被移除后，服务端不再推送该家庭的结构变更：本批为空。
        repo.applyStructural(emptyList())

        assertNotNull(db.roomDao().findByRemoteId("m-1"))
        assertFalse(db.roomDao().getRoomsForHousehold(familyLocalId).isEmpty())
    }

    private suspend fun seedFamily() {
        remote.householdRows[PERSONAL] = personalSnapshot()
        remote.householdRows[FAMILY] = familySnapshot(members = 2)
        remote.rooms[FAMILY] = mutableListOf(
            HouseholdRoom("r-1", FAMILY, "客厅", 0, NOW),
            HouseholdRoom("r-2", FAMILY, "卧室", 1, NOW + 1),
        )
        remote.devices += RemoteDeviceRef(
            platform = "android",
            externalId = MEMBER_DEVICE,
            householdRemoteId = FAMILY,
            roomRemoteId = "r-1",
            alias = "成员的温湿度计",
            updatedAt = NOW,
        )
        repo.syncAll()
        // 基线拉取发生在 seed 阶段；本测试只统计 applyStructural 触发的额外基线。
        remote.baselineCalls = 0
    }

    private fun personalSnapshot() = HouseholdSnapshot(
        remoteId = PERSONAL,
        name = "我的家",
        isPersonal = true,
        role = HouseholdRole.OWNER,
        memberCount = 1,
        deviceCount = 0,
        sortOrder = 0,
        createdAt = NOW,
    )

    private fun familySnapshot(members: Int) = HouseholdSnapshot(
        remoteId = FAMILY,
        name = "全家",
        isPersonal = false,
        role = HouseholdRole.OWNER,
        memberCount = members,
        deviceCount = 1,
        sortOrder = 1,
        createdAt = NOW,
        roomCount = 2,
    )

    private fun ownedDevice(mac: String, roomId: Long, householdId: Long) = DeviceEntity(
        mac = mac,
        platform = "android",
        name = "我的设备",
        lastRssi = -50,
        lastSeenTimestamp = NOW,
        id = mac,
        externalId = mac,
        updatedAt = NOW,
        deletedAt = null,
        syncState = "synced",
        createdAt = NOW,
        roomId = roomId,
        householdId = householdId,
        remoteRoomId = "r-1",
    )

    private fun roomUpsert(remoteId: String, name: String, sortOrder: Int) = StructuralChange.Room(
        roomRemoteId = remoteId,
        householdRemoteId = FAMILY,
        name = name,
        sortOrder = sortOrder,
        deleted = false,
    )

    private class FakeStructuralRemote : HouseholdRemote {
        val householdRows = mutableMapOf<String, HouseholdSnapshot>()
        val rooms = mutableMapOf<String, MutableList<HouseholdRoom>>()
        val devices = mutableListOf<RemoteDeviceRef>()
        var baselineCalls = 0

        override suspend fun listHouseholds(): List<HouseholdSnapshot> {
            baselineCalls += 1
            return householdRows.values.toList()
        }

        override suspend fun createHousehold(name: String): HouseholdSnapshot =
            throw UnsupportedOperationException()

        override suspend fun renameHousehold(remoteId: String, name: String): HouseholdSnapshot =
            throw UnsupportedOperationException()

        override suspend fun members(remoteId: String): List<HouseholdMember> = emptyList()

        override suspend fun removeMember(remoteId: String, userId: String) = Unit

        override suspend fun leave(remoteId: String) = Unit

        override suspend fun transferOwnership(remoteId: String, userId: String) =
            throw UnsupportedOperationException()

        override suspend fun createInvitation(remoteId: String): HouseholdInvitation =
            throw UnsupportedOperationException()

        override suspend fun listInvitations(remoteId: String): List<HouseholdInvitationInfo> =
            emptyList()

        override suspend fun revokeInvitation(remoteId: String, invitationId: String) = Unit

        override suspend fun claimInvitation(code: String): HouseholdSnapshot =
            throw UnsupportedOperationException()

        override suspend fun listRooms(remoteId: String): List<HouseholdRoom> =
            rooms[remoteId].orEmpty().toList()

        override suspend fun createRoom(remoteId: String, name: String, sortOrder: Int): HouseholdRoom =
            throw UnsupportedOperationException()

        override suspend fun updateRoom(
            remoteId: String,
            roomRemoteId: String,
            name: String?,
            sortOrder: Int?,
        ): HouseholdRoom = throw UnsupportedOperationException()

        override suspend fun reorderRooms(
            remoteId: String,
            roomRemoteIds: List<String>,
        ): List<HouseholdRoom> = throw UnsupportedOperationException()

        override suspend fun deleteRoom(remoteId: String, roomRemoteId: String) = Unit

        override suspend fun listDevices(): List<RemoteDeviceRef> = devices.toList()

        override suspend fun patchDevice(
            platform: String,
            externalId: String,
            roomRemoteId: String,
            householdRemoteId: String?,
        ): RemoteDeviceRef = throw UnsupportedOperationException()
    }

    private companion object {
        const val PERSONAL = "h-personal"
        const val FAMILY = "h-family"
        const val MEMBER_DEVICE = "AA:BB:CC:00:00:99"
        const val SHARED_MAC = "shared:android:$MEMBER_DEVICE"
        const val OWNED_MAC = "AA:BB:CC:00:00:88"
        const val NOW = 1_760_000_000_000L
    }
}
