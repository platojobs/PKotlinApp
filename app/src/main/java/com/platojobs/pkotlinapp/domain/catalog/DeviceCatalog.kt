package com.platojobs.pkotlinapp.domain.catalog

import com.platojobs.pkotlinapp.domain.model.Compatibility
import com.platojobs.pkotlinapp.domain.reading.ReadingUuids

/**
 * 服务端设备类型目录（`GET /catalog/device-types`）的领域模型。
 *
 * 目录是唯一事实来源：类型清单、GATT 服务、指标、限制与兼容性全部来自接口，
 * 本地只保留最近一次成功响应的缓存，不再维护第二份写死清单。
 */
data class CatalogService(
    val uuid: String,
    val name: String,
)

/**
 * 目录声明的一个可解析指标。
 *
 * [supportedByApp] 由本机解析器能力决定（[ReadingUuids.supported]），
 * 目录声明了但本机没有实现的特征必须如实标注「当前版本不支持」。
 */
data class CatalogMetric(
    val key: String,
    val unit: String,
    val characteristicUuid: String,
) {
    val supportedByApp: Boolean
        get() = ReadingUuids.normalize(characteristicUuid) in ReadingUuids.supported
}

data class DeviceTypeCatalogItem(
    val type: String,
    val displayName: String,
    val description: String,
    val services: List<CatalogService>,
    val metrics: List<CatalogMetric>,
    val limitations: List<String>,
    val compatibility: Compatibility?,
    val updatedAt: String,
) {
    val supportedMetrics: List<CatalogMetric>
        get() = metrics.filter { it.supportedByApp }

    val unsupportedMetrics: List<CatalogMetric>
        get() = metrics.filterNot { it.supportedByApp }
}

/**
 * 目录快照。
 *
 * [fetchedAt] 非空表示本地确有服务端响应缓存；[generatedAt] 是服务端目录的变更时间，
 * 断网时用它标注「目录更新于」，而不是把缓存假装成刚拉取的数据。
 */
data class DeviceTypeCatalog(
    val items: List<DeviceTypeCatalogItem> = emptyList(),
    val version: Long? = null,
    val generatedAt: String? = null,
    val fetchedAt: Long? = null,
    val lastUsedAt: Long? = null,
) {
    val hasCache: Boolean get() = fetchedAt != null

    companion object {
        val EMPTY = DeviceTypeCatalog()
    }
}

/** 目录请求失败的可读原因；由界面层挑选对应中英文文案。 */
enum class CatalogFailureReason {
    NETWORK,
    HTTP,
    EMPTY,
    UNKNOWN,
}
