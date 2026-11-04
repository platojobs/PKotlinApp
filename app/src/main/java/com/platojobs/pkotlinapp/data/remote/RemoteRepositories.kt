package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.domain.model.DeviceInfo
import com.platojobs.pkotlinapp.domain.model.GattServiceSpec
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.repository.DeviceInfoRepository
import com.platojobs.pkotlinapp.domain.repository.MarketRepository
import com.platojobs.pkotlinapp.domain.repository.ProductRepository

class DeviceInfoRepositoryImpl(
    private val api: ApiService,
) : DeviceInfoRepository {

    override suspend fun fetchDeviceInfo(mac: String): DeviceInfo {
        val dto = api.getDeviceInfo(mac)
        return DeviceInfo(
            mac = dto.mac ?: mac,
            vendor = dto.vendor.orEmpty(),
            deviceType = dto.deviceType.orEmpty(),
            firmwareVersion = dto.firmwareVersion.orEmpty(),
        )
    }
}

class ProductRepositoryImpl(
    private val api: ApiService,
) : ProductRepository {

    override suspend fun fetchProducts(): List<ProductModel> =
        api.getProducts().products.orEmpty().mapIndexed { index, dto ->
            ProductModel(
                id = dto.id ?: "product-$index",
                vendor = dto.vendor.orEmpty(),
                model = dto.model.orEmpty(),
                firmwareVersion = dto.firmwareVersion.orEmpty(),
                note = dto.note.orEmpty(),
                services = dto.services.orEmpty().map { service ->
                    GattServiceSpec(
                        uuid = service.uuid.orEmpty(),
                        name = service.name.orEmpty(),
                        characteristics = service.characteristics.orEmpty(),
                    )
                },
            )
        }
}

/** 市场模块的数据源抽象：后续接入真实商品后端时，只需新增实现并替换注入。 */
interface MarketDataSource {
    suspend fun loadItems(): List<MarketItem>
}

class RemoteMarketDataSource(
    private val api: ApiService,
) : MarketDataSource {

    override suspend fun loadItems(): List<MarketItem> =
        api.getMarketItems().marketItems.orEmpty().mapIndexed { index, dto ->
            MarketItem(
                id = dto.id ?: "market-$index",
                title = dto.title.orEmpty(),
                category = dto.category.orEmpty(),
                version = dto.version.orEmpty(),
                compatibility = dto.compatibility.orEmpty(),
                description = dto.description.orEmpty(),
            )
        }
}

class MarketRepositoryImpl(
    private val dataSource: MarketDataSource,
) : MarketRepository {

    override suspend fun fetchMarketItems(): List<MarketItem> = dataSource.loadItems()
}
