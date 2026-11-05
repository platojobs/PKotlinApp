package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.local.ReadingDao
import com.platojobs.pkotlinapp.data.local.ReadingEntity
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingRemote
import com.platojobs.pkotlinapp.domain.repository.ReadingRepository
import java.security.MessageDigest

/**
 * 读数批量同步：每批 ≤200，按 id 增量；失败时保留 pending，重试复用同一幂等键。
 */
class ReadingSyncer(
    private val dao: ReadingDao,
    private val remote: ReadingRemote,
    private val repository: ReadingRepository,
    private val batchSize: Int = 200,
) {

    /** 同步所有 pending 读数并顺带执行 7 天清理，返回本次推送条数。 */
    suspend fun syncPending(): Int {
        var pushed = 0
        while (true) {
            val rows = dao.pending(batchSize)
            if (rows.isEmpty()) break
            val readings = rows.map(ReadingEntity::toDomain)
            // 同一批内容（含 id）对应同一个 key，重试不会生成新请求。
            val key = ReadingIdempotencyKeys.forBatch(readings)
            remote.pushReadings(readings, key)
            dao.markSynced(rows.map { it.id })
            pushed += rows.size
            if (rows.size < batchSize) break
        }
        repository.cleanup()
        return pushed
    }
}

/**
 * 幂等键必须只由「本批实际内容」决定：按 id 排序后哈希。
 * 重复调用、pending 顺序变化都不会产生新 key；服务端按 id 幂等，可安全重试。
 */
object ReadingIdempotencyKeys {
    fun forBatch(readings: List<Reading>): String {
        val canonical = readings
            .sortedBy { it.id }
            .joinToString("\u001F") { reading ->
                listOf(
                    reading.id,
                    reading.deviceId,
                    reading.metric,
                    reading.value.toString(),
                    reading.unit,
                    reading.recordedAt.toString(),
                ).joinToString("\u001E")
            }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return "readings-" + digest.joinToString("") { byte ->
            byte.toInt().toUInt().toString(16).padStart(2, '0')
        }.take(40)
    }
}

internal fun ReadingEntity.toDomain() = Reading(
    id = id,
    deviceId = deviceId,
    metric = metric,
    value = value,
    unit = unit,
    recordedAt = recordedAt,
    syncState = syncState,
    rawSnapshot = rawSnapshot,
)
