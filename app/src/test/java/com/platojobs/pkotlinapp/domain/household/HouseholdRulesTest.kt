package com.platojobs.pkotlinapp.domain.household

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首次接入的合并策略：按名称认领服务端同名房间，其余按本地顺序上传，
 * 绝不能静默丢弃本机已建房间或设备归属。
 */
class HouseholdRulesTest {

    private fun remote(id: String, name: String, sort: Int = 0) = HouseholdRoom(
        remoteId = id,
        householdRemoteId = "h-1",
        name = name,
        sortOrder = sort,
        createdAt = 0,
    )

    @Test
    fun `local rooms with matching names adopt server rooms and keep local ids`() {
        val local = listOf(
            LocalRoomSnapshot(1, "客厅", 0, null),
            LocalRoomSnapshot(2, "卧室", 1, null),
        )
        val remote = listOf(remote("r-1", " 客厅 "), remote("r-2", "卧室"))

        val plan = planRoomMerge(local, remote)

        assertEquals(listOf(1L to "r-1", 2L to "r-2"), plan.adopt.map { it.first to it.second.remoteId })
        assertTrue(plan.push.isEmpty())
        assertTrue(plan.insert.isEmpty())
    }

    @Test
    fun `unmatched local rooms are pushed not dropped`() {
        val local = listOf(
            LocalRoomSnapshot(1, "客厅", 0, null),
            LocalRoomSnapshot(2, "书房", 1, null),
        )
        val remote = listOf(remote("r-1", "客厅"))

        val plan = planRoomMerge(local, remote)

        assertEquals(listOf(1L), plan.adopt.map { it.first })
        assertEquals(listOf("书房"), plan.push.map { it.name })
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun `server rooms not present locally are inserted`() {
        val local = listOf(LocalRoomSnapshot(1, "客厅", 0, null))
        val remote = listOf(remote("r-1", "客厅"), remote("r-2", "阳台"))

        val plan = planRoomMerge(local, remote)

        assertEquals(listOf("阳台"), plan.insert.map { it.name })
    }

    @Test
    fun `remote room deleted on server is removed locally`() {
        val local = listOf(
            LocalRoomSnapshot(1, "客厅", 0, "r-1"),
            LocalRoomSnapshot(2, "书房", 1, "r-2"),
        )
        val remote = listOf(remote("r-1", "客厅"))

        val plan = planRoomMerge(local, remote)

        assertEquals(listOf(1L), plan.update.map { it.first })
        assertEquals(listOf(2L), plan.remove)
        assertTrue(plan.push.isEmpty())
    }

    @Test
    fun `matching is case and whitespace insensitive and does not double claim`() {
        val local = listOf(
            LocalRoomSnapshot(1, "Living Room", 0, null),
            LocalRoomSnapshot(2, "living room", 1, null),
        )
        val remote = listOf(remote("r-1", " living  room "))

        val plan = planRoomMerge(local, remote)

        assertEquals(1, plan.adopt.size)
        assertEquals(1, plan.push.size)
        assertTrue(plan.insert.isEmpty())
    }

    @Test
    fun `current household prefers persisted id then personal`() {
        val personal = HouseholdSnapshot("h-personal", "我的家", true, HouseholdRole.OWNER, 1, 1, 0, 0)
        val shared = HouseholdSnapshot("h-shared", "老家", false, HouseholdRole.MEMBER, 2, 3, 0, 0)

        assertEquals("h-shared", chooseCurrentHousehold(listOf(personal, shared), "h-shared")?.remoteId)
        assertEquals("h-personal", chooseCurrentHousehold(listOf(shared, personal), null)?.remoteId)
        assertEquals("h-shared", chooseCurrentHousehold(listOf(shared), "missing")?.remoteId)
    }

    @Test
    fun `household errors map to readable kinds`() {
        assertEquals(HouseholdErrorKind.PERMISSION_DENIED, classifyHouseholdError(HouseholdForbiddenException()))
        assertEquals(HouseholdErrorKind.NOT_FOUND, classifyHouseholdError(HouseholdNotFoundException()))
        assertEquals(HouseholdErrorKind.INVALID_CODE, classifyHouseholdError(InviteCodeInvalidException()))
        assertEquals(HouseholdErrorKind.RATE_LIMITED, classifyHouseholdError(InviteRateLimitedException()))
        assertEquals(HouseholdErrorKind.CONFLICT, classifyHouseholdError(HouseholdConflictException()))
        assertEquals(HouseholdErrorKind.CONFLICT, classifyHouseholdError(RoomSetMismatchException()))
        assertEquals(HouseholdErrorKind.OFFLINE, classifyHouseholdError(java.io.IOException()))
        assertEquals(HouseholdErrorKind.SERVER_ERROR, classifyHouseholdError(IllegalStateException()))
        assertFalse(HouseholdRole.parse("member").isOwner)
        assertTrue(HouseholdRole.parse("owner").isOwner)
    }

    @Test
    fun `owner with members cannot leave and sees all manage entries`() {
        val caps = householdCapabilities(household(role = HouseholdRole.OWNER, memberCount = 2))

        assertTrue(caps.canManage)
        assertTrue(caps.canRemoveMembers)
        assertTrue(caps.canTransferOwnership)
        assertFalse(caps.canLeave)
        assertTrue(caps.leaveBlockedByMembers)
    }

    @Test
    fun `member sees no manage entries but can leave`() {
        val caps = householdCapabilities(household(role = HouseholdRole.MEMBER, memberCount = 3))

        assertFalse(caps.canManage)
        assertFalse(caps.canRemoveMembers)
        assertFalse(caps.canTransferOwnership)
        assertTrue(caps.canLeave)
        assertFalse(caps.leaveBlockedByMembers)
    }

    @Test
    fun `owner alone can leave non-personal household`() {
        val caps = householdCapabilities(household(role = HouseholdRole.OWNER, memberCount = 1))

        assertTrue(caps.canManage)
        assertFalse(caps.canRemoveMembers)
        assertFalse(caps.canTransferOwnership)
        assertTrue(caps.canLeave)
    }

    @Test
    fun `old backend without isSelf hides ownership transfer`() {
        val caps = householdCapabilities(
            household(role = HouseholdRole.OWNER, memberCount = 2),
            transferSupported = false,
        )

        assertTrue(caps.canManage)
        assertTrue(caps.canRemoveMembers)
        assertFalse(caps.canTransferOwnership)
        assertFalse(caps.canLeave)
    }

    @Test
    fun `personal household can never be left`() {
        val caps = householdCapabilities(
            household(role = HouseholdRole.OWNER, memberCount = 1, isPersonal = true),
        )
        assertFalse(caps.canLeave)
    }

    private fun household(
        role: HouseholdRole,
        memberCount: Int,
        isPersonal: Boolean = false,
    ) = Household(
        localId = 1,
        remoteId = "h-1",
        name = "全家",
        isPersonal = isPersonal,
        role = role,
        memberCount = memberCount,
        deviceCount = 2,
        roomCount = 3,
    )
}
