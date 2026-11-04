package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.AnonymousLoginRequest
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.SyncConflictDto
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
import com.platojobs.pkotlinapp.data.remote.SyncRecordDto
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.model.normalizeExternalId
import com.platojobs.pkotlinapp.domain.sync.SyncConflict
import com.platojobs.pkotlinapp.domain.sync.SyncPullPage
import com.platojobs.pkotlinapp.domain.sync.SyncPushAck
import com.platojobs.pkotlinapp.domain.sync.SyncRecord
import com.platojobs.pkotlinapp.domain.sync.SyncRemote
import retrofit2.HttpException

/**
 * Retrofit 适配：匿名登录 token 仅保存在 [SecureTokenStore]。
 *
 * 服务端契约（v1）：
 * - POST api/v1/auth/anonymous            -> { token }
 * - GET  api/v1/sync/changes?cursor=...   -> { changes: [...], nextCursor }
 * - POST api/v1/sync/push                 -> { accepted: [externalId], conflicts: [...] }
 *   Header: Authorization: Bearer <token>、Idempotency-Key: <stable batch key>
 */
class RemoteSyncDataSource(
    private val api: SyncApi,
    private val tokenStore: SecureTokenStore,
    private val installId: String,
) : SyncRemote {

    override suspend fun push(records: List<SyncRecord>, idempotencyKey: String): SyncPushAck =
        withToken { authorization ->
            val request = SyncPushRequest(records.map { it.toDto() })
            val response = api.push(authorization, idempotencyKey, request)

            // 兼容服务端返回客户端 id 或 externalId 两种 accepted 标识。
            val accepted = response.accepted.orEmpty().mapNotNull { value ->
                records.firstOrNull { it.id == value || it.externalId == value }?.externalId ?: value
            }
            val conflicts = response.conflicts.orEmpty().mapNotNull { it.toDomain(records) }
            SyncPushAck(accepted = accepted, conflicts = conflicts)
        }

    override suspend fun pull(cursor: String?): SyncPullPage =
        withToken { authorization ->
            // 契约使用 cursor；兼容部分服务端命名为 nextCursor 的情况。
            val response = api.changes(authorization, cursor)
            val nextCursor = response.nextCursor ?: response.cursor
            SyncPullPage(
                changes = response.changes.orEmpty().mapNotNull { it.toDomain() },
                nextCursor = nextCursor,
            )
        }

    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = token() ?: throw IllegalStateException("匿名登录未返回 token")
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            tokenStore.clear()
            val refreshed = token() ?: throw IllegalStateException("匿名登录刷新失败")
            block("Bearer $refreshed")
        }
    }

    private suspend fun token(): String? {
        tokenStore.token()?.takeIf { it.isNotBlank() }?.let { return it }
        val response = api.anonymousLogin(
            AnonymousLoginRequest(platform = PLATFORM_ANDROID, installId = installId),
        )
        val token = response.token?.takeIf { it.isNotBlank() } ?: return null
        tokenStore.saveToken(token)
        return token
    }
}

private fun SyncRecord.toDto() = SyncRecordDto(
    id = id,
    platform = platform,
    externalId = externalId,
    name = name,
    lastRssi = lastRssi,
    lastSeenTimestamp = lastSeenTimestamp,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

private fun SyncRecordDto.toDomain(): SyncRecord? {
    val rawExternalId = externalId?.takeIf { it.isNotBlank() } ?: return null
    val safePlatform = platform?.takeIf { it.isNotBlank() } ?: PLATFORM_ANDROID
    val normalized = if (safePlatform == PLATFORM_ANDROID) {
        normalizeExternalId(rawExternalId)
    } else {
        rawExternalId
    }
    return SyncRecord(
        id = id?.takeIf { it.isNotBlank() } ?: normalized,
        platform = safePlatform,
        externalId = normalized,
        name = name.orEmpty(),
        lastRssi = lastRssi ?: 0,
        lastSeenTimestamp = lastSeenTimestamp ?: updatedAt ?: 0L,
        updatedAt = updatedAt ?: 0L,
        deletedAt = deletedAt,
        syncState = SyncState.SYNCED,
    )
}

private fun SyncConflictDto.toDomain(records: List<SyncRecord>): SyncConflict? {
    val resolved = externalId?.takeIf { it.isNotBlank() }
        ?: serverRecord?.externalId?.takeIf { it.isNotBlank() }
        ?: return null
    val server = serverRecord?.toDomain()
    return SyncConflict(
        externalId = records.firstOrNull { it.id == resolved || it.externalId == resolved }
            ?.externalId ?: resolved,
        reason = reason.orEmpty().ifBlank { "server_conflict" },
        serverRecord = server,
    )
}
