package com.platojobs.pkotlinapp.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

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
)

/** GET /sync/changes 中 `payload` 对应的用户设备。 */
data class SyncDeviceDto(
    val id: String? = null,
    val clientId: String? = null,
    val platform: String? = null,
    val externalId: String? = null,
    val mac: String? = null,
    val alias: String? = null,
    val group: String? = null,
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
    val alias: String? = null,
    val group: String? = null,
    val rules: Map<String, Any?> = emptyMap(),
    val scanRecords: List<SyncScanRecordDto> = emptyList(),
    val updatedAt: String,
    val deleted: Boolean = false,
)

data class SyncPushRequest(
    val clientId: String,
    val devices: List<PushDeviceDto>,
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
)

data class SyncChangesResponse(
    val changes: List<SyncChangeDto>? = null,
    val nextCursor: Long? = null,
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
    ): SyncChangesResponse

    @POST("api/v1/sync/push")
    suspend fun push(
        @Header("Authorization") authorization: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: SyncPushRequest,
    ): SyncPushResponse
}
