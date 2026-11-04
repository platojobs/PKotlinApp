package com.platojobs.pkotlinapp.domain.sync

import com.platojobs.pkotlinapp.domain.model.SyncState

/** 跨端同步的统一记录：设备唯一键为 (platform, externalId)。 */
data class SyncRecord(
    val id: String,
    val platform: String,
    val externalId: String,
    val name: String,
    val lastRssi: Int,
    val lastSeenTimestamp: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
)

data class SyncPullPage(
    val changes: List<SyncRecord>,
    val nextCursor: String?,
)

data class SyncConflict(
    val externalId: String,
    val reason: String,
    val serverRecord: SyncRecord?,
)

data class SyncPushAck(
    val accepted: List<String>,
    val conflicts: List<SyncConflict> = emptyList(),
)

data class SyncPushOutcome(
    val pushed: Int,
    val conflicts: List<SyncConflict>,
)

data class SyncOutcome(
    val pushed: Int,
    val pulled: Int,
    val conflicts: List<SyncConflict>,
)

interface SyncRemote {
    suspend fun push(records: List<SyncRecord>, idempotencyKey: String): SyncPushAck

    suspend fun pull(cursor: String?): SyncPullPage
}

interface SyncLocalStore {
    suspend fun pending(limit: Int): List<SyncRecord>

    suspend fun findActive(externalId: String): SyncRecord?

    suspend fun markSynced(externalIds: List<String>)

    /** 服务端版本覆盖本地，并标记为 synced。 */
    suspend fun replaceWithServer(record: SyncRecord)

    suspend fun deleteByExternalId(externalId: String)
}

interface SyncCursorStore {
    var cursor: String?
}

/**
 * 推送批次幂等键：同一批未变化记录总是得到同一个 key，
 * 网络失败重试时服务端可据此去重。
 *
 * key 覆盖实际推送的业务内容（含 alias/RSSI/时间），确保「同 key + 同请求体」重放，
 * 内容变化时不会命中旧的幂等缓存而以 409 失败。
 */
object IdempotencyKeys {
    fun forBatch(records: List<SyncRecord>): String {
        val canonical = records
            .sortedWith(compareBy({ it.platform }, { it.externalId }, { it.updatedAt }))
            .joinToString("\u001F") {
                listOf(
                    it.platform,
                    it.externalId,
                    it.id,
                    it.name,
                    it.lastRssi,
                    it.lastSeenTimestamp,
                    it.updatedAt,
                    it.deletedAt ?: 0L,
                ).joinToString("\u001E")
            }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return "sync-" + digest.joinToString("") { byte ->
            byte.toInt().toUInt().toString(16).padStart(2, '0')
        }.take(40)
    }
}

/**
 * 纯 Kotlin 同步引擎：不依赖 Android / Room / Retrofit，可直接用 JVM 单测覆盖。
 *
 * 规则：
 * - 本地待上传记录按批推送，成功后置 synced；
 * - 冲突以服务端版本为准，但必须通过 [SyncPushOutcome.conflicts] 上报，不静默覆盖；
 * - 本地墓碑推送成功后再物理删除；
 * - 拉取时若本地有更新的待同步版本，则不被较旧的服务端版本覆盖。
 */
class SyncEngine(
    private val local: SyncLocalStore,
    private val remote: SyncRemote,
    private val cursorStore: SyncCursorStore,
) {

    suspend fun pushPending(limit: Int = 50): SyncPushOutcome {
        val pending = local.pending(limit)
        if (pending.isEmpty()) return SyncPushOutcome(0, emptyList())

        val ack = remote.push(pending, IdempotencyKeys.forBatch(pending))

        // 没有服务端版本可合并的冲突必须保持 pending，不能静默当作已完成。
        val unresolvedConflicts = ack.conflicts
            .filter { it.serverRecord == null }
            .map { it.externalId }
            .toSet()
        val resolvedAccepted = ack.accepted.filterNot { it in unresolvedConflicts }

        local.markSynced(resolvedAccepted)
        pending
            .filter { it.deletedAt != null && it.externalId in resolvedAccepted }
            .forEach { local.deleteByExternalId(it.externalId) }

        ack.conflicts.forEach { conflict ->
            val server = conflict.serverRecord ?: return@forEach
            if (server.deletedAt != null) {
                local.deleteByExternalId(server.externalId)
            } else {
                local.replaceWithServer(server)
            }
        }

        return SyncPushOutcome(
            pushed = resolvedAccepted.size,
            conflicts = ack.conflicts,
        )
    }

    suspend fun pullChanges(): Int {
        val page = remote.pull(cursorStore.cursor)
        page.changes.forEach { change ->
            if (change.deletedAt != null) {
                local.deleteByExternalId(change.externalId)
            } else {
                val localRecord = local.findActive(change.externalId)
                val localUnsyncedWins = localRecord != null &&
                    localRecord.syncState == SyncState.PENDING &&
                    localRecord.updatedAt > change.updatedAt
                if (!localUnsyncedWins) {
                    local.replaceWithServer(change)
                }
            }
        }
        page.nextCursor?.let { cursorStore.cursor = it }
        return page.changes.size
    }

    suspend fun syncAll(): SyncOutcome {
        val push = pushPending()
        val pulled = pullChanges()
        return SyncOutcome(
            pushed = push.pushed,
            pulled = pulled,
            conflicts = push.conflicts,
        )
    }
}
