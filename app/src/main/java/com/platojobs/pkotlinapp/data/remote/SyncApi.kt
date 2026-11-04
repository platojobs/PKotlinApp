package com.platojobs.pkotlinapp.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

data class AnonymousLoginRequest(
    val platform: String = "android",
    val installId: String,
)

data class AnonymousLoginResponse(
    val token: String? = null,
    val userId: String? = null,
)

data class SyncRecordDto(
    val id: String? = null,
    val platform: String? = null,
    val externalId: String? = null,
    val name: String? = null,
    val lastRssi: Int? = null,
    val lastSeenTimestamp: Long? = null,
    val updatedAt: Long? = null,
    val deletedAt: Long? = null,
)

data class SyncChangesResponse(
    val changes: List<SyncRecordDto>? = null,
    val cursor: String? = null,
    val nextCursor: String? = null,
)

data class SyncPushRequest(
    val records: List<SyncRecordDto>,
)

data class SyncConflictDto(
    val externalId: String? = null,
    val reason: String? = null,
    val serverRecord: SyncRecordDto? = null,
)

data class SyncPushResponse(
    val accepted: List<String>? = null,
    val conflicts: List<SyncConflictDto>? = null,
)

interface SyncApi {

    @POST("api/v1/auth/anonymous")
    suspend fun anonymousLogin(@Body body: AnonymousLoginRequest): AnonymousLoginResponse

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
