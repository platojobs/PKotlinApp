package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.ReadingDao
import com.platojobs.pkotlinapp.data.local.ReadingEntity
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用内存版 DAO 验证落库过滤、7 天清理与本地汇总，不依赖 Android 运行时。 */
class ReadingRepositoryTest {

    private val now = 1_760_000_000_000L

    @Test
    fun `record rejects non finite values and normalizes unknown metrics`() = runBlocking {
        val dao = FakeReadingDao()
        val repository = ReadingRepositoryImpl(dao, now = { now })

        assertFalse(
            repository.record(
                "AA:BB:CC:00:00:01",
                ReadingSample("temperature", Double.NaN, "°C", now),
            ),
        )
        assertEquals(0, dao.stored.size)

        assertTrue(
            repository.record(
                "AA:BB:CC:00:00:01",
                ReadingSample("foo", 12.0, "", now),
            ),
        )
        assertEquals(1, dao.stored.size)
        // 未知指标统一落 other，并保留原始快照。
        assertEquals("other", dao.stored.single().metric)
        assertEquals("pending", dao.stored.single().syncState)
    }

    @Test
    fun `cleanup keeps pending rows no matter how old`() = runBlocking {
        val dao = FakeReadingDao()
        val repository = ReadingRepositoryImpl(dao, now = { now })
        val day = 24L * 60L * 60L * 1000L

        repository.record("AA:BB:CC:00:00:01", ReadingSample("temperature", 20.0, "°C", now - 30 * day))
        repository.record("AA:BB:CC:00:00:01", ReadingSample("temperature", 21.0, "°C", now - day))
        assertEquals(2, dao.stored.size)

        repository.cleanup()

        assertEquals(now - Reading.LOCAL_RETENTION_MILLIS, dao.lastCutoff)
        // 两条都尚未同步：即使 30 天前也不能删，离线数据优先保留。
        assertEquals(2, dao.stored.size)
    }

    @Test
    fun `cleanup deletes only synced rows older than seven days`() = runBlocking {
        val dao = FakeReadingDao()
        val repository = ReadingRepositoryImpl(dao, now = { now })
        val day = 24L * 60L * 60L * 1000L

        repository.record("AA:BB:CC:00:00:01", ReadingSample("temperature", 20.0, "°C", now - 8 * day))
        repository.record("AA:BB:CC:00:00:01", ReadingSample("temperature", 21.0, "°C", now - day))
        val oldId = dao.stored.first { it.recordedAt == now - 8 * day }.id
        dao.markSynced(listOf(oldId))

        repository.cleanup()

        assertEquals(now - Reading.LOCAL_RETENTION_MILLIS, dao.lastCutoff)
        assertEquals(1, dao.stored.size)
        assertEquals(now - day, dao.stored.single().recordedAt)
        assertEquals(Reading.SYNC_STATE_PENDING, dao.stored.single().syncState)
    }

    @Test
    fun `summary only counts the requested window`() = runBlocking {
        val dao = FakeReadingDao()
        val repository = ReadingRepositoryImpl(dao, now = { now })
        val day = 24L * 60L * 60L * 1000L

        listOf(18.0, 22.0, 25.0).forEachIndexed { index, value ->
            repository.record(
                "AA:BB:CC:00:00:01",
                ReadingSample("temperature", value, "°C", now - index * 60_000L),
            )
        }
        // 窗口外的旧值不参与统计。
        repository.record("AA:BB:CC:00:00:01", ReadingSample("temperature", 99.0, "°C", now - 2 * day))

        val summary = repository.summary("AA:BB:CC:00:00:01", "temperature", 24)
        assertEquals(3, summary.count)
        assertEquals(18.0, summary.min!!, 0.0)
        assertEquals(25.0, summary.max!!, 0.0)
        assertEquals(65.0 / 3.0, summary.avg!!, 1e-9)
        assertEquals(18.0, summary.last!!, 0.0)
    }

    /** 只实现仓储真正调用的方法，其余保持最小可用。 */
    private class FakeReadingDao : ReadingDao {
        val stored = mutableListOf<ReadingEntity>()
        var lastCutoff: Long? = null
        private val metrics = MutableStateFlow<List<String>>(emptyList())

        override suspend fun insert(reading: ReadingEntity): Long {
            if (stored.any { it.id == reading.id }) return -1
            stored += reading
            metrics.value = stored.map { it.metric }.distinct().sorted()
            return stored.size.toLong()
        }

        override fun observeMetrics(deviceId: String): Flow<List<String>> = metrics

        override fun observeRecent(
            deviceId: String,
            metric: String,
            limit: Int,
        ): Flow<List<ReadingEntity>> = MutableStateFlow(
            stored.filter { it.deviceId == deviceId && it.metric == metric }
                .sortedByDescending { it.recordedAt }
                .take(limit),
        )

        override suspend fun getRecent(
            deviceId: String,
            metric: String,
            limit: Int,
        ): List<ReadingEntity> = stored
            .filter { it.deviceId == deviceId && it.metric == metric }
            .sortedByDescending { it.recordedAt }
            .take(limit)

        override suspend fun getSince(
            deviceId: String,
            metric: String,
            from: Long,
        ): List<ReadingEntity> = stored
            .filter { it.deviceId == deviceId && it.metric == metric && it.recordedAt >= from }
            .sortedBy { it.recordedAt }

        override suspend fun pending(limit: Int): List<ReadingEntity> = stored
            .filter { it.syncState == Reading.SYNC_STATE_PENDING }
            .sortedWith(compareBy({ it.recordedAt }, { it.id }))
            .take(limit)

        override suspend fun pendingCount(): Int =
            stored.count { it.syncState == Reading.SYNC_STATE_PENDING }

        override suspend fun markSynced(ids: List<String>) {
            stored.replaceAll {
                if (it.id in ids) it.copy(syncState = Reading.SYNC_STATE_SYNCED) else it
            }
        }

        override suspend fun deleteOlderThan(cutoff: Long) {
            lastCutoff = cutoff
            // 与 Room 查询语义保持一致：仅删除已同步且超过保留期的记录。
            stored.removeAll {
                it.recordedAt < cutoff && it.syncState == Reading.SYNC_STATE_SYNCED
            }
        }
    }
}
