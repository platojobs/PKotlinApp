package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.model.DeviceInfo
import com.platojobs.pkotlinapp.domain.model.GattServiceSpec
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.model.normalizeExternalId
import com.platojobs.pkotlinapp.domain.repository.DeviceInfoRepository
import com.platojobs.pkotlinapp.domain.repository.MarketRepository
import com.platojobs.pkotlinapp.domain.repository.ProductRepository
import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException

/**
 * 设备详情：
 * 1. `GET /my/devices/android/{externalId}` 校验设备归属（404 表示尚未同步，可继续）；
 * 2. `GET /devices/{mac}` 取得 deviceType；
 * 3. `GET /device-types/{type}/catalog` 取厂商/型号与最新固件。
 */
class DeviceInfoRepositoryImpl(
    private val api: ApiService,
    private val syncApi: SyncApi,
    private val tokenStore: TokenStore,
) : DeviceInfoRepository {

    override suspend fun fetchDeviceInfo(mac: String): DeviceInfo {
        val authorization = "Bearer ${ensureToken()}"

        try {
            // 复合键查询：externalId 必须是规范化大写 MAC。
            api.getMyDevice(authorization, PLATFORM_ANDROID, normalizeExternalId(mac))
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> throw AuthExpiredException(cause = e)
                // 设备尚未同步到服务端时继续使用公共元数据，不影响详情展示。
                404 -> Unit
                else -> throw e
            }
        }

        val public = api.getDeviceInfo(mac)
        val typeKey = public.deviceType
        val catalog = if (typeKey.isNullOrBlank()) {
            null
        } else {
            runCatching { api.getDeviceCatalog(typeKey) }.getOrNull()
        }
        val latestFirmware = catalog?.firmwares.orEmpty()
            .maxByOrNull { it.releasedAt.orEmpty() }

        return DeviceInfo(
            mac = public.mac ?: mac,
            vendor = catalog?.deviceType?.vendor ?: public.vendor.orEmpty(),
            deviceType = catalog?.deviceType?.name ?: typeKey.orEmpty(),
            firmwareVersion = latestFirmware?.version
                ?: public.firmwareVersion.orEmpty(),
        )
    }

    private suspend fun ensureToken(): String {
        tokenStore.token()?.let { return it }
        val response = syncApi.anonymousLogin()
        val token = response.token
            ?: throw AuthExpiredException("匿名登录未返回 token")
        tokenStore.saveToken(token)
        return token
    }
}

/** 产品：设备类型列表 + 每个类型的聚合 catalog（固件与配件一次取回）。 */
class ProductRepositoryImpl(
    private val api: ApiService,
) : ProductRepository {

    override suspend fun fetchProducts(): List<ProductModel> = coroutineScope {
        api.getDeviceTypes().items.orEmpty()
            .filter { !it.type.isNullOrBlank() }
            .map { type ->
                async {
                    val catalog = api.getDeviceCatalog(type.type!!)
                    val latestFirmware = catalog.firmwares.orEmpty()
                        .maxByOrNull { it.releasedAt.orEmpty() }
                    ProductModel(
                        id = type.type.orEmpty(),
                        vendor = catalog.deviceType?.vendor ?: type.vendor.orEmpty(),
                        model = catalog.deviceType?.name ?: type.name.orEmpty(),
                        firmwareVersion = latestFirmware?.version.orEmpty(),
                        note = catalog.deviceType?.description ?: type.description.orEmpty(),
                        services = (catalog.deviceType?.gattServices ?: type.gattServices)
                            .orEmpty()
                            .map { service ->
                                GattServiceSpec(
                                    uuid = service.uuid.orEmpty(),
                                    name = service.name.orEmpty(),
                                    characteristics = emptyList(),
                                )
                            },
                    )
                }
            }
            .awaitAll()
    }
}

/** 市场模块的数据源抽象：后续接入真实商品后端时，只需新增实现并替换注入。 */
interface MarketDataSource {
    suspend fun loadItems(): List<MarketItem>
}

/** 市场：聚合各设备类型的 accessories，不再使用独立的历史市场端点。 */
class RemoteMarketDataSource(
    private val api: ApiService,
) : MarketDataSource {

    override suspend fun loadItems(): List<MarketItem> = coroutineScope {
        api.getDeviceTypes().items.orEmpty()
            .filter { !it.type.isNullOrBlank() }
            .map { type ->
                async {
                    val catalog = api.getDeviceCatalog(type.type!!)
                    val deviceName = catalog.deviceType?.name ?: type.name.orEmpty()
                    catalog.accessories.orEmpty().mapIndexed { index, accessory ->
                        MarketItem(
                            id = accessory.id ?: "${type.type}-$index",
                            title = accessory.name.orEmpty(),
                            category = deviceName,
                            version = accessory.sku.orEmpty(),
                            compatibility = type.type.orEmpty(),
                            description = accessory.description.orEmpty(),
                        )
                    }
                }
            }
            .awaitAll()
            .flatten()
    }
}

class MarketRepositoryImpl(
    private val dataSource: MarketDataSource,
) : MarketRepository {

    override suspend fun fetchMarketItems(): List<MarketItem> = dataSource.loadItems()
}
