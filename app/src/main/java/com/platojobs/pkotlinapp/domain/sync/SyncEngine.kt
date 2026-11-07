package com.platojobs.pkotlinapp.domain.sync

import com.platojobs.pkotlinapp.domain.model.SyncState

/** 跨端同步的统一记录：设备唯一键为 (platform, externalId)。 */
data class SyncRecord(
    val id: String,
    val platform: String,
    val externalId: String,
    val name: String,
    /**
     * 用户是否显式编辑过别名（含清空）。扫描记录永远为 false；
     * 为 true 时即使 [name] 为空也要上报 `alias: ""`。
     */
    val aliasDirty: Boolean = false,
    val lastRssi: Int,
    val lastSeenTimestamp: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.SYNCED,
    // enrich 字段：仅在本地缓存或 catalog 匹配到时才回传，null 表示不覆盖服务端旧值。
    val deviceType: String? = null,
    val vendor: String? = null,
    val firmwareVersion: String? = null,
    val compatibility: String? = null,
    /** catalog 展示名：只用于本地离线降级，不上报服务端。 */
    val deviceTypeName: String? = null,
    /** 设备广播名；null 表示本次不上报，不覆盖服务端旧值。 */
    val advertisedName: String? = null,
    /**
     * 云端/用户编辑的分组。三态：
     * - null：未上报（absent），服务端保持原值；
     * - ""：显式清除；
     * - 非空：覆盖。
     */
    val group: String? = null,
    /**
     * 设备标记时间（毫秒）。配合 [markedAtDirty] 构成三态：
     * - dirty=false：未上报（absent），服务端保持原值；
     * - dirty=true 且 markedAt=null：显式取消（JSON null）；
     * - dirty=true 且非空：标记。
     */
    val markedAt: Long? = null,
    val markedAtDirty: Boolean = false,
    /**
     * 服务端最后一次接受读数上报的接收时间；null 表示从未上报。
     * 服务端只读：不参与 push、幂等哈希与 LWW，写入本地也不推进 updatedAt / 置脏。
     */
    val lastSeenAt: Long? = null,
    /**
     * 服务端响应是否携带了 `lastSeenAt`：[false] 表示老后端缺省，本地保留旧值；
     * [true] 且 [lastSeenAt] 为 null 表示服务端明确「从未上报」，必须清空本地值。
     */
    val hasLastSeenAt: Boolean = false,
)

data class SyncPullPage(
    val changes: List<SyncRecord>,
    val nextCursor: String?,
    /**
     * 家庭/房间结构变更。老客户端不认识的 entity 在这里为空，
     * 但 [nextCursor] 仍然前进（忽略未知类型是硬约束）。
     */
    val structuralChanges: List<StructuralChange> = emptyList(),
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

    /**
     * 服务端只读字段：即使本地待同步版本胜出（LWW）也要写入，避免新代码的 presence
     * 被本机未上传的修改永远挡住。默认空实现便于既有测试替身。
     *
     * [hasLastSeenAt] 为 false 时必须 no-op（老后端缺省，保留本地值）；
     * 为 true 时按 [lastSeenAt] 覆盖，null 表示清空。
     */
    suspend fun applyServerPresence(
        externalId: String,
        hasLastSeenAt: Boolean,
        lastSeenAt: Long?,
    ) {}

    suspend fun deleteByExternalId(externalId: String)
}

interface SyncCursorStore {
    var cursor: String?
}

/**
 * 推送批次幂等键：同一批未变化记录总是得到同一个 key，
 * 网络失败重试时服务端可据此去重。
 *
 * 只纳入**实际会上报**的字段（openapi 0.3.3 三态语义）：
 * 未上报的可缺省字段不参与哈希，避免空值把「同内容重试」算成不同请求而 409。
 * group 的 absent 与显式空串是不同语义，因此空串会以 `group=` 的形式进入哈希。
 */
object IdempotencyKeys {
    fun forBatch(records: List<SyncRecord>): String {
        val canonical = records
            .sortedWith(compareBy({ it.platform }, { it.externalId }, { it.updatedAt }))
            .joinToString("\u001F") { record ->
                buildList {
                    add(record.platform)
                    add(record.externalId)
                    add(record.id)
                    add(record.lastRssi.toString())
                    add(record.lastSeenTimestamp.toString())
                    add(record.updatedAt.toString())
                    add((record.deletedAt ?: 0L).toString())
                    // alias：非空值或用户显式清空都要上报；扫描得到的空别名不参与哈希。
                    if (record.aliasDirty || record.name.isNotBlank()) add("alias=${record.name}")
                    record.advertisedName?.takeIf { it.isNotBlank() }?.let { add("adv=$it") }
                    record.deviceType?.let { add("type=$it") }
                    // group: null=absent（不参与哈希）；""=显式清除；其余=覆盖值。
                    record.group?.let { add("group=$it") }
                    // markedAt: dirty=false=absent；null=显式取消；非空=标记时间。
                    if (record.markedAtDirty) add("markedAt=${record.markedAt ?: "null"}")
                }.joinToString("\u001E")
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
    /** 410 `RESYNC_REQUIRED` 时回调一次，供上层记录状态并提示界面。 */
    private val onCursorExpired: () -> Unit = {},
) {

    /** 本次 [pullChanges] 是否走了「游标失效 → 从 0 全量重拉」分支。 */
    var lastPullWasResync: Boolean = false
        private set

    /**
     * 本次 [pullChanges] 拉到的家庭/房间结构变更（按服务端顺序）。
     * 由上层在拉取结束后交给家庭仓库落库；410 全量重拉的页面同样适用。
     */
    var lastStructuralChanges: List<StructuralChange> = emptyList()
        private set

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
        lastPullWasResync = false
        lastStructuralChanges = emptyList()
        val page = try {
            remote.pull(cursorStore.cursor)
        } catch (e: CursorExpiredException) {
            // 410 是终态分支：清空游标后只做一次 cursor=0 的全量重拉，不进指数退避。
            // pending 设备/读数不在这里删除，保持本地待上传状态。
            cursorStore.cursor = null
            lastPullWasResync = true
            onCursorExpired()
            remote.pull(CURSOR_START)
        }
        lastStructuralChanges = page.structuralChanges
        page.changes.forEach { change ->
            if (change.deletedAt != null) {
                // 服务端墓碑只有在本地没有「更新的 pending 版本」时才生效，
                // 避免全量重拉把尚未上传的本机修改丢掉。
                val localRecord = local.findActive(change.externalId)
                val keepPending = localRecord != null &&
                    localRecord.syncState == SyncState.PENDING &&
                    localRecord.updatedAt > change.updatedAt
                if (!keepPending) {
                    local.deleteByExternalId(change.externalId)
                }
            } else {
                val localRecord = local.findActive(change.externalId)
                val localUnsyncedWins = localRecord != null &&
                    localRecord.syncState == SyncState.PENDING &&
                    localRecord.updatedAt > change.updatedAt
                if (localUnsyncedWins) {
                    // 本地改动胜出，但服务端只读的最近上报时间仍然要落地。
                    local.applyServerPresence(
                        change.externalId,
                        change.hasLastSeenAt,
                        change.lastSeenAt,
                    )
                } else {
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

    companion object {
        /** 契约规定 cursor=0 表示从头全量拉取，且永不返回 410。 */
        const val CURSOR_START = "0"
    }
}
