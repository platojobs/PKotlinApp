package com.platojobs.pkotlinapp.domain.household

/** 本地房间在本轮合并中的输入快照。 */
data class LocalRoomSnapshot(
    val localId: Long,
    val name: String,
    val sortOrder: Int,
    val remoteId: String?,
)

/**
 * 首次接入/每次拉取时把本机房间与服务端房间合并的计划。
 *
 * 规则（明确且幂等）：
 * 1. 已有 remoteId 的本地房间：服务端仍存在则更新，服务端已删除则移除本地；
 * 2. 没有 remoteId 的本地房间：优先按名称（去空白、忽略大小写）匹配尚未占用的服务端房间，
 *    匹配成功即「认领」该服务端房间并保留本地 id，因此原有设备归属不会丢；
 * 3. 仍然匹配不到的本机房间按本地顺序上传，不会静默丢弃；
 * 4. 剩余服务端房间插入本地。
 */
data class RoomMergePlan(
    val adopt: List<Pair<Long, HouseholdRoom>>,
    val update: List<Pair<Long, HouseholdRoom>>,
    val push: List<LocalRoomSnapshot>,
    val insert: List<HouseholdRoom>,
    val remove: List<Long>,
)

fun planRoomMerge(
    local: List<LocalRoomSnapshot>,
    remote: List<HouseholdRoom>,
): RoomMergePlan {
    val remoteById = remote.associateBy { it.remoteId }
    val claimedRemoteIds = mutableSetOf<String>()
    val adopt = mutableListOf<Pair<Long, HouseholdRoom>>()
    val update = mutableListOf<Pair<Long, HouseholdRoom>>()
    val push = mutableListOf<LocalRoomSnapshot>()
    val remove = mutableListOf<Long>()

    local.sortedWith(compareBy<LocalRoomSnapshot> { it.sortOrder }.thenBy { it.localId }).forEach { room ->
        val remoteId = room.remoteId
        if (remoteId != null) {
            val match = remoteById[remoteId]
            if (match != null) {
                claimedRemoteIds += remoteId
                update += room.localId to match
            } else {
                remove += room.localId
            }
            return@forEach
        }
        val candidate = remote.firstOrNull {
            it.remoteId !in claimedRemoteIds && normalizeRoomName(it.name) == normalizeRoomName(room.name)
        }
        if (candidate != null) {
            claimedRemoteIds += candidate.remoteId
            adopt += room.localId to candidate
        } else {
            push += room
        }
    }

    val localRemoteIds = local.mapNotNull { it.remoteId }.toSet()
    val insert = remote.filter { it.remoteId !in claimedRemoteIds && it.remoteId !in localRemoteIds }
    return RoomMergePlan(adopt, update, push, insert, remove)
}

fun normalizeRoomName(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * 选择当前家庭：优先上次持久化的 remoteId；否则个人家庭；再否则第一个。
 * 服务端列表按个人家庭优先、其余按名称/创建时间返回。
 */
fun chooseCurrentHousehold(
    list: List<HouseholdSnapshot>,
    preferredRemoteId: String?,
): HouseholdSnapshot? {
    if (list.isEmpty()) return null
    preferredRemoteId?.let { preferred ->
        list.firstOrNull { it.remoteId == preferred }?.let { return it }
    }
    return list.firstOrNull { it.isPersonal } ?: list.first()
}

/** 家庭相关错误的可读类型，UI 依此映射文案，不直接展示原始错误码。 */
enum class HouseholdErrorKind {
    PERMISSION_DENIED,
    NOT_FOUND,
    INVALID_CODE,
    RATE_LIMITED,
    /** 409：owner 带成员退出、重排集合已变化。 */
    CONFLICT,
    OFFLINE,
    SERVER_ERROR,
}

fun classifyHouseholdError(error: Throwable): HouseholdErrorKind = when (error) {
    is HouseholdForbiddenException -> HouseholdErrorKind.PERMISSION_DENIED
    is HouseholdNotFoundException -> HouseholdErrorKind.NOT_FOUND
    is InviteCodeInvalidException -> HouseholdErrorKind.INVALID_CODE
    is InviteRateLimitedException -> HouseholdErrorKind.RATE_LIMITED
    is HouseholdConflictException, is RoomSetMismatchException -> HouseholdErrorKind.CONFLICT
    is java.io.IOException -> HouseholdErrorKind.OFFLINE
    else -> HouseholdErrorKind.SERVER_ERROR
}

/**
 * 家庭管理入口可见性：
 * - 只有 owner 能签发/撤销邀请码、移除成员、改家庭名；
 * - member 随时可以退出（非个人家庭）；
 * - owner 只有在没有其他成员时才能退出，否则需要先移除成员或转让所有权。
 */
data class HouseholdCapabilities(
    val canManage: Boolean,
    val canRemoveMembers: Boolean,
    val canTransferOwnership: Boolean,
    val canLeave: Boolean,
    /** owner 还有其他成员：展示「请先转让所有权或移除其他成员」。 */
    val leaveBlockedByMembers: Boolean,
)

/**
 * @param transferSupported 服务端是否支持转让所有权（老后端不返回 `isSelf`、无该路由时传 false）。
 */
fun householdCapabilities(
    household: Household?,
    transferSupported: Boolean = true,
): HouseholdCapabilities {
    if (household == null) {
        return HouseholdCapabilities(false, false, false, false, false)
    }
    val owner = household.role == HouseholdRole.OWNER
    val hasOtherMembers = household.memberCount > 1
    val leaveBlocked = !household.isPersonal && owner && hasOtherMembers
    return HouseholdCapabilities(
        canManage = owner,
        canRemoveMembers = owner && hasOtherMembers,
        canTransferOwnership = owner && hasOtherMembers && transferSupported,
        canLeave = !household.isPersonal && !leaveBlocked,
        leaveBlockedByMembers = leaveBlocked,
    )
}
