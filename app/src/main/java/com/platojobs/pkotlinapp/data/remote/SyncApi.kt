package com.platojobs.pkotlinapp.data.remote

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.lang.reflect.Type
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 可缺省字段的「已上报」载体（openapi 0.3.3 三态语义）：
 * - 属性为 null：JSON 中字段缺失（absent），服务端保持原值；
 * - `Reported("")`：显式上报空串（例如清除分组）；
 * - `Reported("值")`：显式覆盖。
 *
 * Moshi 默认把 null 属性省略，因此用它替代 `String?` 才能区分「未上报」与「上报了空串」。
 */
data class Reported<out T>(val value: T)

internal val ReportedStringType: Type = com.squareup.moshi.Types.newParameterizedType(
    Reported::class.java,
    String::class.java,
)

/** 自定义 adapter：确保 `Reported<String>` 按原始 JSON 字符串读写。 */
internal object ReportedStringAdapter : JsonAdapter<Reported<String>>() {
    override fun fromJson(reader: JsonReader): Reported<String>? {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<Any>()
            return null
        }
        return Reported(reader.nextString())
    }

    override fun toJson(writer: JsonWriter, value: Reported<String>?) {
        if (value == null) writer.nullValue() else writer.value(value.value)
    }
}

/**
 * 同步 DTO，字段与 `PJGoIoT/api/openapi.yaml` 对齐：
 * - 时间统一为 RFC 3339 字符串；
 * - 设备唯一键为 `(platform, externalId)`；
 * - push 请求体为 `{clientId, devices}`，响应为 `{applied, conflicts, nextCursor}`；
 * - changes 返回 `{changes: [{seq, entity, entityId, op, payload, serverTs}], nextCursor, hasMore}`。
 */
data class AnonymousLoginResponse(
    val token: String? = null,
    val userId: String? = null,
    /** RFC3339；服务端 token TTL 180 天，「我的」展示到期日（只读）。 */
    val expiresAt: String? = null,
)

/** GET /sync/changes 中 `payload` 对应的用户设备。 */
data class SyncDeviceDto(
    val id: String? = null,
    val clientId: String? = null,
    val platform: String? = null,
    val externalId: String? = null,
    val mac: String? = null,
    val alias: String? = null,
    val advertisedName: String? = null,
    // absent(null) / 空串 / 值三态；冲突响应中缺省字段按 absent 处理。
    val group: Reported<String>? = null,
    val rules: Map<String, Any?>? = null,
    val scanRecords: List<SyncScanRecordDto>? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val serverSeq: Long? = null,
    // openapi 0.3.2 enrich 字段；未注册型号时为 null。
    val deviceType: String? = null,
    val vendor: String? = null,
    val firmwareVersion: String? = null,
    val compatibility: CompatibilityDto? = null,
)

data class SyncScanRecordDto(
    val rssi: Int? = null,
    val at: String? = null,
)

/** 单条增量变更。 */
data class SyncChangeDto(
    val seq: Long? = null,
    val entity: String? = null,
    val entityId: String? = null,
    val op: String? = null,
    val payload: SyncDeviceDto? = null,
    val serverTs: String? = null,
)

/** POST /sync/push 中的单台设备。 */
data class PushDeviceDto(
    val id: String,
    val platform: String,
    val externalId: String,
    val mac: String? = null,
    /**
     * 可选设备型号：只有本地缓存或 catalog 能匹配时才填写。
     * 为 null 时 Moshi 不序列化该字段，服务端沿用旧值而不是覆盖为空。
     */
    val deviceType: String? = null,
    /**
     * 用户别名。null = 未上报（扫描不会上报别名），服务端保留原值。
     */
    val alias: Reported<String>? = null,
    /**
     * 设备广播名。null = 未上报（用户编辑别名时不上报广播名）。
     */
    val advertisedName: Reported<String>? = null,
    /**
     * 分组三态：null = 未上报；Reported("") = 显式清除；Reported("值") = 覆盖。
     */
    val group: Reported<String>? = null,
    val rules: Map<String, Any?> = emptyMap(),
    val scanRecords: List<SyncScanRecordDto> = emptyList(),
    val updatedAt: String,
    val deleted: Boolean = false,
)

data class SyncPushRequest(
    val clientId: String,
    val devices: List<PushDeviceDto>,
    /** 可选读数批次；为空时 Moshi 省略该字段（兼容旧服务端）。 */
    val readings: List<PushReadingDto>? = null,
)

/** POST /sync/push 中的单条读数。`id` 为客户端 UUID，服务端按 id 幂等。 */
data class PushReadingDto(
    val id: String,
    val platform: String,
    val externalId: String,
    val metric: String,
    val value: Double,
    val unit: String,
    /** RFC3339。 */
    val recordedAt: String,
)

/** GET /my/devices/{platform}/{externalId}/readings 的单条返回。 */
data class ReadingDto(
    val id: String? = null,
    val metric: String? = null,
    val value: Double? = null,
    val unit: String? = null,
    val recordedAt: String? = null,
)

data class ReadingPageResponse(
    val items: List<ReadingDto>? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean? = null,
)

/** GET .../readings/summary 返回；无数据时 min/max/avg/last 可能为 null。 */
data class ReadingSummaryResponse(
    val metric: String? = null,
    val unit: String? = null,
    val count: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val avg: Double? = null,
    val last: Double? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
)

/** GET .../readings/series 的单个桶；服务端只返回 count > 0 的桶。 */
data class ReadingBucketDto(
    val start: String? = null,
    val end: String? = null,
    val count: Long? = null,
    val min: Double? = null,
    val max: Double? = null,
    val avg: Double? = null,
    val last: Double? = null,
)

data class ReadingSeriesResponse(
    val metric: String? = null,
    val unit: String? = null,
    val bucket: String? = null,
    val from: String? = null,
    val to: String? = null,
    val buckets: List<ReadingBucketDto>? = null,
)

/** GET .../readings/metrics 的单项；unit 为该指标最新一条读数的单位。 */
data class ReadingMetricDto(
    val metric: String? = null,
    val unit: String? = null,
    val count: Long? = null,
    val firstAt: String? = null,
    val lastAt: String? = null,
)

data class ReadingMetricsResponse(
    val items: List<ReadingMetricDto>? = null,
)

data class AppliedChangeDto(
    val id: String? = null,
    val platform: String? = null,
    val externalId: String? = null,
    val mac: String? = null,
    val status: String? = null,
    val serverSeq: Long? = null,
)

data class ConflictDto(
    val id: String? = null,
    val platform: String? = null,
    val externalId: String? = null,
    val mac: String? = null,
    val reason: String? = null,
    val server: SyncDeviceDto? = null,
    val client: PushDeviceDto? = null,
)

data class SyncPushResponse(
    val applied: List<AppliedChangeDto>? = null,
    val conflicts: List<ConflictDto>? = null,
    val nextCursor: Long? = null,
    // 读数按 id 幂等：重复 id 计入 skipped，不报错。
    val appliedReadings: Int = 0,
    val skippedReadings: Int = 0,
)

data class SyncChangesResponse(
    val changes: List<SyncChangeDto>? = null,
    val nextCursor: Long? = null,
    val hasMore: Boolean? = null,
)

// ---- openapi 0.6.0：配对码 / 账号导出 / 注销 ----

/** POST /auth/pairing-code 的返回；code 仅在本响应出现一次。 */
data class PairingCodeResponse(
    val code: String? = null,
    /** RFC3339，签发后 10 分钟（服务端真值，客户端不自行推算）。 */
    val expiresAt: String? = null,
)

data class PairingClaimRequest(val code: String)

/** POST /auth/pairing-code/claim 的返回：同一 userId 的新 token。 */
data class PairingClaimResponse(
    val userId: String? = null,
    val token: String? = null,
    /** 与 token 一起签发，RFC3339。 */
    val expiresAt: String? = null,
)

/**
 * GET /me/export 的一页。
 * user/devices/readings 保留服务端原始结构（Map），导出时原样合并写盘，
 * cursor 是不透明串，客户端只透传、不解析。
 */
data class ExportPageResponse(
    val user: Map<String, Any?>? = null,
    val devices: List<Map<String, Any?>>? = null,
    val readings: List<Map<String, Any?>>? = null,
    val exportedAt: String? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean? = null,
)

interface SyncApi {

    /** 匿名登录请求体按契约可为空；installId 通过 clientId 放入 push 请求。 */
    @POST("api/v1/auth/anonymous")
    suspend fun anonymousLogin(): AnonymousLoginResponse

    @GET("api/v1/sync/changes")
    suspend fun changes(
        @Header("Authorization") authorization: String,
        @Query("cursor") cursor: String?,
        /** 契约上限 500；显式传值，避免依赖服务端默认值。 */
        @Query("limit") limit: Int = ApiLimits.CHANGES_MAX,
    ): SyncChangesResponse

    @POST("api/v1/sync/push")
    suspend fun push(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: SyncPushRequest,
    ): SyncPushResponse

    @GET("api/v1/my/devices/{platform}/{externalId}/readings")
    suspend fun readings(
        @Header("Authorization") authorization: String,
        @Path("platform") platform: String,
        @Path("externalId") externalId: String,
        @Query("metric") metric: String?,
        @Query("from") from: String?,
        @Query("to") to: String?,
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String?,
    ): ReadingPageResponse

    @GET("api/v1/my/devices/{platform}/{externalId}/readings/summary")
    suspend fun readingsSummary(
        @Header("Authorization") authorization: String,
        @Path("platform") platform: String,
        @Path("externalId") externalId: String,
        @Query("metric") metric: String,
        @Query("hours") hours: Int,
    ): ReadingSummaryResponse

    /** 长周期分桶：from/to 为 UTC RFC3339，bucket 只能是 1m/1h/1d。 */
    @GET("api/v1/my/devices/{platform}/{externalId}/readings/series")
    suspend fun readingsSeries(
        @Header("Authorization") authorization: String,
        @Path("platform") platform: String,
        @Path("externalId") externalId: String,
        @Query("metric") metric: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("bucket") bucket: String,
    ): ReadingSeriesResponse

    /** 指标清单，服务端按 lastAt 倒序返回。 */
    @GET("api/v1/my/devices/{platform}/{externalId}/readings/metrics")
    suspend fun readingsMetrics(
        @Header("Authorization") authorization: String,
        @Path("platform") platform: String,
        @Path("externalId") externalId: String,
    ): ReadingMetricsResponse

    /** 生成一次性配对码；需 Bearer。旧码会被新码立即作废。 */
    @POST("api/v1/auth/pairing-code")
    suspend fun createPairingCode(
        @Header("Authorization") authorization: String,
    ): PairingCodeResponse

    /** 用配对码换取同一账号的新 token；无需鉴权。 */
    @POST("api/v1/auth/pairing-code/claim")
    suspend fun claimPairingCode(@Body body: PairingClaimRequest): PairingClaimResponse

    /** 账号全量导出；cursor 为不透明串，仅透传。 */
    @GET("api/v1/me/export")
    suspend fun exportMyData(
        @Header("Authorization") authorization: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): ExportPageResponse

    /** 硬删除当前账号及全部数据；成功返回 204。 */
    @DELETE("api/v1/me")
    suspend fun deleteMyAccount(
        @Header("Authorization") authorization: String,
    )
}
