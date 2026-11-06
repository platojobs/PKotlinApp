package com.platojobs.pkotlinapp.data.catalog

import com.platojobs.pkotlinapp.data.local.CatalogDao
import com.platojobs.pkotlinapp.data.local.CatalogItemEntity
import com.platojobs.pkotlinapp.data.local.CatalogSyncEntity
import com.platojobs.pkotlinapp.data.remote.CatalogDeviceTypeDto
import com.platojobs.pkotlinapp.data.remote.CatalogMetricDto
import com.platojobs.pkotlinapp.data.remote.DeviceTypeCatalogDto
import com.platojobs.pkotlinapp.data.remote.GattServiceDto
import com.platojobs.pkotlinapp.domain.catalog.CatalogFailureReason
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录缓存与条件请求：200 原子替换、304 复用并刷新使用时间、失败绝不清空缓存。
 */
class DeviceCatalogStoreTest {

    private var now = 1_000_000L

    @Test
    fun `200 stores items with etag and parses declared metrics`() = runBlocking {
        val dao = FakeCatalogDao()
        val remote = FakeRemote(success(catalogJsonItems()))
        val store = store(dao, remote)

        val result = store.refresh(force = true)

        assertTrue(result is CatalogRefreshResult.Updated)
        val snapshot = dao.sync()!!
        assertEquals(12L, snapshot.version)
        assertEquals("\"catalog-v12\"", snapshot.etag)
        assertEquals("Wed, 01 Oct 2026 08:00:00 GMT", snapshot.lastModified)
        assertEquals(now, snapshot.fetchedAt)
        assertEquals(now, snapshot.lastUsedAt)
        // 首次请求没有缓存，不能凭空带上校验器。
        assertNull(remote.lastEtag)
        assertNull(remote.lastLastModified)

        val cached = store.cachedSnapshot()
        assertTrue(cached.hasCache)
        assertEquals(2, cached.items.size)
        val environment = cached.items.first { it.type == "environmental_sensor" }
        assertEquals("环境传感器", environment.displayName)
        assertEquals(
            listOf("temperature_celsius", "relative_humidity"),
            environment.metrics.map { it.key },
        )
        // 温度特征本机实现、湿度没有实现：不能假装都能读。
        assertTrue(environment.metrics.first { it.key == "temperature_celsius" }.supportedByApp)
        assertFalse(environment.metrics.first { it.key == "relative_humidity" }.supportedByApp)
        assertEquals(listOf("relative_humidity"), environment.unsupportedMetrics.map { it.key })
        assertEquals(listOf("需保持干燥"), environment.limitations)
    }

    @Test
    fun `304 reuses cache and only refreshes last used time`() = runBlocking {
        val dao = seededDao()
        val remote = FakeRemote(CatalogFetchResult.NotModified)
        val store = store(dao, remote)

        val result = store.refresh(force = true)

        assertEquals(CatalogRefreshResult.NotModified, result)
        assertEquals("\"catalog-v11\"", remote.lastEtag)
        assertEquals("Wed, 01 Oct 2026 07:00:00 GMT", remote.lastLastModified)
        val snapshot = dao.sync()!!
        // 条目与校验器不变，只更新使用时间。
        assertEquals("\"catalog-v11\"", snapshot.etag)
        assertEquals(11L, snapshot.version)
        assertEquals(100L, snapshot.fetchedAt)
        assertEquals(now, snapshot.lastUsedAt)
        assertEquals(1, dao.items().size)
    }

    @Test
    fun `failure with cache keeps cached items and reports readable reason`() = runBlocking {
        val dao = seededDao()
        val remote = FakeRemote(
            CatalogFetchResult.Failure(
                reason = CatalogFailureReason.NETWORK,
                detail = "unreachable",
            ),
        )
        val store = store(dao, remote)

        val result = store.refresh(force = true)

        assertTrue(result is CatalogRefreshResult.Failed)
        assertEquals(CatalogFailureReason.NETWORK, (result as CatalogRefreshResult.Failed).reason)
        val cached = store.cachedSnapshot()
        assertTrue(cached.hasCache)
        assertEquals(1, cached.items.size)
        assertEquals(100L, dao.sync()!!.fetchedAt)
    }

    @Test
    fun `failure without cache never fabricates a catalog`() = runBlocking {
        val dao = FakeCatalogDao()
        val remote = FakeRemote(
            CatalogFetchResult.Failure(reason = CatalogFailureReason.HTTP, httpCode = 503),
        )
        val store = store(dao, remote)

        val result = store.refresh(force = true)

        assertTrue(result is CatalogRefreshResult.Failed)
        val cached = store.cachedSnapshot()
        assertFalse(cached.hasCache)
        assertTrue(cached.items.isEmpty())
        assertNull(dao.sync())
    }

    @Test
    fun `refresh inside throttle window skips the network`() = runBlocking {
        val dao = seededDao(fetchedAt = now - 10_000)
        val remote = FakeRemote(CatalogFetchResult.NotModified)
        val store = store(dao, remote)

        val result = store.refresh(force = false)

        assertEquals(CatalogRefreshResult.NotModified, result)
        assertEquals(0, remote.calls)
        assertEquals(1, dao.items().size)
    }

    @Test
    fun `force refresh bypasses the throttle window`() = runBlocking {
        val dao = seededDao(fetchedAt = now - 10_000)
        val remote = FakeRemote(CatalogFetchResult.NotModified)
        val store = store(dao, remote)

        val result = store.refresh(force = true)

        assertEquals(CatalogRefreshResult.NotModified, result)
        assertEquals(1, remote.calls)
    }

    @Test
    fun `empty 200 replaces old cache instead of pretending it is unchanged`() = runBlocking {
        val dao = seededDao()
        val remote = FakeRemote(
            CatalogFetchResult.Success(
                catalog = DeviceTypeCatalogDto(version = 13, generatedAt = "2026-10-02T00:00:00Z"),
                etag = "\"catalog-v13\"",
                lastModified = null,
            ),
        )
        val store = store(dao, remote)

        val result = store.refresh(force = true)

        assertTrue(result is CatalogRefreshResult.Updated)
        val cached = store.cachedSnapshot()
        assertTrue(cached.items.isEmpty())
        assertEquals(13L, cached.version)
        assertEquals("\"catalog-v13\"", dao.sync()!!.etag)
    }

    private fun store(dao: CatalogDao, remote: CatalogRemote) = DeviceCatalogStore(
        dao = dao,
        remote = remote,
        clock = { now },
    )

    private fun success(items: List<CatalogDeviceTypeDto>) = CatalogFetchResult.Success(
        catalog = DeviceTypeCatalogDto(
            version = 12,
            generatedAt = "2026-10-01T08:00:00Z",
            items = items,
        ),
        etag = "\"catalog-v12\"",
        lastModified = "Wed, 01 Oct 2026 08:00:00 GMT",
    )

    private fun catalogJsonItems(): List<CatalogDeviceTypeDto> = listOf(
        CatalogDeviceTypeDto(
            type = "environmental_sensor",
            displayName = "环境传感器",
            description = "温湿度采集",
            gattServices = listOf(
                GattServiceDto(uuid = "0000181a-0000-1000-8000-00805f9b34fb", name = "Environmental"),
            ),
            metrics = listOf(
                CatalogMetricDto(
                    key = "temperature_celsius",
                    unit = "°C",
                    characteristicUuid = "00002a6e-0000-1000-8000-00805f9b34fb",
                ),
                CatalogMetricDto(
                    key = "relative_humidity",
                    unit = "%",
                    characteristicUuid = "00002a6f-0000-1000-8000-00805f9b34fb",
                ),
            ),
            limitations = listOf("需保持干燥"),
            updatedAt = "2026-09-30T00:00:00Z",
        ),
        CatalogDeviceTypeDto(
            type = "heart_rate_monitor",
            displayName = "心率手环",
            description = "",
            gattServices = emptyList(),
            metrics = listOf(
                CatalogMetricDto(
                    key = "heart_rate",
                    unit = "bpm",
                    characteristicUuid = "00002a37-0000-1000-8000-00805f9b34fb",
                ),
            ),
            updatedAt = "2026-09-30T00:00:00Z",
        ),
    )

    private fun seededDao(fetchedAt: Long = 100L): FakeCatalogDao =
        FakeCatalogDao(
            items = listOf(
                CatalogItemEntity(
                    type = "nordic_uart",
                    displayName = "NUS 中继",
                    description = "文本透传",
                    updatedAt = "2026-09-01T00:00:00Z",
                    sortOrder = 0,
                    payloadJson = "{}",
                ),
            ),
            sync = CatalogSyncEntity(
                version = 11,
                generatedAt = "2026-09-01T00:00:00Z",
                etag = "\"catalog-v11\"",
                lastModified = "Wed, 01 Oct 2026 07:00:00 GMT",
                fetchedAt = fetchedAt,
                lastUsedAt = fetchedAt,
            ),
        )

    private class FakeRemote(var result: CatalogFetchResult) : CatalogRemote {
        var calls = 0
        var lastEtag: String? = null
        var lastLastModified: String? = null

        override suspend fun fetch(etag: String?, lastModified: String?): CatalogFetchResult {
            calls += 1
            lastEtag = etag
            lastLastModified = lastModified
            return result
        }
    }

    private class FakeCatalogDao(
        items: List<CatalogItemEntity> = emptyList(),
        sync: CatalogSyncEntity? = null,
    ) : CatalogDao {
        private val itemsFlow = MutableStateFlow(items)
        private val syncFlow = MutableStateFlow(sync)

        override fun observeItems(): Flow<List<CatalogItemEntity>> = itemsFlow
        override fun observeSync(): Flow<CatalogSyncEntity?> = syncFlow
        override suspend fun items(): List<CatalogItemEntity> = itemsFlow.value
        override suspend fun sync(): CatalogSyncEntity? = syncFlow.value

        override suspend fun upsertItems(items: List<CatalogItemEntity>) {
            itemsFlow.value = items
        }

        override suspend fun upsertSync(sync: CatalogSyncEntity) {
            syncFlow.value = sync
        }

        override suspend fun deleteItems() {
            itemsFlow.value = emptyList()
        }

        override suspend fun touchLastUsed(timestamp: Long) {
            syncFlow.value = syncFlow.value?.copy(lastUsedAt = timestamp)
        }
    }
}
