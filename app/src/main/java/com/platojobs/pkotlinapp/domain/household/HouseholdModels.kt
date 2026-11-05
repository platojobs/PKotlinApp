package com.platojobs.pkotlinapp.domain.household

/** 家庭角色：服务端仅区分 owner / member。 */
enum class HouseholdRole {
    OWNER,
    MEMBER;

    companion object {
        fun parse(raw: String?): HouseholdRole =
            if (raw.equals("owner", ignoreCase = true)) OWNER else MEMBER
    }

    val isOwner: Boolean get() = this == OWNER
}

data class Household(
    val localId: Long,
    val remoteId: String,
    val name: String,
    val isPersonal: Boolean,
    val role: HouseholdRole,
    val memberCount: Int = 0,
    val deviceCount: Int = 0,
    /** 服务端统计的房间数（`GET /households`），不数本地缓存。 */
    val roomCount: Int = 0,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
) {
    val isOwner: Boolean get() = role == HouseholdRole.OWNER
}

data class HouseholdMember(
    val userId: String,
    val role: HouseholdRole,
    val kind: String,
    val joinedAt: Long,
)

data class HouseholdInvitation(
    val code: String,
    /** 服务端返回的过期时间（毫秒时间戳）。 */
    val expiresAt: Long,
)

/** 邀请码状态：与服务端 `status` 枚举一致。 */
enum class InvitationStatus {
    ACTIVE,
    EXPIRED,
    LOCKED;

    companion object {
        fun parse(raw: String?): InvitationStatus = when (raw?.lowercase()) {
            "active" -> ACTIVE
            "locked" -> LOCKED
            else -> EXPIRED
        }
    }
}

/**
 * 邀请码管理视图：服务端只返回哈希前缀，明文只在签发那一次出现。
 */
data class HouseholdInvitationInfo(
    val invitationId: String,
    val codeHashPrefix: String,
    val status: InvitationStatus,
    val attempts: Int,
    val expiresAt: Long,
    val createdAt: Long,
)

data class HouseholdRoom(
    val remoteId: String,
    val householdRemoteId: String,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
)

/** `/my/devices` 返回的设备快照中，家庭与房间归属用到的字段。 */
data class RemoteDeviceRef(
    val platform: String,
    val externalId: String,
    val householdRemoteId: String?,
    val roomRemoteId: String?,
    val alias: String? = null,
    val updatedAt: Long = 0,
)

data class HouseholdSyncStatus(
    val synced: Boolean = false,
    val syncing: Boolean = false,
    val lastError: String? = null,
    val lastSyncAt: Long? = null,
)

class HouseholdForbiddenException : RuntimeException("forbidden")
class HouseholdNotFoundException : RuntimeException("not found")
class HouseholdBadRequestException : RuntimeException("bad request")
class InviteCodeInvalidException : RuntimeException("invitation invalid")
class InviteRateLimitedException : RuntimeException("too many attempts")
class HouseholdServerUnavailableException(cause: Throwable? = null) :
    RuntimeException("server unavailable", cause)

/**
 * 409：owner 带成员退出、整段重排集合不一致。
 *
 * 后端 v0.13.0 尚未提供 `ROOM_SET_MISMATCH`，因此机读判定只用 HTTP 409 + `error.code`
 * （当前为 `CONFLICT`），不解析人类可读的 message。
 */
class HouseholdConflictException(val code: String? = null) : RuntimeException("conflict")

/** 重排时房间集合已变化：仓库已重新拉取，调用方提示「已刷新」即可。 */
class RoomSetMismatchException : RuntimeException("room set changed")

/** 家庭接口的远程访问抽象，便于单测替换。 */
interface HouseholdRemote {
    suspend fun listHouseholds(): List<HouseholdSnapshot>
    suspend fun createHousehold(name: String): HouseholdSnapshot
    suspend fun renameHousehold(remoteId: String, name: String): HouseholdSnapshot
    suspend fun members(remoteId: String): List<HouseholdMember>
    suspend fun removeMember(remoteId: String, userId: String)
    suspend fun leave(remoteId: String)
    suspend fun createInvitation(remoteId: String): HouseholdInvitation
    suspend fun listInvitations(remoteId: String): List<HouseholdInvitationInfo>
    suspend fun revokeInvitation(remoteId: String, invitationId: String)
    suspend fun claimInvitation(code: String): HouseholdSnapshot
    suspend fun listRooms(remoteId: String): List<HouseholdRoom>
    suspend fun createRoom(remoteId: String, name: String, sortOrder: Int): HouseholdRoom
    suspend fun updateRoom(
        remoteId: String,
        roomRemoteId: String,
        name: String?,
        sortOrder: Int?,
    ): HouseholdRoom

    /** 整段重排；集合不一致时服务端返回 409。 */
    suspend fun reorderRooms(remoteId: String, roomRemoteIds: List<String>): List<HouseholdRoom>
    suspend fun deleteRoom(remoteId: String, roomRemoteId: String)
    suspend fun listDevices(): List<RemoteDeviceRef>

    suspend fun patchDevice(
        platform: String,
        externalId: String,
        roomRemoteId: String,
        householdRemoteId: String?,
    ): RemoteDeviceRef
}

/** 同步编排（SyncManager）可选依赖：家庭拉取/上报与重新登录后的清理。 */
interface HouseholdSyncer {
    suspend fun onSync()

    suspend fun onRelogin()
}

/** 服务端家庭列表项（还未落库，因此没有 localId）。 */
data class HouseholdSnapshot(
    val remoteId: String,
    val name: String,
    val isPersonal: Boolean,
    val role: HouseholdRole,
    val memberCount: Int,
    val deviceCount: Int,
    val sortOrder: Int,
    val createdAt: Long,
    /** 服务端统计的房间数（openapi 0.9.0 起）。 */
    val roomCount: Int = 0,
)
