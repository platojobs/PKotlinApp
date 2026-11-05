package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.model.unrecognizedDeviceTypeKey
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

class CatalogMappingTest {

    private class FakeTokenStore(var stored: String? = "token-1") : TokenStore {
        override fun token(): String? = stored

        override fun saveToken(token: String) {
            stored = token
        }

        override fun clear() {
            stored = null
        }
    }

    private class FakeSyncApi(private val issuedToken: String? = "token-new") : SyncApi {
        var loginCalls = 0

        override suspend fun anonymousLogin(): AnonymousLoginResponse {
            loginCalls += 1
            return AnonymousLoginResponse(token = issuedToken, userId = "user-1")
        }

        override suspend fun changes(
            authorization: String,
            cursor: String?,
        ): SyncChangesResponse = SyncChangesResponse(changes = emptyList(), nextCursor = 0)

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

    private class FakeApi(
        var types: List<DeviceTypeDto> = emptyList(),
        var catalogs: Map<String, DeviceCatalogDto> = emptyMap(),
        var publicDevice: PublicDeviceDto = PublicDeviceDto(),
        var myDeviceError: HttpException? = null,
    ) : ApiService {
        var lastPlatform: String? = null
        var lastExternalId: String? = null
        var lastAuthorization: String? = null
        var myDeviceCalls = 0

        override suspend fun getDeviceInfo(mac: String): PublicDeviceDto = publicDevice

        override suspend fun getDeviceTypes(): DeviceTypesResponse =
            DeviceTypesResponse(items = types)

        override suspend fun getDeviceCatalog(type: String): DeviceCatalogDto =
            catalogs[type] ?: DeviceCatalogDto(deviceType = types.firstOrNull { it.type == type })

        override suspend fun getMyDevice(
            authorization: String,
            platform: String,
            externalId: String,
        ): UserDeviceDto {
            myDeviceCalls += 1
            lastAuthorization = authorization
            lastPlatform = platform
            lastExternalId = externalId
            myDeviceError?.let { throw it }
            return UserDeviceDto(
                id = "device-1",
                platform = platform,
                externalId = externalId,
            )
        }
    }

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "".toResponseBody("application/json".toMediaType())),
    )

    private fun sensorCatalog() = DeviceCatalogDto(
        deviceType = DeviceTypeDto(
            type = "environmental_sensor",
            name = "环境传感器",
            vendor = "PJ Labs",
            description = "温湿度采集",
            gattServices = listOf(GattServiceDto(uuid = "0x181A", name = "Environmental Sensing")),
        ),
        firmwares = listOf(
            FirmwareDto(id = "fw-1", version = "1.0.0", releasedAt = "2025-01-01"),
            FirmwareDto(id = "fw-2", version = "1.2.0", releasedAt = "2026-03-01"),
        ),
        accessories = listOf(
            AccessoryDto(
                id = "acc-1",
                deviceType = "environmental_sensor",
                name = "防水外壳",
                sku = "SKU-SENSOR-CASE",
                description = "IP67 外壳",
            ),
        ),
    )

    @Test
    fun `products map catalog and pick latest firmware`() = runBlocking {
        val api = FakeApi(
            types = listOf(
                DeviceTypeDto(type = "environmental_sensor", name = "旧名称", vendor = "旧厂商"),
            ),
            catalogs = mapOf("environmental_sensor" to sensorCatalog()),
        )

        val products = ProductRepositoryImpl(api).fetchProducts()

        assertEquals(1, products.size)
        assertEquals("environmental_sensor", products[0].id)
        assertEquals("环境传感器", products[0].model)
        assertEquals("PJ Labs", products[0].vendor)
        assertEquals("1.2.0", products[0].firmwareVersion)
        assertEquals("温湿度采集", products[0].note)
        assertEquals("0x181A", products[0].services.single().uuid)
    }

    @Test
    fun `market flattens accessories from catalogs`() = runBlocking {
        val api = FakeApi(
            types = listOf(DeviceTypeDto(type = "environmental_sensor", name = "环境传感器")),
            catalogs = mapOf("environmental_sensor" to sensorCatalog()),
        )

        val items = RemoteMarketDataSource(api).loadItems()

        assertEquals(1, items.size)
        assertEquals("acc-1", items[0].id)
        assertEquals("防水外壳", items[0].title)
        assertEquals("环境传感器", items[0].category)
        assertEquals("SKU-SENSOR-CASE", items[0].version)
        assertEquals("environmental_sensor", items[0].compatibility)
        assertEquals("IP67 外壳", items[0].description)
    }

    @Test
    fun `device detail uses compound key with normalized uppercase mac`() = runBlocking {
        val api = FakeApi(
            types = listOf(DeviceTypeDto(type = "environmental_sensor", name = "环境传感器")),
            catalogs = mapOf("environmental_sensor" to sensorCatalog()),
            publicDevice = PublicDeviceDto(
                mac = "AA:BB:CC:DD:EE:FF",
                vendor = "公共厂商",
                deviceType = "environmental_sensor",
                firmwareVersion = "0.9.0",
            ),
        )

        val info = DeviceInfoRepositoryImpl(
            api = api,
            syncApi = FakeSyncApi(),
            tokenStore = FakeTokenStore("token-1"),
        ).fetchDeviceInfo("aa:bb:cc:dd:ee:ff")

        assertEquals("android", api.lastPlatform)
        assertEquals("AA:BB:CC:DD:EE:FF", api.lastExternalId)
        assertEquals("Bearer token-1", api.lastAuthorization)
        assertEquals("PJ Labs", info.vendor)
        assertEquals("环境传感器", info.deviceType)
        assertEquals("1.2.0", info.firmwareVersion)
    }

    @Test
    fun `device detail tolerates 404 from my devices and falls back to public metadata`() {
        val api = FakeApi(
            publicDevice = PublicDeviceDto(
                mac = "AA:BB:CC:DD:EE:FF",
                vendor = "公共厂商",
                deviceType = "unknown_type",
                firmwareVersion = "0.9.0",
            ),
            myDeviceError = httpError(404),
        )

        val info = runBlocking {
            DeviceInfoRepositoryImpl(api, FakeSyncApi(), FakeTokenStore("token-1"))
                .fetchDeviceInfo("AA:BB:CC:DD:EE:FF")
        }

        assertEquals(1, api.myDeviceCalls)
        assertEquals("公共厂商", info.vendor)
        // 没有 catalog 展示名时保留型号键，由 UI 标注「未识别的型号」。
        assertNull(info.deviceType)
        assertEquals("unknown_type", info.deviceTypeKey)
        assertEquals("unknown_type", info.unrecognizedDeviceTypeKey())
        assertEquals("0.9.0", info.firmwareVersion)
    }

    @Test
    fun `device detail 401 is reported as auth expired without relogin`() {
        val syncApi = FakeSyncApi()
        val tokenStore = FakeTokenStore("token-1")
        val api = FakeApi(myDeviceError = httpError(401))
        val repository = DeviceInfoRepositoryImpl(api, syncApi, tokenStore)

        assertThrows(AuthExpiredException::class.java) {
            runBlocking { repository.fetchDeviceInfo("AA:BB:CC:DD:EE:FF") }
        }
        // 不自动换账号重登，token 保持不变。
        assertEquals(0, syncApi.loginCalls)
        assertEquals("token-1", tokenStore.stored)
    }

    @Test
    fun `missing token logs in anonymously before querying my device`() {
        val tokenStore = FakeTokenStore(null)
        val syncApi = FakeSyncApi()
        val api = FakeApi(
            publicDevice = PublicDeviceDto(
                mac = "AA:BB:CC:DD:EE:FF",
                deviceType = "environmental_sensor",
            ),
        )

        runBlocking {
            DeviceInfoRepositoryImpl(api, syncApi, tokenStore)
                .fetchDeviceInfo("AA:BB:CC:DD:EE:FF")
        }

        assertEquals(1, syncApi.loginCalls)
        assertEquals("token-new", tokenStore.stored)
        assertEquals("Bearer token-new", api.lastAuthorization)
    }

    @Test
    fun `device without type skips catalog`() {
        val api = FakeApi(
            publicDevice = PublicDeviceDto(
                mac = "AA:BB:CC:DD:EE:FF",
                vendor = "公共厂商",
                firmwareVersion = "0.9.0",
            ),
        )

        val info = runBlocking {
            DeviceInfoRepositoryImpl(api, FakeSyncApi(), FakeTokenStore("token-1"))
                .fetchDeviceInfo("AA:BB:CC:DD:EE:FF")
        }

        assertEquals("公共厂商", info.vendor)
        assertEquals("0.9.0", info.firmwareVersion)
    }
}
