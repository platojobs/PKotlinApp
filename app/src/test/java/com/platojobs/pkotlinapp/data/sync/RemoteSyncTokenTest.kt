package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.AnonymousLoginResponse
import com.platojobs.pkotlinapp.data.remote.ReadingPageResponse
import com.platojobs.pkotlinapp.data.remote.ReadingSummaryResponse
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.SyncChangesResponse
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
import com.platojobs.pkotlinapp.data.remote.SyncPushResponse
import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RemoteSyncTokenTest {

    private class FakeTokenStore(var stored: String? = null) : TokenStore {
        override fun token(): String? = stored

        override fun saveToken(token: String) {
            stored = token
        }

        override fun clear() {
            stored = null
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

        override suspend fun anonymousLogin(): AnonymousLoginResponse {
            loginCalls += 1
            return loginResponse
        }

        override suspend fun changes(
            authorization: String,
            cursor: String?,
        ): SyncChangesResponse {
            lastAuthorization = authorization
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
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())),
    )

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
}
