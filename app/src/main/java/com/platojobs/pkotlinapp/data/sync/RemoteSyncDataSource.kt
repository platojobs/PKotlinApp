package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.ApiLimits
import com.platojobs.pkotlinapp.data.remote.AppliedChangeDto
import com.platojobs.pkotlinapp.data.remote.ConflictDto
import com.platojobs.pkotlinapp.data.remote.NullableReported
import com.platojobs.pkotlinapp.data.remote.PushDeviceDto
import com.platojobs.pkotlinapp.data.remote.PushReadingDto
import com.platojobs.pkotlinapp.data.remote.Reported
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.remote.SyncChangeDto
import com.platojobs.pkotlinapp.data.remote.SyncDeviceDto
import com.platojobs.pkotlinapp.data.remote.SyncPushRequest
import com.platojobs.pkotlinapp.data.remote.SyncScanRecordDto
import com.platojobs.pkotlinapp.data.remote.displayLabel
import com.platojobs.pkotlinapp.data.remote.toSyncException
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.model.normalizeExternalId
import com.platojobs.pkotlinapp.domain.reading.CloudReading
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingBucket
import com.platojobs.pkotlinapp.domain.reading.ReadingMetric
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.reading.LatestReadingRemote
import com.platojobs.pkotlinapp.domain.reading.ReadingPage
import com.platojobs.pkotlinapp.domain.reading.ReadingPushAck
import com.platojobs.pkotlinapp.domain.reading.ReadingRemote
import com.platojobs.pkotlinapp.domain.reading.ReadingSeries
import com.platojobs.pkotlinapp.domain.reading.ReadingSummary
import com.platojobs.pkotlinapp.domain.reading.RemoteReading
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
    /** 匿名登录写入新会话后通知推送注册；默认空实现，便于既有测试替身。 */
    private val onSessionReady: () -> Unit = {},
) : SyncRemote, ReadingRemote, LatestReadingRemote {

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
            val response = api.changes(
                authorization = authorization,
                cursor = cursor,
                limit = ApiLimits.CHANGES_MAX,
            )
            val parsed = response.changes.orEmpty().mapNotNull { it.toSyncChangeEntry() }
            SyncPullPage(
                changes = parsed.filterIsInstance<SyncChangeEntry.Device>().map { it.record },
                // 未知 entity 在 parsed 中已消失，但 nextCursor 仍按服务端值前进。
                structuralChanges = parsed
                    .filterIsInstance<SyncChangeEntry.Structure>()
                    .map { it.change },
                // 契约 cursor 为整数序列号，本地以字符串透传。
                nextCursor = response.nextCursor?.toString(),
            )
        }

    override suspend fun pushReadings(
        readings: List<Reading>,
        idempotencyKey: String,
    ): ReadingPushAck = withToken { authorization ->
        val request = SyncPushRequest(
            clientId = installId,
            devices = emptyList(),
            readings = readings.map { it.toPushDto() },
        )
        val response = api.push(authorization, idempotencyKey, request)
        ReadingPushAck(
            applied = response.appliedReadings,
            skipped = response.skippedReadings,
        )
    }

    override suspend fun queryReadings(
        deviceId: String,
        metric: String?,
        from: Long?,
        to: Long?,
        limit: Int,
        cursor: String?,
    ): ReadingPage = withToken { authorization ->
        val externalId = normalizeExternalIdForPlatform(PLATFORM_ANDROID, deviceId)
        val response = api.readings(
            authorization = authorization,
            platform = PLATFORM_ANDROID,
            externalId = externalId,
            metric = metric,
            from = from?.let(Rfc3339::format),
            to = to?.let(Rfc3339::format),
            // 契约上限 500；客户端分页默认 100，这里再兜底收敛，避免超限 400。
            limit = limit.coerceIn(1, ApiLimits.READINGS_MAX),
            cursor = cursor,
        )
        ReadingPage(
            items = response.items.orEmpty().mapNotNull { item ->
                val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = item.value ?: return@mapNotNull null
                if (!value.isFinite()) return@mapNotNull null
                RemoteReading(
                    id = id,
                    metric = item.metric.orEmpty(),
                    value = value,
                    unit = item.unit.orEmpty(),
                    recordedAt = Rfc3339.parse(item.recordedAt) ?: 0L,
                )
            },
            nextCursor = response.nextCursor,
            hasMore = response.hasMore == true,
        )
    }

    override suspend fun remoteSummary(
        deviceId: String,
        metric: String,
        hours: Int,
    ): ReadingSummary = withToken { authorization ->
        val externalId = normalizeExternalIdForPlatform(PLATFORM_ANDROID, deviceId)
        val response = api.readingsSummary(
            authorization = authorization,
            platform = PLATFORM_ANDROID,
            externalId = externalId,
            metric = metric,
            hours = hours.coerceAtLeast(1),
        )
        ReadingSummary(
            metric = response.metric ?: metric,
            unit = response.unit ?: ReadingMetrics.unit(metric),
            count = response.count ?: 0,
            min = response.min,
            max = response.max,
            avg = response.avg,
            last = response.last,
            windowStart = Rfc3339.parse(response.windowStart) ?: 0L,
            windowEnd = Rfc3339.parse(response.windowEnd) ?: 0L,
        )
    }

    override suspend fun series(
        deviceId: String,
        metric: String,
        from: Long,
        to: Long,
        bucket: String,
    ): ReadingSeries = withToken { authorization ->
        val externalId = normalizeExternalIdForPlatform(PLATFORM_ANDROID, deviceId)
        val response = api.readingsSeries(
            authorization = authorization,
            platform = PLATFORM_ANDROID,
            externalId = externalId,
            metric = metric,
            from = Rfc3339.format(from),
            to = Rfc3339.format(to),
            bucket = bucket,
        )
        ReadingSeries(
            metric = response.metric?.takeIf { it.isNotBlank() } ?: metric,
            unit = response.unit ?: ReadingMetrics.unit(metric),
            bucket = response.bucket?.takeIf { it.isNotBlank() } ?: bucket,
            from = Rfc3339.parse(response.from) ?: from,
            to = Rfc3339.parse(response.to) ?: to,
            buckets = response.buckets.orEmpty().mapNotNull { item ->
                val start = Rfc3339.parse(item.start) ?: return@mapNotNull null
                val end = Rfc3339.parse(item.end) ?: return@mapNotNull null
                val count = item.count ?: return@mapNotNull null
                val avg = item.avg ?: return@mapNotNull null
                val min = item.min ?: avg
                val max = item.max ?: avg
                val last = item.last ?: avg
                if (!avg.isFinite() || !min.isFinite() || !max.isFinite() || !last.isFinite()) {
                    return@mapNotNull null
                }
                ReadingBucket(
                    start = start,
                    end = end,
                    count = count,
                    min = min,
                    max = max,
                    avg = avg,
                    last = last,
                )
            }.sortedBy { it.start },
        )
    }

    override suspend fun metrics(deviceId: String): List<ReadingMetric> =
        withToken { authorization ->
            val externalId = normalizeExternalIdForPlatform(PLATFORM_ANDROID, deviceId)
            val response = api.readingsMetrics(
                authorization = authorization,
                platform = PLATFORM_ANDROID,
                externalId = externalId,
            )
            response.items.orEmpty().mapNotNull { item ->
                val metric = item.metric?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ReadingMetric(
                    metric = metric,
                    unit = item.unit.orEmpty().ifBlank { ReadingMetrics.unit(metric) },
                    count = item.count ?: 0L,
                    firstAt = Rfc3339.parse(item.firstAt) ?: 0L,
                    lastAt = Rfc3339.parse(item.lastAt) ?: 0L,
                )
            }
        }

    /**
     * 云端最近读数：先取 `/readings/metrics` 找 `lastAt` 最新的指标，
     * 再按 `limit=1` 取该指标最近一条。任何指标都没有时返回 null（空态）。
     */
    override suspend fun latest(platform: String, externalId: String): CloudReading? =
        withToken { authorization ->
            val safePlatform = platform.trim().lowercase().ifBlank { PLATFORM_ANDROID }
            // Retrofit @Path 默认 encoded=false：这里的冒号 MAC 只会被编码一次（%3A）。
            val normalized = normalizeExternalIdForPlatform(safePlatform, externalId)
            val newest = api.readingsMetrics(authorization, safePlatform, normalized)
                .items.orEmpty()
                .mapNotNull { item ->
                    val metric = item.metric?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val lastAt = Rfc3339.parse(item.lastAt) ?: return@mapNotNull null
                    metric to lastAt
                }
                .maxByOrNull { it.second }
                ?: return@withToken null

            val page = api.readings(
                authorization = authorization,
                platform = safePlatform,
                externalId = normalized,
                metric = newest.first,
                from = null,
                to = null,
                limit = 1,
                cursor = null,
            )
            val item = page.items.orEmpty().firstOrNull() ?: return@withToken null
            val value = item.value ?: return@withToken null
            if (!value.isFinite()) return@withToken null
            CloudReading(
                metric = item.metric?.takeIf { it.isNotBlank() } ?: newest.first,
                value = value,
                unit = item.unit.orEmpty().ifBlank { ReadingMetrics.unit(newest.first) },
                recordedAt = Rfc3339.parse(item.recordedAt) ?: 0L,
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
            // 401→重登；410→游标失效；503 TIMEOUT→服务端繁忙，三者分开。
            throw e.toSyncException() ?: e
        }
    }

    private suspend fun token(): String? {
        tokenStore.token()?.takeIf { it.isNotBlank() }?.let { return it }
        // 刷新失败后进入重新登录态：禁止静默新建匿名账号，避免原账号数据「消失」。
        if (tokenStore.reloginRequired()) throw AuthExpiredException()
        val response = api.anonymousLogin()
        val token = response.token?.takeIf { it.isNotBlank() } ?: return null
        tokenStore.saveSession(token, Rfc3339.parse(response.expiresAt))
        onSessionReady()
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
        // markedAt 三态：未脏不上报；脏且为空=JSON null 取消；脏且非空=RFC3339。
        markedAt = if (markedAtDirty) {
            NullableReported(markedAt?.let(Rfc3339::format))
        } else {
            null
        },
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
    // 本机扫描证据只来自 scanRecords；不再拿 updatedAt 冒充心跳。
    val seenAt = latestScan?.let { Rfc3339.parse(it.at) } ?: 0L
    // 三态：属性缺失=老后端（保持本地值）；显式 JSON null/空串=从未上报（清空）。
    val lastSeenField = lastSeenAt
    val serverLastSeenAt = lastSeenField?.value?.takeIf { it.isNotBlank() }?.let(Rfc3339::parse)

    // markedAt 三态：属性缺失=保持；JSON null 或空串=取消；合法 RFC3339=标记。
    val markField = markedAt
    val markRaw = markField?.value
    val parsedMark = markRaw?.takeIf { it.isNotBlank() }?.let(Rfc3339::parse)
    val markReported = markField != null && (markRaw.isNullOrBlank() || parsedMark != null)

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
        markedAt = parsedMark,
        markedAtDirty = markReported,
        lastSeenAt = serverLastSeenAt,
        hasLastSeenAt = lastSeenField != null,
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

internal fun Reading.toPushDto(): PushReadingDto = PushReadingDto(
    id = id,
    platform = PLATFORM_ANDROID,
    externalId = normalizeExternalIdForPlatform(PLATFORM_ANDROID, deviceId),
    metric = ReadingMetrics.normalize(metric),
    value = value,
    unit = unit,
    recordedAt = Rfc3339.format(recordedAt),
)

internal fun normalizeExternalIdForPlatform(platform: String?, externalId: String): String {
    val safePlatform = platform?.takeIf { it.isNotBlank() } ?: PLATFORM_ANDROID
    return if (safePlatform == PLATFORM_ANDROID) {
        normalizeExternalId(externalId)
    } else {
        externalId.trim().uppercase()
    }
}
