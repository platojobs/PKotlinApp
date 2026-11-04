package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.AppliedChangeDto
import com.platojobs.pkotlinapp.data.remote.ConflictDto
import com.platojobs.pkotlinapp.data.remote.PushDeviceDto
import com.platojobs.pkotlinapp.data.remote.Reported
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.SyncChangeDto
import com.platojobs.pkotlinapp.data.remote.SyncDeviceDto
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
import com.platojobs.pkotlinapp.data.remote.SyncScanRecordDto
import com.platojobs.pkotlinapp.data.remote.displayLabel
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.model.normalizeExternalId
import com.platojobs.pkotlinapp.domain.sync.AuthExpiredException
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import com.platojobs.pkotlinapp.domain.sync.SyncConflict
import com.platojobs.pkotlinapp.domain.sync.SyncPullPage
import com.platojobs.pkotlinapp.domain.sync.SyncPushAck
import com.platojobs.pkotlinapp.domain.sync.SyncRecord
import com.platojobs.pkotlinapp.domain.sync.SyncRemote
import retrofit2.HttpException
import java.util.UUID

/**
 * Retrofit 适配，字段与 `PJGoIoT/api/openapi.yaml` 对齐：
 * - `POST api/v1/auth/anonymous`               -> 201 `{userId, token}`（请求体可为空）
 * - `GET  api/v1/sync/changes?cursor=<int>&limit=` -> `{changes, nextCursor, hasMore}`
 *   changes 中每条为 `{seq, entity, entityId, op, payload(UserDevice), serverTs}`
 * - `POST api/v1/sync/push`                    -> `{applied, conflicts, nextCursor}`
 *   Header: `Authorization: Bearer <token>`、`Idempotency-Key: <stable batch key>`
 *
 * UserDevice 与本地模型的字段映射：
 * - `alias` <-> `name`（aliasDirty 记录用户是否显式编辑过，用于区分 absent 与显式清空）
 * - `scanRecords[{rssi, at}]` <-> `lastRssi` / `lastSeenTimestamp`
 * - `updatedAt` / `deletedAt` 为 RFC 3339，本地存 epoch millis
 * - Android 的 `mac` 元数据为小写 MAC，`externalId` 为规范化大写 MAC
 */
class RemoteSyncDataSource(
    private val api: SyncApi,
    private val tokenStore: TokenStore,
    private val installId: String,
) : SyncRemote {

    override suspend fun push(records: List<SyncRecord>, idempotencyKey: String): SyncPushAck =
        withToken { authorization ->
            val request = SyncPushRequest(
                clientId = installId,
                devices = records.map { it.toPushDto() },
            )
            val response = api.push(authorization, idempotencyKey, request)

            // 服务端 accepted 为 applied 数组；用 externalId 对回本地记录。
            val accepted = response.applied.orEmpty().mapNotNull { it.toAcceptedExternalId(records) }
            val conflicts = response.conflicts.orEmpty().map { it.toDomain() }
            SyncPushAck(accepted = accepted, conflicts = conflicts)
        }

    override suspend fun pull(cursor: String?): SyncPullPage =
        withToken { authorization ->
            val response = api.changes(authorization, cursor)
            SyncPullPage(
                changes = response.changes.orEmpty().mapNotNull { it.toDomain() },
                // 契约 cursor 为整数序列号，本地以字符串透传。
                nextCursor = response.nextCursor?.toString(),
            )
        }

    /**
     * 401 与 iOS 保持一致：不自动重新匿名登录（自动重登会切换到另一个账号，
     * 造成同步游标错位并把本机数据推到新账号上），只抛出 [AuthExpiredException]，
     * 交由上层暂停同步并等待用户显式重新登录。
     */
    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = token() ?: throw IllegalStateException("匿名登录未返回 token")
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            if (e.code() == 401) throw AuthExpiredException(cause = e)
            throw e
        }
    }

    private suspend fun token(): String? {
        tokenStore.token()?.takeIf { it.isNotBlank() }?.let { return it }
        val response = api.anonymousLogin()
        val token = response.token?.takeIf { it.isNotBlank() } ?: return null
        tokenStore.saveToken(token)
        return token
    }
}

internal fun SyncRecord.toPushDto(): PushDeviceDto {
    val deviceId = runCatching { UUID.fromString(id) }.getOrNull()?.toString()
        ?: UUID.nameUUIDFromBytes("$platform|$externalId".toByteArray(Charsets.UTF_8)).toString()
    val normalizedExternalId = normalizeExternalIdForPlatform(platform, externalId)
    val scanRecords = if (lastSeenTimestamp > 0) {
        listOf(
            SyncScanRecordDto(
                rssi = lastRssi,
                at = Rfc3339.format(lastSeenTimestamp),
            ),
        )
    } else {
        emptyList()
    }
    return PushDeviceDto(
        id = deviceId,
        platform = platform,
        externalId = normalizedExternalId,
        // Android 的 mac 元数据由服务端规范化为小写；iOS 无 MAC。
        mac = if (platform == PLATFORM_ANDROID) normalizedExternalId.lowercase() else null,
        // 只有本地/云端 enrich 匹配到型号时才上报；null 字段不会序列化，服务端沿用旧值。
        deviceType = deviceType?.takeIf { it.isNotBlank() },
        // 扫描只上报 advertisedName，用户编辑只上报 alias；两者独立。
        // aliasDirty 置位后即使别名为空也要显式上报空串，服务端才会真正清空旧别名。
        alias = if (aliasDirty || name.isNotBlank()) Reported(name) else null,
        advertisedName = advertisedName?.takeIf { it.isNotBlank() }?.let(::Reported),
        // group 三态：null 不上报；空串显式清除；非空覆盖。
        group = group?.let(::Reported),
        rules = emptyMap(),
        scanRecords = scanRecords,
        updatedAt = Rfc3339.format(updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis()),
        deleted = deletedAt != null,
    )
}

internal fun AppliedChangeDto.toAcceptedExternalId(records: List<SyncRecord>): String? {
    val rawExternalId = externalId?.takeIf { it.isNotBlank() }
        ?: records.firstOrNull { it.id == id }?.externalId
        ?: return null
    val normalized = normalizeExternalIdForPlatform(platform, rawExternalId)
    // 服务端返回的是规范化键；对回本地记录的实际 externalId，兼容历史大小写。
    return records.firstOrNull {
        it.externalId == normalized ||
            normalizeExternalIdForPlatform(it.platform, it.externalId) == normalized
    }?.externalId ?: normalized
}

internal fun SyncChangeDto.toDomain(): SyncRecord? {
    val device = payload ?: return null
    val record = device.toDomain() ?: return null
    val tombstone = record.deletedAt
        ?: if (op == "delete") (Rfc3339.parse(serverTs) ?: record.updatedAt) else null
    return record.copy(deletedAt = tombstone)
}

internal fun SyncDeviceDto.toDomain(): SyncRecord? {
    val rawExternalId = externalId?.takeIf { it.isNotBlank() } ?: return null
    val safePlatform = platform?.takeIf { it.isNotBlank() } ?: PLATFORM_ANDROID
    val normalized = normalizeExternalIdForPlatform(safePlatform, rawExternalId)
    val updatedAt = Rfc3339.parse(updatedAt) ?: 0L

    val scans = scanRecords.orEmpty()
    val latestScan = scans.maxByOrNull { Rfc3339.parse(it.at) ?: Long.MIN_VALUE }
    val seenAt = latestScan?.let { Rfc3339.parse(it.at) } ?: updatedAt

    return SyncRecord(
        id = id?.takeIf { it.isNotBlank() } ?: normalized,
        platform = safePlatform,
        externalId = normalized,
        name = alias.orEmpty(),
        advertisedName = advertisedName,
        lastRssi = latestScan?.rssi ?: 0,
        lastSeenTimestamp = seenAt,
        updatedAt = updatedAt,
        deletedAt = Rfc3339.parse(deletedAt),
        syncState = SyncState.SYNCED,
        deviceType = deviceType?.takeIf { it.isNotBlank() },
        vendor = vendor?.takeIf { it.isNotBlank() },
        firmwareVersion = firmwareVersion?.takeIf { it.isNotBlank() },
        compatibility = compatibility?.displayLabel()?.takeIf { it.isNotBlank() },
        // group 保留空串：与服务端「已显式清除」的语义一致；absent(null) 才表示未上报。
        group = group?.value,
    )
}

internal fun ConflictDto.toDomain(): SyncConflict {
    val rawExternalId = externalId?.takeIf { it.isNotBlank() }
        ?: server?.externalId?.takeIf { it.isNotBlank() }
        ?: client?.externalId?.takeIf { it.isNotBlank() }
        ?: ""
    val normalized = normalizeExternalIdForPlatform(platform, rawExternalId)
    return SyncConflict(
        externalId = normalized,
        reason = reason.orEmpty().ifBlank { "server_conflict" },
        serverRecord = server?.toDomain(),
    )
}

internal fun normalizeExternalIdForPlatform(platform: String?, externalId: String): String {
    val safePlatform = platform?.takeIf { it.isNotBlank() } ?: PLATFORM_ANDROID
    return if (safePlatform == PLATFORM_ANDROID) {
        normalizeExternalId(externalId)
    } else {
        externalId.trim().uppercase()
    }
}
