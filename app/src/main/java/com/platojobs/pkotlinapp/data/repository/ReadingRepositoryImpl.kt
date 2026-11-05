package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.ReadingDao
import com.platojobs.pkotlinapp.data.local.ReadingEntity
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingMetric
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.reading.ReadingSample
import com.platojobs.pkotlinapp.domain.reading.ReadingSummary
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.max
import kotlin.math.min

class ReadingRepositoryImpl(
    private val dao: ReadingDao,
    private val now: () -> Long = System::currentTimeMillis,
) : ReadingRepository {

    override suspend fun record(deviceId: String, sample: ReadingSample): Boolean {
        // 解析器已过滤非法值；这里再用有限值兜底，避免任何脏数据落库。
        if (!sample.value.isFinite()) return false
        val entity = ReadingEntity(
            id = Reading.newId(),
            deviceId = deviceId,
            metric = ReadingMetrics.normalize(sample.metric),
            value = sample.value,
            unit = sample.unit,
            recordedAt = sample.recordedAt,
            syncState = Reading.SYNC_STATE_PENDING,
            rawSnapshot = sample.rawSnapshot,
        )
        return runCatching { dao.insert(entity) >= 0L }.getOrDefault(false)
    }

    override fun observeMetrics(deviceId: String): Flow<List<String>> =
        dao.observeMetrics(deviceId)

    override fun observeRecent(
        deviceId: String,
        metric: String,
        limit: Int,
    ): Flow<List<Reading>> = dao.observeRecent(deviceId, metric, limit)
        .map { rows -> rows.map(ReadingEntity::toDomain) }

    override suspend fun getRecent(deviceId: String, metric: String, limit: Int): List<Reading> =
        dao.getRecent(deviceId, metric, limit).map(ReadingEntity::toDomain)

    override suspend fun localMetrics(deviceId: String): List<ReadingMetric> =
        dao.metricAggregates(deviceId).map { row ->
            ReadingMetric(
                metric = row.metric,
                unit = row.unit.ifBlank { ReadingMetrics.unit(row.metric) },
                count = row.count,
                firstAt = row.firstAt,
                lastAt = row.lastAt,
            )
        }

    override suspend fun readingsSince(
        deviceId: String,
        metric: String,
        from: Long,
    ): List<Reading> = dao.getSince(deviceId, metric, from).map(ReadingEntity::toDomain)

    override suspend fun readingsBetween(
        deviceId: String,
        metric: String,
        from: Long,
        to: Long,
    ): List<Reading> = dao.getBetween(deviceId, metric, from, to).map(ReadingEntity::toDomain)

    override suspend fun summary(deviceId: String, metric: String, hours: Int): ReadingSummary {
        val end = now()
        val start = end - hours.coerceAtLeast(1) * 60L * 60L * 1000L
        val rows = dao.getSince(deviceId, metric, start)
        if (rows.isEmpty()) {
            return ReadingSummary(
                metric = metric,
                unit = ReadingMetrics.unit(metric),
                count = 0,
                windowStart = start,
                windowEnd = end,
            )
        }
        return summarizeValues(
            metric = metric,
            unit = rows.last().unit.ifBlank { ReadingMetrics.unit(metric) },
            values = rows.map { it.value },
            windowStart = start,
            windowEnd = end,
        )
    }

    override suspend fun cleanup() {
        dao.deleteOlderThan(now() - Reading.LOCAL_RETENTION_MILLIS)
    }
}

private fun ReadingEntity.toDomain() = Reading(
    id = id,
    deviceId = deviceId,
    metric = metric,
    value = value,
    unit = unit,
    recordedAt = recordedAt,
    syncState = syncState,
    rawSnapshot = rawSnapshot,
)

/** 供纯逻辑测试使用的统计函数（无 Android 依赖）。 */
internal fun summarizeValues(
    metric: String,
    unit: String,
    values: List<Double>,
    windowStart: Long,
    windowEnd: Long,
): ReadingSummary {
    if (values.isEmpty()) {
        return ReadingSummary(metric, unit, 0, windowStart = windowStart, windowEnd = windowEnd)
    }
    var lo = values.first()
    var hi = values.first()
    var sum = 0.0
    values.forEach {
        lo = min(lo, it)
        hi = max(hi, it)
        sum += it
    }
    return ReadingSummary(
        metric = metric,
        unit = unit,
        count = values.size,
        min = lo,
        max = hi,
        avg = sum / values.size,
        last = values.last(),
        windowStart = windowStart,
        windowEnd = windowEnd,
    )
}
