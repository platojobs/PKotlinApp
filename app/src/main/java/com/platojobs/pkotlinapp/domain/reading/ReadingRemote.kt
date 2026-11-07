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

/** 服务端 UTC 对齐的单个时间桶；只包含有数据的桶。 */
data class ReadingBucket(
    val start: Long,
    val end: Long,
    val count: Long,
    val min: Double,
    val max: Double,
    val avg: Double,
    val last: Double,
)

/** 某指标在指定区间内的分桶序列（按 start 升序）。 */
data class ReadingSeries(
    val metric: String,
    val unit: String,
    val bucket: String,
    val from: Long,
    val to: Long,
    val buckets: List<ReadingBucket>,
)

/** 服务端指标清单项：count/lastAt 用于展示「指标名 · N 条」并倒序排列。 */
data class ReadingMetric(
    val metric: String,
    val unit: String,
    val count: Long,
    val firstAt: Long,
    val lastAt: Long,
)

/** 云端最近一条读数；[metric] 为该设备 `lastAt` 最新的指标。 */
data class CloudReading(
    val metric: String,
    val value: Double,
    val unit: String,
    val recordedAt: Long,
)

/**
 * 云端最近读数四态：UI 必须区分「正在取」「有值」「确实没有」「取不到」，
 * 失败不得退化成离线或「暂无读数」。
 */
sealed interface CloudReadingState {
    data object Loading : CloudReadingState

    data class Available(val reading: CloudReading) : CloudReadingState

    /** 云端确认该设备没有任何指标（200 + 空清单）。 */
    data object Empty : CloudReadingState

    /** 网络/鉴权/服务端错误；界面显示「云端读数暂不可用」。 */
    data object Failed : CloudReadingState
}

/**
 * 按平台 + externalId 取云端最近读数：
 * 先 `/readings/metrics` 找 `lastAt` 最新的指标，再取该指标最近一条。
 */
interface LatestReadingRemote {
    suspend fun latest(platform: String, externalId: String): CloudReading?
}

/** 读数远端接口：推送、倒序查询、24h 汇总、长周期分桶与指标清单。 */
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

    /** 长周期分桶；服务端只返回有数据的桶，空桶需要客户端按时间补齐（断线不连线）。 */
    suspend fun series(
        deviceId: String,
        metric: String,
        from: Long,
        to: Long,
        bucket: String,
    ): ReadingSeries

    /** 指标清单，服务端按 lastAt 倒序返回。 */
    suspend fun metrics(deviceId: String): List<ReadingMetric>
}
