package com.platojobs.pkotlinapp.data.household

import androidx.room.Room
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.data.sync.SyncPrefs
import com.platojobs.pkotlinapp.domain.household.HouseholdConflictException
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitation
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitationInfo
import com.platojobs.pkotlinapp.domain.household.HouseholdMember
import com.platojobs.pkotlinapp.domain.household.HouseholdNotFoundException
import com.platojobs.pkotlinapp.domain.household.HouseholdRemote
import com.platojobs.pkotlinapp.domain.household.HouseholdRole
import com.platojobs.pkotlinapp.domain.household.HouseholdRoom
import com.platojobs.pkotlinapp.domain.household.HouseholdSnapshot
import com.platojobs.pkotlinapp.domain.household.InvitationStatus
import com.platojobs.pkotlinapp.domain.household.RemoteDeviceRef
import com.platojobs.pkotlinapp.domain.household.RoomSetMismatchException
import com.platojobs.pkotlinapp.domain.household.householdCapabilities
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * 家庭链路的本机行为：用真实 Room（内存库）+ 假远端，
 * 覆盖成员移除/退出家庭后的本地清理、邀请码列表与撤销、整段重排 409 重试。
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class HouseholdRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var remote: FakeHouseholdRemote
    private lateinit var repo: HouseholdRepository
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Before
    fun setUp() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = SyncPrefs(context)
        prefs.currentHouseholdRemoteId = null
        remote = FakeHouseholdRemote()
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
    fun `removing a member drops their shared devices and refreshes counts`() = runBlocking {
        seedFamilyWithSharedDevice()
        repo.switchHousehold(FAMILY)

        val sharedMac = "shared:android:$MEMBER_DEVICE"
        assertNotNull(db.deviceDao().find(sharedMac))

        // 服务端移除成员后，该成员设备回到其个人家庭，不再出现在 /my/devices。
        remote.onRemoveMember = { userId ->
            remote.devices.removeAll { it.externalId == MEMBER_DEVICE }
            remote.householdRows[FAMILY] = requireNotNull(remote.householdRows[FAMILY])
                .copy(memberCount = 1, deviceCount = 0)
            remote.removedMembers += userId
        }
        repo.removeMember("user-2")

        assertNull(db.deviceDao().find(sharedMac))
        val family = db.householdDao().findByRemoteId(FAMILY)
        assertEquals(1, family?.memberCount)
        assertEquals(0, family?.deviceCount)
        assertEquals(listOf("user-2"), remote.removedMembers)
    }

    @Test
    fun `transferring ownership downgrades caller and hides manage entries`() = runBlocking {
        seedFamilyWithSharedDevice()
        repo.switchHousehold(FAMILY)
        assertEquals(HouseholdRole.OWNER, repo.currentHousehold.value?.role)

        val result = repo.transferOwnership("user-2")

        assertEquals(listOf("user-2"), remote.transfers)
        assertEquals(HouseholdRole.MEMBER, result.role)
        // 角色已落库，UI 的 currentHousehold Flow 会在下一帧收到同一份刷新结果。
        assertEquals("member", db.householdDao().findByRemoteId(FAMILY)?.role)
        val caps = householdCapabilities(result, repo.supportsOwnershipTransfer())
        assertFalse(caps.canManage)
        assertFalse(caps.canRemoveMembers)
        assertFalse(caps.canTransferOwnership)
    }

    @Test
    fun `leaving a household clears its rooms and falls back to personal`() = runBlocking {
        seedFamilyWithSharedDevice()
        repo.switchHousehold(FAMILY)
        assertTrue(db.roomDao().getRooms().isNotEmpty())

        val remaining = repo.leaveHousehold()

        assertEquals(PERSONAL, remaining?.remoteId)
        assertNull(db.householdDao().findByRemoteId(FAMILY))
        assertTrue(db.roomDao().getRooms().isEmpty())
        assertNull(db.deviceDao().find("shared:android:$MEMBER_DEVICE"))
        assertEquals(listOf(FAMILY), remote.leftHouseholds)
    }

    @Test
    fun `invitation list has no plaintext and revoke removes the entry`() = runBlocking {
        seedFamilyWithSharedDevice()
        repo.switchHousehold(FAMILY)
        remote.invitationRows += HouseholdInvitationInfo(
            invitationId = "inv-1",
            codeHashPrefix = "ABCD1234",
            status = InvitationStatus.ACTIVE,
            attempts = 0,
            expiresAt = NOW + 600_000,
            createdAt = NOW,
        )

        val list = repo.invitations()

        assertEquals(1, list.size)
        assertEquals("ABCD1234", list.single().codeHashPrefix)
        assertEquals(InvitationStatus.ACTIVE, list.single().status)

        repo.revokeInvitation("inv-1")
        assertTrue(repo.invitations().isEmpty())
        assertEquals(listOf("inv-1"), remote.revokedInvitations)
    }

    @Test
    fun `reorder conflict refreshes server order and reports mismatch`() = runBlocking {
        seedFamilyWithSharedDevice()
        repo.switchHousehold(FAMILY)
        val familyId = requireNotNull(db.householdDao().findByRemoteId(FAMILY)).id
        val localIds = db.roomDao().getRoomsForHousehold(familyId)
            .sortedBy { it.sortOrder }
            .map { it.id }
        assertEquals(2, localIds.size)

        remote.conflictOnReorder = true
        try {
            repo.reorderRooms(localIds.reversed())
            fail("409 应抛出 RoomSetMismatchException")
        } catch (expected: RoomSetMismatchException) {
            // 预期：仓库已重新拉取，服务端顺序覆盖本地乐观顺序。
        }

        val refreshed = db.roomDao().getRoomsForHousehold(familyId).sortedBy { it.sortOrder }
        assertEquals(listOf("r-1", "r-2"), refreshed.map { it.remoteId })

        remote.conflictOnReorder = false
        assertTrue(repo.reorderRooms(localIds.reversed()))
        assertEquals(listOf("r-2", "r-1"), remote.lastReorder)
        val submitted = db.roomDao().getRoomsForHousehold(familyId).sortedBy { it.sortOrder }
        assertEquals(listOf("r-2", "r-1"), submitted.map { it.remoteId })
    }

    @Test
    fun `reorder while offline keeps local order and marks unsynced`() = runBlocking {
        seedFamilyWithSharedDevice()
        repo.switchHousehold(FAMILY)
        val familyId = requireNotNull(db.householdDao().findByRemoteId(FAMILY)).id
        val localIds = db.roomDao().getRoomsForHousehold(familyId)
            .sortedBy { it.sortOrder }
            .map { it.id }

        remote.offline = true
        assertTrue(!repo.reorderRooms(localIds.reversed()))

        val local = db.roomDao().getRoomsForHousehold(familyId).sortedBy { it.sortOrder }
        assertEquals(listOf("r-2", "r-1"), local.map { it.remoteId })
        assertTrue(!repo.status.value.synced)
    }

    private suspend fun seedFamilyWithSharedDevice() {
        remote.householdRows[PERSONAL] = snapshot(PERSONAL, "我的家", personal = true, members = 1)
        remote.householdRows[FAMILY] = snapshot(FAMILY, "全家", personal = false, members = 2)
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
    }

    private fun snapshot(remoteId: String, name: String, personal: Boolean, members: Int) =
        HouseholdSnapshot(
            remoteId = remoteId,
            name = name,
            isPersonal = personal,
            role = HouseholdRole.OWNER,
            memberCount = members,
            deviceCount = if (personal) 0 else 1,
            sortOrder = if (personal) 0 else 1,
            createdAt = NOW,
            roomCount = if (personal) 0 else 2,
        )

    private class FakeHouseholdRemote : HouseholdRemote {
        val householdRows = mutableMapOf<String, HouseholdSnapshot>()
        val rooms = mutableMapOf<String, MutableList<HouseholdRoom>>()
        val devices = mutableListOf<RemoteDeviceRef>()
        val invitationRows = mutableListOf<HouseholdInvitationInfo>()
        val removedMembers = mutableListOf<String>()
        val revokedInvitations = mutableListOf<String>()
        val leftHouseholds = mutableListOf<String>()
        val transfers = mutableListOf<String>()
        var conflictOnReorder = false
        var offline = false
        var lastReorder: List<String>? = null
        var onRemoveMember: (String) -> Unit = {}

        override suspend fun listHouseholds(): List<HouseholdSnapshot> {
            failIfOffline()
            return householdRows.values.toList()
        }

        override suspend fun createHousehold(name: String): HouseholdSnapshot =
            snapshot("h-new", name)

        override suspend fun renameHousehold(remoteId: String, name: String): HouseholdSnapshot =
            snapshot(remoteId, name)

        override suspend fun members(remoteId: String): List<HouseholdMember> = listOf(
            HouseholdMember("user-1", HouseholdRole.OWNER, "user", NOW, isSelf = true),
            HouseholdMember("user-2", HouseholdRole.MEMBER, "user", NOW),
        )

        override suspend fun removeMember(remoteId: String, userId: String) {
            failIfOffline()
            onRemoveMember(userId)
        }

        override suspend fun transferOwnership(
            remoteId: String,
            userId: String,
        ): HouseholdSnapshot {
            failIfOffline()
            transfers += userId
            val row = householdRows[remoteId] ?: throw HouseholdNotFoundException()
            // 转让后调用者降为 member，目标升为 owner。
            householdRows[remoteId] = row.copy(role = HouseholdRole.MEMBER)
            return householdRows[remoteId]!!
        }

        override suspend fun leave(remoteId: String) {
            failIfOffline()
            leftHouseholds += remoteId
            householdRows.remove(remoteId)
            rooms.remove(remoteId)
            devices.removeAll { it.householdRemoteId == remoteId }
        }

        override suspend fun createInvitation(remoteId: String) = HouseholdInvitation("ABCD2345", NOW + 60_000)

        override suspend fun listInvitations(remoteId: String): List<HouseholdInvitationInfo> =
            invitationRows.toList()

        override suspend fun revokeInvitation(remoteId: String, invitationId: String) {
            revokedInvitations += invitationId
            invitationRows.removeAll { it.invitationId == invitationId }
        }

        override suspend fun claimInvitation(code: String): HouseholdSnapshot = snapshot("h-claim", code)

        override suspend fun listRooms(remoteId: String): List<HouseholdRoom> {
            failIfOffline()
            return rooms[remoteId].orEmpty().toList()
        }

        override suspend fun createRoom(remoteId: String, name: String, sortOrder: Int): HouseholdRoom =
            HouseholdRoom("r-${rooms.size + 1}", remoteId, name, sortOrder, NOW)

        override suspend fun updateRoom(
            remoteId: String,
            roomRemoteId: String,
            name: String?,
            sortOrder: Int?,
        ): HouseholdRoom {
            val room = rooms[remoteId].orEmpty().first { it.remoteId == roomRemoteId }
            return room.copy(name = name ?: room.name, sortOrder = sortOrder ?: room.sortOrder)
        }

        override suspend fun reorderRooms(
            remoteId: String,
            roomRemoteIds: List<String>,
        ): List<HouseholdRoom> {
            failIfOffline()
            if (conflictOnReorder) throw HouseholdConflictException("CONFLICT")
            val current = rooms[remoteId].orEmpty()
            if (roomRemoteIds.toSet() != current.map { it.remoteId }.toSet()) {
                throw HouseholdConflictException("CONFLICT")
            }
            lastReorder = roomRemoteIds
            val reordered = roomRemoteIds.mapIndexed { index, id ->
                current.first { it.remoteId == id }.copy(sortOrder = index)
            }
            rooms[remoteId] = reordered.toMutableList()
            return reordered
        }

        override suspend fun deleteRoom(remoteId: String, roomRemoteId: String) {
            rooms[remoteId]?.removeAll { it.remoteId == roomRemoteId }
        }

        override suspend fun listDevices(): List<RemoteDeviceRef> {
            failIfOffline()
            return devices.toList()
        }

        override suspend fun patchDevice(
            platform: String,
            externalId: String,
            roomRemoteId: String,
            householdRemoteId: String?,
        ): RemoteDeviceRef = RemoteDeviceRef(
            platform = platform,
            externalId = externalId,
            householdRemoteId = householdRemoteId,
            roomRemoteId = roomRemoteId.ifEmpty { null },
            updatedAt = NOW,
        )

        private fun failIfOffline() {
            if (offline) throw IOException("offline")
        }

        private fun snapshot(remoteId: String, name: String) = HouseholdSnapshot(
            remoteId = remoteId,
            name = name,
            isPersonal = false,
            role = HouseholdRole.OWNER,
            memberCount = 1,
            deviceCount = 0,
            sortOrder = 0,
            createdAt = NOW,
        )
    }

    private companion object {
        const val PERSONAL = "h-personal"
        const val FAMILY = "h-family"
        const val MEMBER_DEVICE = "AA:BB:CC:00:00:99"
        const val NOW = 1_760_000_000_000L
    }
}
