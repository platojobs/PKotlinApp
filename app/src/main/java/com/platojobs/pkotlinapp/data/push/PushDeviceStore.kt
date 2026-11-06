package com.platojobs.pkotlinapp.data.push

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.platojobs.pkotlinapp.domain.push.PushDeviceRecord
import com.platojobs.pkotlinapp.domain.push.PushDeviceStatus

/**
 * 本地推送设备映射：token 唯一，保留远端 deviceId/status/最后错误。
 * token 为空串的行只用于记录「未配置 FCM」，不会被注册到服务端。
 */
@Entity(
    tableName = "push_devices",
    indices = [
        Index(value = ["token"], unique = true),
        Index("status"),
    ],
)
data class PushDeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val token: String,
    val remoteDeviceId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "'pending'") val status: String = "pending",
    val lastError: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "'android'") val platform: String = "android",
    @androidx.room.ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

@Dao
interface PushDeviceDao {
    @Query("SELECT * FROM push_devices WHERE token = :token LIMIT 1")
    suspend fun findByToken(token: String): PushDeviceEntity?

    @Query("SELECT * FROM push_devices WHERE status = 'registered' AND token != ''")
    suspend fun registered(): List<PushDeviceEntity>

    @Query("SELECT * FROM push_devices WHERE status IN ('pending', 'rejected')")
    suspend fun pendingOrRejected(): List<PushDeviceEntity>

    @Query("SELECT * FROM push_devices WHERE status = 'unconfigured' LIMIT 1")
    suspend fun unconfigured(): PushDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: PushDeviceEntity)
}

/** 仓库层依赖的存储抽象，便于 JVM 单测用内存 Fake。 */
interface PushDeviceStore {
    suspend fun findByToken(token: String): PushDeviceRecord?
    suspend fun registered(): List<PushDeviceRecord>
    suspend fun pendingOrRejected(): List<PushDeviceRecord>
    suspend fun unconfigured(): PushDeviceRecord?
    suspend fun save(record: PushDeviceRecord)
}

class RoomPushDeviceStore(private val dao: PushDeviceDao) : PushDeviceStore {
    override suspend fun findByToken(token: String): PushDeviceRecord? =
        dao.findByToken(token)?.toRecord()

    override suspend fun registered(): List<PushDeviceRecord> = dao.registered().map { it.toRecord() }

    override suspend fun pendingOrRejected(): List<PushDeviceRecord> =
        dao.pendingOrRejected().map { it.toRecord() }

    override suspend fun unconfigured(): PushDeviceRecord? = dao.unconfigured()?.toRecord()

    override suspend fun save(record: PushDeviceRecord) {
        // REPLACE 依赖 token 唯一索引；保留首次写入时间。
        dao.save(
            PushDeviceEntity(
                token = record.token,
                remoteDeviceId = record.remoteDeviceId,
                status = record.status.stored,
                lastError = record.lastError,
                retryCount = record.retryCount,
                nextRetryAt = record.nextRetryAt,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
            ),
        )
    }
}

private fun PushDeviceEntity.toRecord() = PushDeviceRecord(
    token = token,
    remoteDeviceId = remoteDeviceId,
    status = PushDeviceStatus.fromStored(status),
    lastError = lastError,
    retryCount = retryCount,
    nextRetryAt = nextRetryAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
