package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.AnonymousLoginResponse
import com.platojobs.pkotlinapp.data.remote.DeletionRequestResponse
import com.platojobs.pkotlinapp.data.remote.ExportTaskResponse
import com.platojobs.pkotlinapp.data.remote.FeedbackCloseRequest
import com.platojobs.pkotlinapp.data.remote.FeedbackDetailDto
import com.platojobs.pkotlinapp.data.remote.PairingClaimRequest
import com.platojobs.pkotlinapp.data.remote.PairingClaimResponse
import com.platojobs.pkotlinapp.data.remote.PairingCodeResponse
import com.platojobs.pkotlinapp.data.remote.ReadingMetricsResponse
import com.platojobs.pkotlinapp.data.remote.ReadingPageResponse
import com.platojobs.pkotlinapp.data.remote.ReadingSeriesResponse
import com.platojobs.pkotlinapp.data.remote.ReadingSummaryResponse
import com.platojobs.pkotlinapp.data.remote.SessionListResponse
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.SyncChangesResponse
import com.platojobs.pkotlinapp.data.remote.SyncDeviceDto
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
import com.platojobs.pkotlinapp.data.remote.SyncPushResponse
import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import com.platojobs.pkotlinapp.domain.sync.CursorExpiredException
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import com.platojobs.pkotlinapp.domain.sync.ServerBusyException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RemoteSyncTokenTest {

    private class FakeTokenStore(var stored: String? = null) : TokenStore {
        var expiresAt: Long? = null
        var relogin = false

        override fun reloginRequired(): Boolean = relogin
        override fun setReloginRequired(required: Boolean) {
            relogin = required
        }

        override fun token(): String? = stored

        override fun saveToken(token: String) {
            stored = token
        }

        override fun saveSession(token: String, expiresAt: Long?) {
            stored = token
            this.expiresAt = expiresAt
        }

        override fun sessionExpiresAt(): Long? = expiresAt

        override fun clear() {
            stored = null
            expiresAt = null
        }
    }

    private class FakeSyncApi(
        private val loginResponse: AnonymousLoginResponse = AnonymousLoginResponse(
            token = "fresh-token",
            userId = "user-1",
        ),
        private val changesError: HttpException? = null,
    ) : SyncApi {
        var loginCalls = 0
        var lastAuthorization: String? = null
        var lastChangesLimit: Int? = null

        override suspend fun anonymousLogin(): AnonymousLoginResponse {
            loginCalls += 1
            return loginResponse
        }

        override suspend fun refreshToken(authorization: String): AnonymousLoginResponse {
            loginCalls += 1
            return loginResponse
        }

        override suspend fun logout(authorization: String) = Unit

        override suspend fun listSessions(authorization: String): SessionListResponse = SessionListResponse()

        override suspend fun revokeSession(authorization: String, id: String) = Unit

        override suspend fun getMyFeedback(authorization: String, id: String): FeedbackDetailDto =
            FeedbackDetailDto()

        override suspend fun closeMyFeedback(
            authorization: String,
            id: String,
            body: FeedbackCloseRequest,
        ): FeedbackDetailDto = FeedbackDetailDto()

        override suspend fun changes(
            authorization: String,
            cursor: String?,
            limit: Int,
        ): SyncChangesResponse {
            lastAuthorization = authorization
            lastChangesLimit = limit
            changesError?.let { throw it }
            return SyncChangesResponse(changes = emptyList(), nextCursor = 0)
        }

        override suspend fun push(
            authorization: String,
            idempotencyKey: String,
            body: SyncPushRequest,
        ): SyncPushResponse = SyncPushResponse()

        override suspend fun readings(
            authorization: String,
            platform: String,
            externalId: String,
            metric: String?,
            from: String?,
            to: String?,
            limit: Int,
            cursor: String?,
        ): ReadingPageResponse = ReadingPageResponse()

        override suspend fun readingsSummary(
            authorization: String,
            platform: String,
            externalId: String,
            metric: String,
            hours: Int,
        ): ReadingSummaryResponse = ReadingSummaryResponse()

        override suspend fun readingsSeries(
            authorization: String,
            platform: String,
            externalId: String,
            metric: String,
            from: String,
            to: String,
            bucket: String,
        ): ReadingSeriesResponse = ReadingSeriesResponse()

        override suspend fun readingsMetrics(
            authorization: String,
            platform: String,
            externalId: String,
        ): ReadingMetricsResponse = ReadingMetricsResponse()

        override suspend fun createPairingCode(authorization: String): PairingCodeResponse =
            PairingCodeResponse()

        override suspend fun claimPairingCode(body: PairingClaimRequest): PairingClaimResponse =
            PairingClaimResponse()

        override suspend fun createMyDataExport(authorization: String): ExportTaskResponse =
            ExportTaskResponse()

        override suspend fun myDataExport(authorization: String, id: String): ExportTaskResponse =
            ExportTaskResponse()

        override suspend fun createDeletionRequest(authorization: String): DeletionRequestResponse =
            DeletionRequestResponse()

        override suspend fun deleteMyAccount(deletionToken: String) = Unit

        override suspend fun households(
            authorization: String,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdListResponse =
            com.platojobs.pkotlinapp.data.remote.HouseholdListResponse()

        override suspend fun createHousehold(
            authorization: String,
            body: com.platojobs.pkotlinapp.data.remote.HouseholdCreateRequest,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdDto =
            com.platojobs.pkotlinapp.data.remote.HouseholdDto()

        override suspend fun household(
            authorization: String,
            id: String,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdDto =
            com.platojobs.pkotlinapp.data.remote.HouseholdDto()

        override suspend fun patchHousehold(
            authorization: String,
            id: String,
            body: com.platojobs.pkotlinapp.data.remote.HouseholdPatchRequest,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdDto =
            com.platojobs.pkotlinapp.data.remote.HouseholdDto()

        override suspend fun deleteHousehold(authorization: String, id: String) = Unit

        override suspend fun myDevices(
            authorization: String,
        ): com.platojobs.pkotlinapp.data.remote.MyDevicesResponse =
            com.platojobs.pkotlinapp.data.remote.MyDevicesResponse()

        override suspend fun patchMyDevice(
            authorization: String,
            platform: String,
            externalId: String,
            body: com.platojobs.pkotlinapp.data.remote.DeviceMetaPatchRequest,
        ): SyncDeviceDto = SyncDeviceDto()

        override suspend fun householdMembers(
            authorization: String,
            id: String,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdMemberListResponse =
            com.platojobs.pkotlinapp.data.remote.HouseholdMemberListResponse()

        override suspend fun createHouseholdInvitation(
            authorization: String,
            id: String,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdInvitationDto =
            com.platojobs.pkotlinapp.data.remote.HouseholdInvitationDto()

        override suspend fun claimHouseholdInvitation(
            authorization: String,
            body: com.platojobs.pkotlinapp.data.remote.HouseholdInvitationClaimRequest,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdDto =
            com.platojobs.pkotlinapp.data.remote.HouseholdDto()

        override suspend fun householdRooms(
            authorization: String,
            id: String,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdRoomListResponse =
            com.platojobs.pkotlinapp.data.remote.HouseholdRoomListResponse()

        override suspend fun createHouseholdRoom(
            authorization: String,
            id: String,
            body: com.platojobs.pkotlinapp.data.remote.HouseholdRoomCreateRequest,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdRoomDto =
            com.platojobs.pkotlinapp.data.remote.HouseholdRoomDto()

        override suspend fun patchHouseholdRoom(
            authorization: String,
            id: String,
            roomId: String,
            body: com.platojobs.pkotlinapp.data.remote.HouseholdRoomPatchRequest,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdRoomDto =
            com.platojobs.pkotlinapp.data.remote.HouseholdRoomDto()

        override suspend fun deleteHouseholdRoom(
            authorization: String,
            id: String,
            roomId: String,
        ) = Unit

        override suspend fun removeHouseholdMember(
            authorization: String,
            id: String,
            userId: String,
        ) = Unit

        override suspend fun leaveHousehold(
            authorization: String,
            id: String,
        ) = Unit

        override suspend fun transferHouseholdOwnership(
            authorization: String,
            id: String,
            body: com.platojobs.pkotlinapp.data.remote.HouseholdOwnershipTransferRequest,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdDto =
            com.platojobs.pkotlinapp.data.remote.HouseholdDto()

        override suspend fun householdInvitations(
            authorization: String,
            id: String,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdInvitationInfoListResponse =
            com.platojobs.pkotlinapp.data.remote.HouseholdInvitationInfoListResponse()

        override suspend fun revokeHouseholdInvitation(
            authorization: String,
            id: String,
            invitationId: String,
        ) = Unit

        override suspend fun reorderHouseholdRooms(
            authorization: String,
            id: String,
            body: com.platojobs.pkotlinapp.data.remote.HouseholdRoomOrderRequest,
        ): com.platojobs.pkotlinapp.data.remote.HouseholdRoomListResponse =
            com.platojobs.pkotlinapp.data.remote.HouseholdRoomListResponse()

        override suspend fun createFeedback(
            authorization: String,
            idempotencyKey: String,
            body: com.platojobs.pkotlinapp.data.remote.FeedbackCreateRequest,
        ): com.platojobs.pkotlinapp.data.remote.FeedbackCreatedDto =
            com.platojobs.pkotlinapp.data.remote.FeedbackCreatedDto()

        override suspend fun listMyFeedback(
            authorization: String,
            cursor: String?,
            limit: Int,
        ): com.platojobs.pkotlinapp.data.remote.FeedbackPageResponse =
            com.platojobs.pkotlinapp.data.remote.FeedbackPageResponse()
    }

    private fun httpError(code: Int, body: String = "") = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
    )

    /** 刷新失败后 token 为空时必须进入重新登录态，而不是静默新建匿名账号。 */
    @Test
    fun `relogin required blocks silent anonymous login`() {
        val tokenStore = FakeTokenStore(null).apply { relogin = true }
        val api = FakeSyncApi()
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        assertThrows(AuthExpiredException::class.java) {
            runBlocking { dataSource.pull(null) }
        }
        assertEquals(0, api.loginCalls)
        assertNull(tokenStore.token())
    }

    @Test
    fun `401 does not relogin and keeps existing token`() {
        val tokenStore = FakeTokenStore("expired-token")
        val api = FakeSyncApi(changesError = httpError(401))
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        assertThrows(AuthExpiredException::class.java) {
            runBlocking { dataSource.pull(null) }
        }
        // 不自动匿名登录：否则会切换到另一个账号。
        assertEquals(0, api.loginCalls)
        assertEquals("expired-token", tokenStore.token())
        assertEquals("Bearer expired-token", api.lastAuthorization)
    }

    @Test
    fun `missing token logs in anonymously once and stores it`() {
        val tokenStore = FakeTokenStore(null)
        val api = FakeSyncApi()
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        runBlocking { dataSource.pull(null) }

        assertEquals(1, api.loginCalls)
        assertEquals("fresh-token", tokenStore.token())
        assertEquals("Bearer fresh-token", api.lastAuthorization)
    }

    @Test
    fun `non 401 http error is propagated unchanged`() {
        val tokenStore = FakeTokenStore("valid-token")
        val api = FakeSyncApi(changesError = httpError(500))
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        val error = assertThrows(HttpException::class.java) {
            runBlocking { dataSource.pull(null) }
        }

        assertEquals(500, error.code())
        assertEquals("valid-token", tokenStore.token())
        assertEquals(0, api.loginCalls)
    }

    @Test
    fun `missing token without login token is not reported as auth expiry`() {
        val tokenStore = FakeTokenStore(null)
        val api = FakeSyncApi(loginResponse = AnonymousLoginResponse(token = null))
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { dataSource.pull(null) }
        }
        assertNull(tokenStore.token())
    }

    @Test
    fun `anonymous login stores expiresAt from response`() {
        val tokenStore = FakeTokenStore(null)
        val expiresAtRaw = "2027-05-04T12:00:00Z"
        val api = FakeSyncApi(
            loginResponse = AnonymousLoginResponse(
                token = "fresh-token",
                userId = "user-1",
                expiresAt = expiresAtRaw,
            ),
        )
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        runBlocking { dataSource.pull(null) }

        val expiresAt = tokenStore.sessionExpiresAt()
        assertNotNull(expiresAt)
        assertEquals(Rfc3339.parse(expiresAtRaw), expiresAt)
    }

    @Test
    fun `410 RESYNC_REQUIRED maps to cursor expired`() {
        val tokenStore = FakeTokenStore("valid-token")
        val api = FakeSyncApi(
            changesError = httpError(
                410,
                """{"error":{"code":"RESYNC_REQUIRED","message":"cursor too old"}}""",
            ),
        )
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        assertThrows(CursorExpiredException::class.java) {
            runBlocking { dataSource.pull("12") }
        }
        assertEquals("valid-token", tokenStore.token())
        assertEquals(0, api.loginCalls)
    }

    @Test
    fun `503 TIMEOUT maps to server busy not resync`() {
        val tokenStore = FakeTokenStore("valid-token")
        val api = FakeSyncApi(
            changesError = httpError(
                503,
                """{"error":{"code":"TIMEOUT","message":"query timeout"}}""",
            ),
        )
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        assertThrows(ServerBusyException::class.java) {
            runBlocking { dataSource.pull("12") }
        }
        // 不能误判为 410（清游标）或 401（重登）。
        assertEquals("valid-token", tokenStore.token())
        assertEquals(0, api.loginCalls)
    }

    @Test
    fun `changes request uses contract limit 500`() {
        val tokenStore = FakeTokenStore("valid-token")
        val api = FakeSyncApi()
        val dataSource = RemoteSyncDataSource(api, tokenStore, "install-1")

        runBlocking { dataSource.pull(null) }

        assertEquals(500, api.lastChangesLimit)
        assertTrue(api.lastChangesLimit!! <= com.platojobs.pkotlinapp.data.remote.ApiLimits.CHANGES_MAX)
    }
}
