package com.platojobs.pkotlinapp.domain.reading

/** 服务端查询到的单条读数（用于分页/对账，UI 默认展示本地数据）。 */
data class RemoteReading(
    val id: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val recordedAt: Long,
)

data class ReadingPage(
    val items: List<RemoteReading>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class ReadingPushAck(
    val applied: Int,
    val skipped: Int,
)

/** 读数远端接口：推送、倒序查询与 24h 汇总。 */
interface ReadingRemote {
    suspend fun pushReadings(readings: List<Reading>, idempotencyKey: String): ReadingPushAck

    suspend fun queryReadings(
        deviceId: String,
        metric: String?,
        from: Long?,
        to: Long?,
        limit: Int,
        cursor: String?,
    ): ReadingPage

    /** 远端不可用时由调用方回退本地汇总。 */
    suspend fun remoteSummary(deviceId: String, metric: String, hours: Int): ReadingSummary
}
