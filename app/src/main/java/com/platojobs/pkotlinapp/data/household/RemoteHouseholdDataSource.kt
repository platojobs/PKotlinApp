package com.platojobs.pkotlinapp.data.household

import com.platojobs.pkotlinapp.data.remote.DeviceMetaPatchRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdCreateRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdDto
import com.platojobs.pkotlinapp.data.remote.HouseholdInvitationClaimRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdInvitationInfoDto
import com.platojobs.pkotlinapp.data.remote.HouseholdOwnershipTransferRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdPatchRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdRoomCreateRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdRoomDto
import com.platojobs.pkotlinapp.data.remote.HouseholdRoomOrderRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdRoomPatchRequest
import com.platojobs.pkotlinapp.data.remote.Reported
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.SyncDeviceDto
import com.platojobs.pkotlinapp.data.remote.apiErrorCode
import com.platojobs.pkotlinapp.data.remote.toSyncException
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.household.HouseholdConflictException
import com.platojobs.pkotlinapp.domain.household.HouseholdForbiddenException
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitation
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitationInfo
import com.platojobs.pkotlinapp.domain.household.HouseholdMember
import com.platojobs.pkotlinapp.domain.household.HouseholdNotFoundException
import com.platojobs.pkotlinapp.domain.household.HouseholdRemote
import com.platojobs.pkotlinapp.domain.household.HouseholdRole
import com.platojobs.pkotlinapp.domain.household.HouseholdRoom
import com.platojobs.pkotlinapp.domain.household.HouseholdSnapshot
import com.platojobs.pkotlinapp.domain.household.InvitationStatus
import com.platojobs.pkotlinapp.domain.household.InviteCodeInvalidException
import com.platojobs.pkotlinapp.domain.household.InviteRateLimitedException
import com.platojobs.pkotlinapp.domain.household.OwnershipTransferUnsupportedException
import com.platojobs.pkotlinapp.domain.household.RemoteDeviceRef
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import retrofit2.HttpException

/**
 * 家庭接口 Retrofit 适配（openapi 0.9.0 / 后端 v0.13.0）。
 *
 * 错误语义：
 * - 403 → [HouseholdForbiddenException]（member 改家庭/房间/签发邀请码）；
 * - 404 → [HouseholdNotFoundException]（跨家庭不可见，避免泄露存在性）；
 * - 409 → [HouseholdConflictException]，只带 `error.code` 作为机读判定（v0.14.0 起重排为
 *   `ROOM_SET_MISMATCH`，更早为 `CONFLICT`），不解析 message；
 * - 领取邀请码的 404 → [InviteCodeInvalidException]，429 → [InviteRateLimitedException]；
 * - 401 沿用同步的 [com.platojobs.pkotlinapp.domain.sync.AuthExpiredException]。
 */
class RemoteHouseholdDataSource(
    private val api: SyncApi,
    private val tokenStore: TokenStore,
) : HouseholdRemote {

    /** 成员列表 `isSelf` 缺失或转让路由 405 时置 false，UI 据此隐藏入口。 */
    private var transferCapable = true

    override suspend fun listHouseholds(): List<HouseholdSnapshot> = withToken { auth ->
        api.households(auth).items.orEmpty().mapNotNull { it.toSnapshot() }
    }

    override suspend fun createHousehold(name: String): HouseholdSnapshot = withToken { auth ->
        api.createHousehold(auth, HouseholdCreateRequest(name)).toSnapshot()
            ?: throw IllegalStateException("创建家庭响应缺少 id")
    }

    override suspend fun renameHousehold(remoteId: String, name: String): HouseholdSnapshot =
        withToken { auth ->
            api.patchHousehold(auth, remoteId, HouseholdPatchRequest(name)).toSnapshot()
                ?: throw IllegalStateException("更新家庭响应缺少 id")
        }

    override suspend fun members(remoteId: String): List<HouseholdMember> = withToken { auth ->
        val items = api.householdMembers(auth, remoteId).items.orEmpty()
        // v0.14.0 起每条成员都带 isSelf；全部缺失说明是老后端，提前隐藏转让入口。
        if (items.isNotEmpty() && items.none { it.isSelf != null }) {
            transferCapable = false
        }
        items.map {
            HouseholdMember(
                userId = it.userId.orEmpty(),
                role = HouseholdRole.parse(it.role),
                kind = it.kind.orEmpty(),
                joinedAt = Rfc3339.parse(it.joinedAt) ?: 0L,
                isSelf = it.isSelf == true,
            )
        }
    }

    override suspend fun removeMember(remoteId: String, userId: String) = withToken { auth ->
        api.removeHouseholdMember(auth, remoteId, userId)
    }

    override suspend fun leave(remoteId: String) = withToken { auth ->
        api.leaveHousehold(auth, remoteId)
    }

    /**
     * 转让所有权。403/404/409 统一由 [withToken] 转成可读异常；
     * 405（方法不存在）说明老后端没有该路由，标记能力不可用供 UI 隐藏。
     */
    override suspend fun transferOwnership(
        remoteId: String,
        userId: String,
    ): HouseholdSnapshot = try {
        withToken { auth ->
            api.transferHouseholdOwnership(
                auth,
                remoteId,
                HouseholdOwnershipTransferRequest(userId),
            ).toSnapshot() ?: throw IllegalStateException("转让家庭响应缺少 id")
        }
    } catch (e: HttpException) {
        if (e.code() == 405) {
            transferCapable = false
            throw OwnershipTransferUnsupportedException()
        }
        throw e
    }

    override fun supportsOwnershipTransfer(): Boolean = transferCapable

    override suspend fun createInvitation(remoteId: String): HouseholdInvitation = withToken { auth ->
        val response = api.createHouseholdInvitation(auth, remoteId)
        val code = response.code?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("邀请码响应缺少 code")
        HouseholdInvitation(code = code, expiresAt = Rfc3339.parse(response.expiresAt) ?: 0L)
    }

    override suspend fun listInvitations(remoteId: String): List<HouseholdInvitationInfo> =
        withToken { auth ->
            api.householdInvitations(auth, remoteId).items.orEmpty().mapNotNull { it.toInfo() }
        }

    override suspend fun revokeInvitation(remoteId: String, invitationId: String) =
        withToken { auth ->
            api.revokeHouseholdInvitation(auth, remoteId, invitationId)
        }

    override suspend fun claimInvitation(code: String): HouseholdSnapshot = withToken { auth ->
        try {
            api.claimHouseholdInvitation(auth, HouseholdInvitationClaimRequest(code)).toSnapshot()
                ?: throw IllegalStateException("加入家庭响应缺少 id")
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> throw InviteCodeInvalidException()
                429 -> throw InviteRateLimitedException()
                else -> throw e
            }
        }
    }

    override suspend fun listRooms(remoteId: String): List<HouseholdRoom> = withToken { auth ->
        api.householdRooms(auth, remoteId).items.orEmpty().mapNotNull { it.toRoom() }
    }

    override suspend fun createRoom(
        remoteId: String,
        name: String,
        sortOrder: Int,
    ): HouseholdRoom = withToken { auth ->
        api.createHouseholdRoom(auth, remoteId, HouseholdRoomCreateRequest(name, sortOrder))
            .toRoom() ?: throw IllegalStateException("创建房间响应缺少 id")
    }

    override suspend fun updateRoom(
        remoteId: String,
        roomRemoteId: String,
        name: String?,
        sortOrder: Int?,
    ): HouseholdRoom = withToken { auth ->
        api.patchHouseholdRoom(
            auth,
            remoteId,
            roomRemoteId,
            HouseholdRoomPatchRequest(name = name, sortOrder = sortOrder),
        ).toRoom() ?: throw IllegalStateException("更新房间响应缺少 id")
    }

    override suspend fun deleteRoom(remoteId: String, roomRemoteId: String) = withToken { auth ->
        api.deleteHouseholdRoom(auth, remoteId, roomRemoteId)
    }

    override suspend fun reorderRooms(
        remoteId: String,
        roomRemoteIds: List<String>,
    ): List<HouseholdRoom> = withToken { auth ->
        api.reorderHouseholdRooms(auth, remoteId, HouseholdRoomOrderRequest(roomRemoteIds))
            .items.orEmpty().mapNotNull { it.toRoom() }
    }

    override suspend fun listDevices(): List<RemoteDeviceRef> = withToken { auth ->
        api.myDevices(auth).items.orEmpty().mapNotNull { it.toDeviceRef() }
    }

    override suspend fun patchDevice(
        platform: String,
        externalId: String,
        roomRemoteId: String,
        householdRemoteId: String?,
    ): RemoteDeviceRef = withToken { auth ->
        val response = api.patchMyDevice(
            auth,
            platform,
            externalId,
            DeviceMetaPatchRequest(
                roomId = Reported(roomRemoteId),
                householdId = householdRemoteId?.let(::Reported),
            ),
        )
        response.toDeviceRef() ?: throw IllegalStateException("设备更新响应缺少标识")
    }

    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = tokenStore.token()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("尚未登录，无法执行家庭操作")
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            when (e.code()) {
                403 -> throw HouseholdForbiddenException()
                404 -> throw HouseholdNotFoundException()
                409 -> throw HouseholdConflictException(e.apiErrorCode())
                else -> throw e.toSyncException() ?: e
            }
        }
    }

    private fun HouseholdDto.toSnapshot(): HouseholdSnapshot? {
        val id = id?.takeIf { it.isNotBlank() } ?: return null
        return HouseholdSnapshot(
            remoteId = id,
            name = name?.takeIf { it.isNotBlank() } ?: "家庭",
            isPersonal = personal == true,
            role = HouseholdRole.parse(role),
            memberCount = memberCount ?: 0,
            deviceCount = deviceCount ?: 0,
            sortOrder = 0,
            createdAt = Rfc3339.parse(createdAt) ?: 0L,
            roomCount = roomCount ?: 0,
        )
    }

    private fun HouseholdInvitationInfoDto.toInfo(): HouseholdInvitationInfo? {
        val invitationId = id?.takeIf { it.isNotBlank() } ?: return null
        return HouseholdInvitationInfo(
            invitationId = invitationId,
            codeHashPrefix = codeHashPrefix.orEmpty(),
            status = InvitationStatus.parse(status),
            attempts = attempts ?: 0,
            expiresAt = Rfc3339.parse(expiresAt) ?: 0L,
            createdAt = Rfc3339.parse(createdAt) ?: 0L,
        )
    }

    private fun HouseholdRoomDto.toRoom(): HouseholdRoom? {
        val roomId = id?.takeIf { it.isNotBlank() } ?: return null
        return HouseholdRoom(
            remoteId = roomId,
            householdRemoteId = householdId.orEmpty(),
            name = name?.takeIf { it.isNotBlank() } ?: "房间",
            sortOrder = sortOrder ?: 0,
            createdAt = Rfc3339.parse(createdAt) ?: 0L,
        )
    }

    private fun SyncDeviceDto.toDeviceRef(): RemoteDeviceRef? {
        val platformValue = platform?.takeIf { it.isNotBlank() } ?: return null
        val externalValue = externalId?.takeIf { it.isNotBlank() } ?: return null
        return RemoteDeviceRef(
            platform = platformValue,
            externalId = externalValue,
            householdRemoteId = householdId?.takeIf { it.isNotBlank() },
            roomRemoteId = roomId?.takeIf { it.isNotBlank() },
            alias = alias?.takeIf { it.isNotBlank() },
            updatedAt = Rfc3339.parse(updatedAt) ?: 0L,
        )
    }
}
