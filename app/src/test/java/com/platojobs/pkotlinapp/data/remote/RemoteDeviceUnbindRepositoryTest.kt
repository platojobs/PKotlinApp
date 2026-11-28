package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.data.sync.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 解绑语义：204（本次软删）与 404（早已不存在）都当成功；
 * 其他状态码抛错，由 UI 提示并保留本地数据；未登录不发起请求。
 */
class RemoteDeviceUnbindRepositoryTest {

    private class FakeTokenStore(private val value: String?) : TokenStore {
        override fun token(): String? = value

        override fun saveToken(token: String) = Unit

        override fun clear() = Unit
    }

    private class FakeApi(
        var response: Response<Unit> = Response.success(204, Unit),
    ) : ApiService {
        var auth: String? = null
        var platform: String? = null
        var externalId: String? = null
        var calls = 0

        override suspend fun deleteMyDevice(
            authorization: String,
            platform: String,
            externalId: String,
        ): Response<Unit> {
            calls++
            auth = authorization
            this.platform = platform
            this.externalId = externalId
            return response
        }

        override suspend fun getDeviceInfo(mac: String): PublicDeviceDto = error("unused")

        override suspend fun getDeviceTypes(): DeviceTypesResponse = error("unused")

        override suspend fun getDeviceCatalog(type: String): DeviceCatalogDto = error("unused")

        override suspend fun getDeviceTypeCatalog(
            ifNoneMatch: String?,
            ifModifiedSince: String?,
        ): Response<DeviceTypeCatalogDto> = error("unused")

        override suspend fun getMyDevice(
            authorization: String,
            platform: String,
            externalId: String,
        ): UserDeviceDto = error("unused")
    }

    @Test
    fun `204 and 404 are treated as already removed`() = runBlocking {
        listOf(204, 404).forEach { code ->
            val api = FakeApi(
                if (code == 204) {
                    Response.success(204, Unit)
                } else {
                    Response.error(404, "{}".toResponseBody("application/json".toMediaType()))
                },
            )
            RemoteDeviceUnbindRepository(api, FakeTokenStore("t")).deleteOwnedDevice(
                "android",
                "AA:BB:CC:00:00:77",
            )
            assertEquals("Bearer t", api.auth)
            assertEquals("android", api.platform)
            assertEquals("AA:BB:CC:00:00:77", api.externalId)
        }
    }

    @Test
    fun `other status codes surface as errors`() {
        val api = FakeApi(
            Response.error(
                500,
                """{"error":{"code":"INTERNAL"}}""".toResponseBody("application/json".toMediaType()),
            ),
        )
        val repository = RemoteDeviceUnbindRepository(api, FakeTokenStore("t"))
        val error = assertThrows(HttpException::class.java) {
            runBlocking { repository.deleteOwnedDevice("android", "AA:BB:CC:00:00:77") }
        }
        assertEquals(500, error.code())
        assertEquals(1, api.calls)
    }

    @Test
    fun `missing token does not call the api`() {
        val api = FakeApi()
        val repository = RemoteDeviceUnbindRepository(api, FakeTokenStore(null))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteOwnedDevice("android", "AA:BB:CC:00:00:77") }
        }
        assertEquals(0, api.calls)
        assertNull(api.auth)
    }
}
