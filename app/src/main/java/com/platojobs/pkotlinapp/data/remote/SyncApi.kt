package com.platojobs.pkotlinapp.data.remote

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import java.lang.reflect.Type
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
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

/** GET /sessions 的单条会话；不包含 token 本身，服务端只存哈希。 */
data class SessionDto(
    val id: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val current: Boolean? = null,
)

data class SessionListResponse(
    val sessions: List<SessionDto>? = null,
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
    // openapi 0.12.0 家庭归属；未归属时省略或为 null。
    val householdId: String? = null,
    val roomId: String? = null,
    // openapi 0.11.0 household/room change payload 的复用字段；设备 payload 不使用。
    val name: String? = null,
    val ownerUserId: String? = null,
    val role: String? = null,
    val memberCount: Int? = null,
    val sortOrder: Int? = null,
    val deleted: Boolean? = null,
    // openapi 0.16.0 scene change payload 的复用字段；设备 payload 不使用。
    val enabled: Boolean? = null,
    val cooldownSeconds: Int? = null,
    val trigger: SceneTriggerDto? = null,
    val actions: List<SceneActionDto>? = null,
    val version: Long? = null,
    val deviceMissing: Boolean? = null,
    val createdAt: String? = null,
)

/** GET /my/devices 的返回：本人设备 + 家庭成员共享设备。 */
data class MyDevicesResponse(
    val items: List<SyncDeviceDto>? = null,
)

// ---- openapi 0.8.0：家庭 / 成员 / 邀请码 / 房间 ----

data class HouseholdDto(
    val id: String? = null,
    val name: String? = null,
    val ownerUserId: String? = null,
    val role: String? = null,
    val personal: Boolean? = null,
    val memberCount: Int? = null,
    val deviceCount: Int? = null,
    val roomCount: Int? = null,
    val createdAt: String? = null,
)

data class HouseholdListResponse(
    val items: List<HouseholdDto>? = null,
)

data class HouseholdCreateRequest(val name: String)

data class HouseholdPatchRequest(val name: String)

data class HouseholdMemberDto(
    val userId: String? = null,
    val role: String? = null,
    val kind: String? = null,
    val joinedAt: String? = null,
    /** 服务端（v0.14.0 起）标记该行是否为当前账号；缺失表示老后端。 */
    val isSelf: Boolean? = null,
    /** 可选的成员展示名；当前后端为匿名账号，通常为 null。 */
    val displayName: String? = null,
    val name: String? = null,
    val nickname: String? = null,
)

data class HouseholdOwnershipTransferRequest(val userId: String)

data class HouseholdMemberListResponse(
    val items: List<HouseholdMemberDto>? = null,
)

data class HouseholdInvitationDto(
    val code: String? = null,
    /** RFC3339；服务端签发后 10 分钟。 */
    val expiresAt: String? = null,
)

data class HouseholdInvitationClaimRequest(val code: String)

/** 邀请码管理视图：只有哈希前缀，绝不包含明文。 */
data class HouseholdInvitationInfoDto(
    val id: String? = null,
    val codeHashPrefix: String? = null,
    val status: String? = null,
    val attempts: Int? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
)

data class HouseholdInvitationInfoListResponse(
    val items: List<HouseholdInvitationInfoDto>? = null,
)

/** 整段重排：roomIds 必须恰好覆盖家庭全部房间。 */
data class HouseholdRoomOrderRequest(val roomIds: List<String>)

data class HouseholdRoomDto(
    val id: String? = null,
    val householdId: String? = null,
    val name: String? = null,
    val sortOrder: Int? = null,
    val createdAt: String? = null,
)

data class HouseholdRoomListResponse(
    val items: List<HouseholdRoomDto>? = null,
)

data class HouseholdRoomCreateRequest(val name: String, val sortOrder: Int)

/** PATCH 房间：null 字段由 Moshi 省略，表示保持不变。 */
data class HouseholdRoomPatchRequest(val name: String? = null, val sortOrder: Int? = null)

/**
 * PATCH /my/devices/{platform}/{externalId} 的三态请求。
 * 字段为 null 表示缺失（保持不变）；`Reported("")` 表示清除；`Reported(值)` 表示覆盖。
 */
data class DeviceMetaPatchRequest(
    val alias: Reported<String>? = null,
    val roomId: Reported<String>? = null,
    val householdId: Reported<String>? = null,
)

data class SyncScanRecordDto(
    val rssi: Int? = null,
    val at: String? = null,
)

/**
 * 单条增量变更。
 *
 * `payload` 是 entity 的联合体：设备字段之外，openapi 0.11.0 的
 * household / room 也复用同一结构（`name`、`ownerUserId`、`role`、
 * `memberCount`、`sortOrder`、`deleted`）。未知 entity 的未知字段
 * 会被 Moshi 忽略，解析层按 entity 显式分派后丢弃。
 */
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

/** POST /me/deletion-request 201：一次性删除令牌，明文只出现一次。 */
data class DeletionRequestResponse(
    val token: String? = null,
    val expiresAt: String? = null,
)

/**
 * POST /me/export 与 GET /me/export/{id} 的响应。
 * status 为 pending / ready / failed；ready 时 downloadUrl 为相对路径，
 * 含一次性下载令牌，10 分钟有效且只能用一次。
 */
data class ExportTaskResponse(
    val id: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
    val completedAt: String? = null,
    val downloadUrl: String? = null,
    val downloadExpiresAt: String? = null,
    val expiresAt: String? = null,
    /** 失败时的服务端可读原因（如「导出任务因服务重启中断，请重新发起导出」）。 */
    val failureReason: String? = null,
)

/** 反馈提交上下文（openapi 0.12.0 `FeedbackClientContext`），由客户端自动填充。 */
data class FeedbackContextDto(
    val platform: String = "android",
    val appVersion: String,
    val buildType: String,
    val osVersion: String,
    val deviceModel: String,
)

/** POST /feedback 请求体；字段与后端 `FeedbackCreate` 对齐。 */
data class FeedbackCreateRequest(
    val kind: String,
    val body: String,
    val contact: String? = null,
    val context: FeedbackContextDto,
    val diagnostics: String? = null,
)

/** POST /feedback 201 响应。 */
data class FeedbackCreatedDto(
    val feedbackId: String? = null,
    val createdAt: String? = null,
)

/**
 * GET /feedback/mine 列表项。
 *
 * 契约明确列表 **不返回** contact 与 diagnostics；这里刻意不声明这两个字段，
 * 既避免误用，也让契约测试可以通过反射断言「列表 DTO 没有隐私字段」。
 */
data class FeedbackItemDto(
    val feedbackId: String? = null,
    val kind: String? = null,
    val body: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
    val buildType: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
)

data class FeedbackPageResponse(
    val items: List<FeedbackItemDto>? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean? = null,
)

/** GET /feedback/{id}：本人单条反馈详情，含状态说明，不含 contact/diagnostics。 */
data class FeedbackDetailDto(
    val feedbackId: String? = null,
    val kind: String? = null,
    val body: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
    val buildType: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val status: String? = null,
    /** 给用户看的状态说明，如「已受理，工作人员正在处理」。 */
    val statusMessage: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** PATCH /feedback/{id}：用户侧只允许关闭，固定 status=closed。 */
data class FeedbackCloseRequest(
    val status: String = "closed",
)

/** POST /push/devices 请求体；缺 platform/token 会返回 400 PUSH_DEVICE_INVALID。 */
data class PushDeviceRegistrationRequest(
    val platform: String = "android",
    val token: String,
    val appVersion: String? = null,
    val deviceModel: String? = null,
)

/** POST /push/devices 响应（服务端 PushRegistration）。 */
data class PushDeviceRegistrationResponse(
    val id: String? = null,
    val platform: String? = null,
    val token: String? = null,
    val appVersion: String? = null,
    val deviceModel: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

interface SyncApi {

    /** 匿名登录请求体按契约可为空；installId 通过 clientId 放入 push 请求。 */
    @POST("api/v1/auth/anonymous")
    suspend fun anonymousLogin(): AnonymousLoginResponse

    /**
     * 滚动刷新：旧 token 在同一事务内失效，响应与匿名登录相同。
     * 由 OkHttp Authenticator 调用，失败后进入重新登录态，绝不自动新建匿名账号。
     */
    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(
        @Header("Authorization") authorization: String,
    ): AnonymousLoginResponse

    /** 吊销当前 token（退出当前会话，不删除账号）。 */
    @POST("api/v1/auth/logout")
    suspend fun logout(
        @Header("Authorization") authorization: String,
    )

    /** 当前账号未吊销且未过期的会话，按创建时间倒序；current=true 为本请求所用会话。 */
    @GET("api/v1/sessions")
    suspend fun listSessions(
        @Header("Authorization") authorization: String,
    ): SessionListResponse

    /** 吊销单个会话；404 表示不存在/属于他人，已吊销重复调用为 204（幂等）。 */
    @DELETE("api/v1/sessions/{id}")
    suspend fun revokeSession(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    )

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

    /** 账号数据导出：创建异步任务（202）。 */
    @POST("api/v1/me/export")
    suspend fun createMyDataExport(
        @Header("Authorization") authorization: String,
    ): ExportTaskResponse

    /** 查询导出任务；ready 时返回新的 10 分钟一次性下载链接。 */
    @GET("api/v1/me/export/{id}")
    suspend fun myDataExport(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): ExportTaskResponse

    /** 申请一次性删除令牌（Bearer）；同一账号再次申请会让旧令牌失效。 */
    @POST("api/v1/me/deletion-request")
    suspend fun createDeletionRequest(
        @Header("Authorization") authorization: String,
    ): DeletionRequestResponse

    /**
     * 用一次性令牌硬删除账号；**不使用 Bearer**，避免删除后重放永远得到 401。
     * 成功返回 204。
     */
    @DELETE("api/v1/me")
    suspend fun deleteMyAccount(
        @Header("X-Deletion-Token") deletionToken: String,
    )

    // ---- 家庭（openapi 0.8.0 / 后端 v0.12.0） ----

    @GET("api/v1/households")
    suspend fun households(
        @Header("Authorization") authorization: String,
    ): HouseholdListResponse

    @POST("api/v1/households")
    suspend fun createHousehold(
        @Header("Authorization") authorization: String,
        @Body body: HouseholdCreateRequest,
    ): HouseholdDto

    @GET("api/v1/households/{id}")
    suspend fun household(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): HouseholdDto

    @PATCH("api/v1/households/{id}")
    suspend fun patchHousehold(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body body: HouseholdPatchRequest,
    ): HouseholdDto

    @DELETE("api/v1/households/{id}")
    suspend fun deleteHousehold(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    )

    /** 当前用户可见设备（本人 + 家庭成员共享）。 */
    @GET("api/v1/my/devices")
    suspend fun myDevices(
        @Header("Authorization") authorization: String,
    ): MyDevicesResponse

    /** 设备别名/房间/家庭三态更新；仅 owner 可调用。 */
    @PATCH("api/v1/my/devices/{platform}/{externalId}")
    suspend fun patchMyDevice(
        @Header("Authorization") authorization: String,
        @Path("platform") platform: String,
        @Path("externalId") externalId: String,
        @Body body: DeviceMetaPatchRequest,
    ): SyncDeviceDto

    @GET("api/v1/households/{id}/members")
    suspend fun householdMembers(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): HouseholdMemberListResponse

    @DELETE("api/v1/households/{id}/members/{userId}")
    suspend fun removeHouseholdMember(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("userId") userId: String,
    )

    @POST("api/v1/households/{id}/leave")
    suspend fun leaveHousehold(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    )

    /** 转让所有权：仅 owner 可调用，目标必须是同家庭的其他成员。 */
    @POST("api/v1/households/{id}/transfer-ownership")
    suspend fun transferHouseholdOwnership(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body body: HouseholdOwnershipTransferRequest,
    ): HouseholdDto

    @POST("api/v1/households/{id}/invitations")
    suspend fun createHouseholdInvitation(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): HouseholdInvitationDto

    @GET("api/v1/households/{id}/invitations")
    suspend fun householdInvitations(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): HouseholdInvitationInfoListResponse

    @DELETE("api/v1/households/{id}/invitations/{invitationId}")
    suspend fun revokeHouseholdInvitation(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("invitationId") invitationId: String,
    )

    @POST("api/v1/households/invitations/claim")
    suspend fun claimHouseholdInvitation(
        @Header("Authorization") authorization: String,
        @Body body: HouseholdInvitationClaimRequest,
    ): HouseholdDto

    @GET("api/v1/households/{id}/rooms")
    suspend fun householdRooms(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): HouseholdRoomListResponse

    @POST("api/v1/households/{id}/rooms")
    suspend fun createHouseholdRoom(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body body: HouseholdRoomCreateRequest,
    ): HouseholdRoomDto

    @PATCH("api/v1/households/{id}/rooms/{roomId}")
    suspend fun patchHouseholdRoom(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("roomId") roomId: String,
        @Body body: HouseholdRoomPatchRequest,
    ): HouseholdRoomDto

    @DELETE("api/v1/households/{id}/rooms/{roomId}")
    suspend fun deleteHouseholdRoom(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("roomId") roomId: String,
    )

    @PUT("api/v1/households/{id}/rooms/order")
    suspend fun reorderHouseholdRooms(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body body: HouseholdRoomOrderRequest,
    ): HouseholdRoomListResponse

    // ---- 反馈（openapi 0.12.0 / 后端 v0.16.0） ----

    /** 幂等键优先取 `Idempotency-Key` 头；重复内容重放会带 `Idempotent-Replayed: true`。 */
    @POST("api/v1/feedback")
    suspend fun createFeedback(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: FeedbackCreateRequest,
    ): FeedbackCreatedDto

    /** 只返回当前账号的反馈，按 createdAt 倒序游标分页。 */
    @GET("api/v1/feedback/mine")
    suspend fun listMyFeedback(
        @Header("Authorization") authorization: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): FeedbackPageResponse

    /** 本人单条反馈详情；不存在或属于他人统一 404。 */
    @GET("api/v1/feedback/{id}")
    suspend fun getMyFeedback(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): FeedbackDetailDto

    /** 关闭本人反馈；已关闭再改返回 409 FEEDBACK_INVALID_TRANSITION。 */
    @PATCH("api/v1/feedback/{id}")
    suspend fun closeMyFeedback(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body body: FeedbackCloseRequest,
    ): FeedbackDetailDto

    // ---- 推送设备（openapi 0.18.0 / 后端 v0.23.0） ----

    /** 同一 (user_id, token) 幂等 upsert；inactive 会重置为 active。 */
    @POST("api/v1/push/devices")
    suspend fun registerPushDevice(
        @Header("Authorization") authorization: String,
        @Body body: PushDeviceRegistrationRequest,
    ): PushDeviceRegistrationResponse

    /** 置 inactive，不硬删；404 表示不存在/属于他人，客户端按已清理处理。 */
    @DELETE("api/v1/push/devices/{deviceId}")
    suspend fun deactivatePushDevice(
        @Header("Authorization") authorization: String,
        @Path("deviceId") deviceId: String,
    )
}

/** 场景接口独立于 SyncApi，避免设备同步的测试假实现被场景端点绑架。 */
interface SceneApi {

    /** 一次返回本人全部未删除场景（无分页）。 */
    @GET("api/v1/scenes")
    suspend fun listScenes(
        @Header("Authorization") authorization: String,
    ): SceneListResponse

    @POST("api/v1/scenes")
    suspend fun createScene(
        @Header("Authorization") authorization: String,
        @Body body: SceneInputDto,
    ): SceneDto

    @GET("api/v1/scenes/{sceneId}")
    suspend fun getScene(
        @Header("Authorization") authorization: String,
        @Path("sceneId") sceneId: String,
    ): SceneDto

    @PUT("api/v1/scenes/{sceneId}")
    suspend fun updateScene(
        @Header("Authorization") authorization: String,
        @Path("sceneId") sceneId: String,
        @Body body: ScenePatchDto,
    ): SceneDto

    /** 204 软删除；跨用户与已删除统一 404。 */
    @DELETE("api/v1/scenes/{sceneId}")
    suspend fun deleteScene(
        @Header("Authorization") authorization: String,
        @Path("sceneId") sceneId: String,
    )
}
