package com.platojobs.pkotlinapp.data.household

import com.platojobs.pkotlinapp.data.remote.DeviceMetaPatchRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdCreateRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdDto
import com.platojobs.pkotlinapp.data.remote.HouseholdInvitationClaimRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdRoomCreateRequest
import com.platojobs.pkotlinapp.data.remote.HouseholdRoomDto
import com.platojobs.pkotlinapp.data.remote.HouseholdRoomPatchRequest
import com.platojobs.pkotlinapp.data.remote.Reported
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.SyncDeviceDto
import com.platojobs.pkotlinapp.data.remote.toSyncException
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.household.HouseholdForbiddenException
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitation
import com.platojobs.pkotlinapp.domain.household.HouseholdMember
import com.platojobs.pkotlinapp.domain.household.HouseholdNotFoundException
import com.platojobs.pkotlinapp.domain.household.HouseholdRemote
import com.platojobs.pkotlinapp.domain.household.HouseholdRole
import com.platojobs.pkotlinapp.domain.household.HouseholdRoom
import com.platojobs.pkotlinapp.domain.household.HouseholdSnapshot
import com.platojobs.pkotlinapp.domain.household.InviteCodeInvalidException
import com.platojobs.pkotlinapp.domain.household.InviteRateLimitedException
import com.platojobs.pkotlinapp.domain.household.RemoteDeviceRef
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import retrofit2.HttpException

/**
 * 家庭接口 Retrofit 适配（openapi 0.8.0 / 后端 v0.12.0）。
 *
 * 错误语义：
 * - 403 → [HouseholdForbiddenException]（member 改家庭/房间）；
 * - 404 → [HouseholdNotFoundException]（跨家庭不可见，避免泄露存在性）；
 * - 领取邀请码的 404 → [InviteCodeInvalidException]，429 → [InviteRateLimitedException]；
 * - 401 沿用同步的 [com.platojobs.pkotlinapp.domain.sync.AuthExpiredException]。
 */
class RemoteHouseholdDataSource(
    private val api: SyncApi,
    private val tokenStore: TokenStore,
) : HouseholdRemote {

    override suspend fun listHouseholds(): List<HouseholdSnapshot> = withToken { auth ->
        api.households(auth).items.orEmpty().mapNotNull { it.toSnapshot() }
    }

    override suspend fun createHousehold(name: String): HouseholdSnapshot = withToken { auth ->
        api.createHousehold(auth, HouseholdCreateRequest(name)).toSnapshot()
            ?: throw IllegalStateException("创建家庭响应缺少 id")
    }

    override suspend fun members(remoteId: String): List<HouseholdMember> = withToken { auth ->
        api.householdMembers(auth, remoteId).items.orEmpty().map {
            HouseholdMember(
                userId = it.userId.orEmpty(),
                role = HouseholdRole.parse(it.role),
                kind = it.kind.orEmpty(),
                joinedAt = Rfc3339.parse(it.joinedAt) ?: 0L,
            )
        }
    }

    override suspend fun createInvitation(remoteId: String): HouseholdInvitation = withToken { auth ->
        val response = api.createHouseholdInvitation(auth, remoteId)
        val code = response.code?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("邀请码响应缺少 code")
        HouseholdInvitation(code = code, expiresAt = Rfc3339.parse(response.expiresAt) ?: 0L)
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
