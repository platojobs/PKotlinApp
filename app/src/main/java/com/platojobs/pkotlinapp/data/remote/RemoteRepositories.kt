package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.data.local.DeviceDao
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
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException

/**
 * 设备详情（openapi 0.3.2）：
 * 1. `GET /my/devices/android/{externalId}` 校验归属并直接拿到 enrich 字段；
 * 2. 仅在 enrich 缺失或设备尚未同步（404）时，用 `/devices/{mac}` 与 catalog 兜底；
 * 3. 成功结果写入 Room enrich 缓存，断网时由 ViewModel 读取本地缓存降级展示。
 */
class DeviceInfoRepositoryImpl(
    private val api: ApiService,
    private val syncApi: SyncApi,
    private val tokenStore: TokenStore,
    private val deviceDao: DeviceDao? = null,
) : DeviceInfoRepository {

    override suspend fun fetchDeviceInfo(mac: String): DeviceInfo {
        val authorization = "Bearer ${ensureToken()}"
        val externalId = normalizeExternalId(mac)

        val myDevice = try {
            api.getMyDevice(authorization, PLATFORM_ANDROID, externalId)
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> throw AuthExpiredException(cause = e)
                // 设备尚未同步到服务端时没有 enrich，退回到公共元数据。
                404 -> null
                else -> throw e
            }
        }

        // 已有用户设备时，公共元数据/catalog 只作为字段缺失时的补充，失败不影响详情。
        val public = if (myDevice != null) {
            runCatching { api.getDeviceInfo(mac) }.getOrNull()
        } else {
            api.getDeviceInfo(mac)
        }

        val typeKey = firstNotBlank(
            myDevice?.deviceType,
            public?.deviceType,
        )
        val catalog = typeKey?.let {
            runCatching { api.getDeviceCatalog(it) }.getOrNull()
        }
        val latestFirmware = catalog?.firmwares.orEmpty()
            .maxByOrNull { it.releasedAt.orEmpty() }

        val vendor = firstNotBlank(
            myDevice?.vendor,
            catalog?.deviceType?.vendor,
            public?.vendor,
        )
        val firmwareVersion = firstNotBlank(
            myDevice?.firmwareVersion,
            latestFirmware?.version,
            public?.firmwareVersion,
        )
        val compatibilityDto = myDevice?.compatibility
            ?: catalog?.deviceType?.compatibility
            ?: public?.compatibility
        val compatibility = compatibilityDto?.toDomain()

        val latestScan = myDevice?.scanRecords.orEmpty()
            .maxByOrNull { Rfc3339.parse(it.at) ?: Long.MIN_VALUE }
        val lastSeen = latestScan?.let { Rfc3339.parse(it.at) }?.takeIf { it > 0 }
            ?: Rfc3339.parse(myDevice?.updatedAt)
            ?: 0L

        val info = DeviceInfo(
            mac = public?.mac ?: myDevice?.mac ?: mac,
            vendor = vendor,
            // 只保存人类可读型号名；没有展示名时 UI 回退型号键并标注「未识别的型号」。
            deviceType = catalog?.deviceType?.name?.takeIf { it.isNotBlank() },
            deviceTypeKey = typeKey,
            firmwareVersion = firmwareVersion,
            alias = myDevice?.alias.orEmpty(),
            group = myDevice?.group.orEmpty(),
            lastSeenTimestamp = lastSeen,
            compatibility = compatibility,
        )

        // 只缓存 enrich 字段，不触碰同步状态与本地修改时间。
        deviceDao?.updateEnrich(
            externalId = externalId,
            deviceType = typeKey,
            vendor = vendor,
            firmwareVersion = firmwareVersion,
            compatibility = compatibility?.displayLabel()?.takeIf { it.isNotBlank() },
            deviceTypeName = catalog?.deviceType?.name?.takeIf { it.isNotBlank() },
        )
        // 未同步设备（myDevice = null）没有云端分组，保留本地编辑的值。
        myDevice?.group?.takeIf { it.isNotBlank() }?.let { group ->
            deviceDao?.updateGroup(externalId = externalId, group = group)
        }

        return info
    }

    private suspend fun ensureToken(): String {
        tokenStore.token()?.let { return it }
        val response = syncApi.anonymousLogin()
        val token = response.token
            ?: throw AuthExpiredException("匿名登录未返回 token")
        tokenStore.saveToken(token)
        return token
    }

    private fun firstNotBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
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

/** 市场：聚合各设备类型的 accessories，使用真实的厂商与兼容性维度筛选。 */
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
                        val compatibility = accessory.compatibility?.toDomain()
                        // 兼容性标签只取接口真实返回的维度；没有时退回设备类型，不造假分类。
                        val tags = compatibility?.filterTags().orEmpty()
                            .ifEmpty { listOfNotNull(type.type?.takeIf { it.isNotBlank() }) }
                        MarketItem(
                            id = accessory.id ?: "${type.type}-$index",
                            title = accessory.name.orEmpty(),
                            category = deviceName,
                            version = accessory.sku.orEmpty(),
                            compatibility = compatibility?.displayLabel()
                                ?.takeIf { it.isNotBlank() }
                                ?: tags.joinToString("/"),
                            description = accessory.description.orEmpty(),
                            vendor = accessory.vendor.orEmpty(),
                            compatibilityTags = tags,
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
