package com.platojobs.pkotlinapp.data.catalog

import com.platojobs.pkotlinapp.data.local.CatalogDao
import com.platojobs.pkotlinapp.data.local.CatalogItemEntity
import com.platojobs.pkotlinapp.data.local.CatalogSyncEntity
import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.remote.CatalogDeviceTypeDto
import com.platojobs.pkotlinapp.data.remote.CatalogItemPayloadDto
import com.platojobs.pkotlinapp.data.remote.toDomain
import com.platojobs.pkotlinapp.domain.catalog.CatalogFailureReason
import com.platojobs.pkotlinapp.domain.catalog.CatalogMetric
import com.platojobs.pkotlinapp.domain.catalog.CatalogService
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalog
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalogItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 目录刷新结果；失败时界面保留缓存，只提示离线/错误。 */
sealed interface CatalogRefreshResult {
    data class Updated(val version: Long) : CatalogRefreshResult

    /** 304 或本地缓存仍在节流窗口内。 */
    data object NotModified : CatalogRefreshResult

    data class Failed(
        val reason: CatalogFailureReason,
        val httpCode: Int? = null,
        val detail: String? = null,
    ) : CatalogRefreshResult
}

/**
 * 服务端设备类型目录的唯一入口。
 *
 * - 读取永远来自 Room 缓存，首屏不等待网络；
 * - 刷新带 `If-None-Match` / `If-Modified-Since`，304 只刷新使用时间；
 * - 200 时条目与校验器在同一事务内替换；失败绝不清空缓存。
 */
class DeviceCatalogStore(
    private val dao: CatalogDao,
    private val remote: CatalogRemote,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minRefreshIntervalMillis: Long = MIN_REFRESH_INTERVAL_MILLIS,
) {

    private val mutex = Mutex()
    private val payloadAdapter = ApiClient.moshi()
        .adapter(CatalogItemPayloadDto::class.java)

    /** 唯一真值：Room 中的目录快照；无缓存时返回空快照（[DeviceTypeCatalog.hasCache] 为 false）。 */
    fun observe(): Flow<DeviceTypeCatalog> =
        combine(dao.observeItems(), dao.observeSync()) { items, sync ->
            DeviceTypeCatalog(
                items = items.mapNotNull { it.toDomainItem() },
                version = sync?.version,
                generatedAt = sync?.generatedAt,
                fetchedAt = sync?.fetchedAt,
                lastUsedAt = sync?.lastUsedAt,
            )
        }

    suspend fun cachedSnapshot(): DeviceTypeCatalog {
        val sync = dao.sync()
        return DeviceTypeCatalog(
            items = dao.items().mapNotNull { it.toDomainItem() },
            version = sync?.version,
            generatedAt = sync?.generatedAt,
            fetchedAt = sync?.fetchedAt,
            lastUsedAt = sync?.lastUsedAt,
        )
    }

    /**
     * 刷新目录。[force] 用于用户手动重试；默认在节流窗口内直接复用缓存，
     * 避免产品/市场/详情同时打开时重复请求。
     */
    suspend fun refresh(force: Boolean = false): CatalogRefreshResult = mutex.withLock {
        val sync = dao.sync()
        val now = clock()
        if (!force && sync != null && now >= sync.fetchedAt &&
            now - sync.fetchedAt < minRefreshIntervalMillis
        ) {
            return@withLock CatalogRefreshResult.NotModified
        }

        when (val result = remote.fetch(sync?.etag, sync?.lastModified)) {
            CatalogFetchResult.NotModified -> {
                dao.markUsed(clock())
                CatalogRefreshResult.NotModified
            }

            is CatalogFetchResult.Failure -> CatalogRefreshResult.Failed(
                reason = result.reason,
                httpCode = result.httpCode,
                detail = result.detail,
            )

            is CatalogFetchResult.Success -> {
                val catalog = result.catalog
                val items = catalog.items.orEmpty()
                    .mapIndexedNotNull { index, item -> item.toEntity(index) }
                val entities = CatalogSyncEntity(
                    version = catalog.version ?: FALLBACK_VERSION,
                    generatedAt = catalog.generatedAt.orEmpty(),
                    etag = result.etag,
                    lastModified = result.lastModified,
                    fetchedAt = clock(),
                    lastUsedAt = clock(),
                )
                // 允许服务端返回空目录：这是真实响应，不能沿用旧缓存冒充。
                dao.replaceAll(items, entities)
                CatalogRefreshResult.Updated(entities.version)
            }
        }
    }

    private fun CatalogDeviceTypeDto.toEntity(sortOrder: Int): CatalogItemEntity? {
        val key = type?.trim().orEmpty()
        if (key.isEmpty()) return null
        val payload = CatalogItemPayloadDto(
            gattServices = gattServices.orEmpty(),
            metrics = metrics.orEmpty(),
            limitations = limitations.orEmpty(),
            compatibility = compatibility,
        )
        return CatalogItemEntity(
            type = key,
            displayName = displayName?.trim().takeUnless { it.isNullOrEmpty() } ?: key,
            description = description.orEmpty(),
            updatedAt = updatedAt.orEmpty(),
            sortOrder = sortOrder,
            payloadJson = payloadAdapter.toJson(payload),
        )
    }

    private fun CatalogItemEntity.toDomainItem(): DeviceTypeCatalogItem? {
        if (type.isBlank()) return null
        val payload = runCatching { payloadAdapter.fromJson(payloadJson) }
            .getOrNull() ?: CatalogItemPayloadDto()
        return DeviceTypeCatalogItem(
            type = type,
            displayName = displayName.ifBlank { type },
            description = description,
            services = payload.gattServices.orEmpty().mapNotNull { dto ->
                val uuid = dto.uuid?.trim().orEmpty()
                if (uuid.isEmpty()) null else CatalogService(uuid, dto.name.orEmpty())
            },
            metrics = payload.metrics.orEmpty().mapNotNull { dto ->
                val key = dto.key?.trim().orEmpty()
                val characteristic = dto.characteristicUuid?.trim().orEmpty()
                if (key.isEmpty() || characteristic.isEmpty()) {
                    null
                } else {
                    CatalogMetric(key, dto.unit.orEmpty(), characteristic)
                }
            },
            limitations = payload.limitations.orEmpty().filter { it.isNotBlank() },
            compatibility = payload.compatibility?.toDomain(),
            updatedAt = updatedAt,
        )
    }

    companion object {
        /** 首次缓存写入的节流窗口：60 秒内不重复请求。 */
        const val MIN_REFRESH_INTERVAL_MILLIS = 60_000L

        /** 服务端缺失 version 时的兜底值；不用于展示。 */
        private const val FALLBACK_VERSION = 0L
    }
}
