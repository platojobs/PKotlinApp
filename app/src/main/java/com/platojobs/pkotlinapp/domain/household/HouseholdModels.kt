package com.platojobs.pkotlinapp.domain.household

import com.platojobs.pkotlinapp.domain.sync.StructuralChange

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
    /** 服务端 v0.14.0 起直接标记「这一行是我」，不再靠用户名或 id 比对。 */
    val isSelf: Boolean = false,
    /** 服务端返回的人名/昵称；老后端没有该字段时为 null。 */
    val displayName: String? = null,
)

/** 成员行的可读名称：有人名/昵称用人名，否则用「成员 N」，原始 ID 只留给排障入口。 */
object HouseholdMemberLabel {
    fun resolve(
        displayName: String?,
        ordinal: Int,
        ordinalLabel: (Int) -> String,
    ): String {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
        return name ?: ordinalLabel(ordinal)
    }
}

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
 * 机读判定只用 HTTP 409 + `error.code`，不解析人类可读的 message。
 * 后端 v0.14.0 起重排返回 `ROOM_SET_MISMATCH`，更早版本为 `CONFLICT`。
 */
class HouseholdConflictException(val code: String? = null) : RuntimeException("conflict")

/** 重排时房间集合已变化：仓库已重新拉取，调用方提示「已刷新」即可。 */
class RoomSetMismatchException : RuntimeException("room set changed")

/** 老后端没有转让所有权路由（404/405）：UI 隐藏入口，不弹错误。 */
class OwnershipTransferUnsupportedException : RuntimeException("ownership transfer unsupported")

/** 家庭接口的远程访问抽象，便于单测替换。 */
interface HouseholdRemote {
    suspend fun listHouseholds(): List<HouseholdSnapshot>
    suspend fun createHousehold(name: String): HouseholdSnapshot
    suspend fun renameHousehold(remoteId: String, name: String): HouseholdSnapshot
    suspend fun members(remoteId: String): List<HouseholdMember>
    suspend fun removeMember(remoteId: String, userId: String)
    suspend fun leave(remoteId: String)

    /** 转让所有权；老后端没有该路由时抛 [HouseholdNotFoundException]。 */
    suspend fun transferOwnership(remoteId: String, userId: String): HouseholdSnapshot

    /**
     * 服务端是否支持转让所有权。默认 true；远程实现可用成员列表里 `isSelf`
     * 是否存在来探测老后端，从而提前隐藏入口而不是等用户点击后报错。
     */
    fun supportsOwnershipTransfer(): Boolean = true

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

    /**
     * 应用 change_log 中的家庭/房间结构变更（openapi 0.11.0）。
     *
     * 默认空实现只是为了让不关心结构变更的测试替身继续编译；
     * 生产实现必须尊重本地 pending 房间编辑，不能整段覆盖。
     */
    suspend fun applyStructural(changes: List<StructuralChange>) = Unit
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
