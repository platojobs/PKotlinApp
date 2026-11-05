package com.platojobs.pkotlinapp.domain.sync

import com.platojobs.pkotlinapp.domain.model.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncEngineTest {

    private class FakeLocalStore(
        initial: List<SyncRecord> = emptyList(),
    ) : SyncLocalStore {
        val records = LinkedHashMap<String, SyncRecord>()

        init {
            initial.forEach { records[it.externalId] = it }
        }

        override suspend fun pending(limit: Int): List<SyncRecord> =
            records.values.filter { it.syncState == SyncState.PENDING }.take(limit)

        override suspend fun findActive(externalId: String): SyncRecord? =
            records[externalId]?.takeIf { it.deletedAt == null }

        override suspend fun markSynced(externalIds: List<String>) {
            externalIds.forEach { externalId ->
                records[externalId]?.let { records[externalId] = it.copy(syncState = SyncState.SYNCED) }
            }
        }

        override suspend fun replaceWithServer(record: SyncRecord) {
            records[record.externalId] = record.copy(syncState = SyncState.SYNCED)
        }

        override suspend fun deleteByExternalId(externalId: String) {
            records.remove(externalId)
        }
    }

    private class FakeRemote : SyncRemote {
        val ackCache = mutableMapOf<String, SyncPushAck>()
        val pushedRecords = mutableListOf<SyncRecord>()
        var applyCount = 0
        var throwAfterApplyOnce = false
        var conflicts: List<SyncConflict> = emptyList()
        var pullPage: SyncPullPage = SyncPullPage(emptyList(), null)
        val pullErrors = ArrayDeque<Exception>()
        val pullCursors = mutableListOf<String?>()

        override suspend fun push(records: List<SyncRecord>, idempotencyKey: String): SyncPushAck {
            ackCache[idempotencyKey]?.let { return it }
            val ack = SyncPushAck(
                accepted = records.map { it.externalId },
                conflicts = conflicts,
            )
            ackCache[idempotencyKey] = ack
            pushedRecords += records
            applyCount += records.size
            if (throwAfterApplyOnce) {
                throwAfterApplyOnce = false
                throw IOException("simulated lost response")
            }
            return ack
        }

        override suspend fun pull(cursor: String?): SyncPullPage {
            pullCursors += cursor
            pullErrors.removeFirstOrNull()?.let { throw it }
            return pullPage
        }
    }

    private class FakeCursorStore(override var cursor: String? = null) : SyncCursorStore

    @Test
    fun `retry uses stable idempotency key and does not duplicate apply`() = runBlocking {
        val local = FakeLocalStore(
            listOf(
                SyncRecord(
                    id = "client-1",
                    platform = "android",
                    externalId = "AA:BB:CC:DD:EE:FF",
                    name = "Tracker",
                    lastRssi = -50,
                    lastSeenTimestamp = 100,
                    updatedAt = 100,
                    syncState = SyncState.PENDING,
                ),
            ),
        )
        val remote = FakeRemote().apply { throwAfterApplyOnce = true }
        val engine = SyncEngine(local, remote, FakeCursorStore())

        try {
            engine.pushPending()
        } catch (_: IOException) {
            // 第一次响应丢失，本地仍保持 pending
        }
        assertEquals(SyncState.PENDING, local.records.values.single().syncState)

        val second = engine.pushPending()
        assertEquals(1, second.pushed)
        assertEquals(1, remote.applyCount)
        assertEquals(1, remote.pushedRecords.size)
        assertEquals(SyncState.SYNCED, local.records.values.single().syncState)
    }

    @Test
    fun `conflict loses to server and is reported not silently overwritten`() = runBlocking {
        val local = FakeLocalStore(
            listOf(
                SyncRecord(
                    id = "client-1",
                    platform = "android",
                    externalId = "AA:BB:CC:DD:EE:FF",
                    name = "本地名称",
                    lastRssi = -60,
                    lastSeenTimestamp = 100,
                    updatedAt = 100,
                    syncState = SyncState.PENDING,
                ),
            ),
        )
        val serverRecord = SyncRecord(
            id = "client-1",
            platform = "android",
            externalId = "AA:BB:CC:DD:EE:FF",
            name = "服务端名称",
            lastRssi = -55,
            lastSeenTimestamp = 200,
            updatedAt = 200,
        )
        val remote = FakeRemote().apply {
            conflicts = listOf(
                SyncConflict(
                    externalId = serverRecord.externalId,
                    reason = "server_newer",
                    serverRecord = serverRecord,
                ),
            )
        }
        val engine = SyncEngine(local, remote, FakeCursorStore())

        val outcome = engine.pushPending()

        assertEquals(1, outcome.conflicts.size)
        assertEquals("server_newer", outcome.conflicts.single().reason)
        assertEquals("服务端名称", local.records.getValue(serverRecord.externalId).name)
        assertEquals(SyncState.SYNCED, local.records.getValue(serverRecord.externalId).syncState)
    }

    @Test
    fun `tombstone is pushed and purged after successful sync`() = runBlocking {
        val tombstone = SyncRecord(
            id = "client-1",
            platform = "android",
            externalId = "AA:BB:CC:DD:EE:FF",
            name = "Tracker",
            lastRssi = -50,
            lastSeenTimestamp = 100,
            updatedAt = 120,
            deletedAt = 120,
            syncState = SyncState.PENDING,
        )
        val local = FakeLocalStore(listOf(tombstone))
        val remote = FakeRemote()
        val engine = SyncEngine(local, remote, FakeCursorStore())

        val outcome = engine.pushPending()

        assertEquals(1, outcome.pushed)
        assertTrue(remote.pushedRecords.single().deletedAt != null)
        assertNull(local.records[tombstone.externalId])
    }

    @Test
    fun `unresolved conflict without server record stays pending`() = runBlocking {
        val local = FakeLocalStore(
            listOf(
                SyncRecord(
                    id = "client-1",
                    platform = "android",
                    externalId = "AA:BB:CC:DD:EE:FF",
                    name = "Tracker",
                    lastRssi = -50,
                    lastSeenTimestamp = 100,
                    updatedAt = 100,
                    syncState = SyncState.PENDING,
                ),
            ),
        )
        val remote = FakeRemote().apply {
            conflicts = listOf(
                SyncConflict(
                    externalId = "AA:BB:CC:DD:EE:FF",
                    reason = "unresolved",
                    serverRecord = null,
                ),
            )
        }
        val engine = SyncEngine(local, remote, FakeCursorStore())

        val outcome = engine.pushPending()

        assertEquals(1, outcome.conflicts.size)
        assertEquals(0, outcome.pushed)
        assertEquals(
            SyncState.PENDING,
            local.records.getValue("AA:BB:CC:DD:EE:FF").syncState,
        )
    }

    @Test
    fun `pull keeps newer local pending data and applies server tombstones`() = runBlocking {
        val local = FakeLocalStore(
            listOf(
                SyncRecord(
                    id = "client-1",
                    platform = "android",
                    externalId = "AA:BB:CC:DD:EE:11",
                    name = "本地较新",
                    lastRssi = -40,
                    lastSeenTimestamp = 200,
                    updatedAt = 200,
                    syncState = SyncState.PENDING,
                ),
                SyncRecord(
                    id = "client-2",
                    platform = "android",
                    externalId = "AA:BB:CC:DD:EE:22",
                    name = "待删除",
                    lastRssi = -70,
                    lastSeenTimestamp = 100,
                    updatedAt = 100,
                ),
            ),
        )
        val remote = FakeRemote().apply {
            pullPage = SyncPullPage(
                changes = listOf(
                    SyncRecord(
                        id = "client-1",
                        platform = "android",
                        externalId = "AA:BB:CC:DD:EE:11",
                        name = "服务端较旧",
                        lastRssi = -50,
                        lastSeenTimestamp = 150,
                        updatedAt = 150,
                    ),
                    SyncRecord(
                        id = "client-2",
                        platform = "android",
                        externalId = "AA:BB:CC:DD:EE:22",
                        name = "待删除",
                        lastRssi = 0,
                        lastSeenTimestamp = 100,
                        updatedAt = 300,
                        deletedAt = 300,
                    ),
                ),
                nextCursor = "cursor-2",
            )
        }
        val cursorStore = FakeCursorStore("cursor-1")
        val engine = SyncEngine(local, remote, cursorStore)

        val pulled = engine.pullChanges()

        assertEquals(2, pulled)
        assertEquals("本地较新", local.records.getValue("AA:BB:CC:DD:EE:11").name)
        assertFalse(local.records.containsKey("AA:BB:CC:DD:EE:22"))
        assertEquals("cursor-2", cursorStore.cursor)
    }

    @Test
    fun `idempotency key is stable and only hashes reported fields`() {
        val base = SyncRecord(
            id = "client-1",
            platform = "android",
            externalId = "AA:BB:CC:DD:EE:11",
            name = "",
            advertisedName = "PJ-Sensor",
            lastRssi = -55,
            lastSeenTimestamp = 100,
            updatedAt = 100,
            // 未上报的可缺省字段：不应参与哈希。
            deviceType = null,
            group = null,
        )

        // 同内容重试必须得到同一个 key。
        assertEquals(IdempotencyKeys.forBatch(listOf(base)), IdempotencyKeys.forBatch(listOf(base.copy())))

        // 未上报字段的空值不能因为「碰巧为空」而改变 key。
        val sameWithBlankOptional = base.copy(advertisedName = "PJ-Sensor")
        assertEquals(IdempotencyKeys.forBatch(listOf(base)), IdempotencyKeys.forBatch(listOf(sameWithBlankOptional)))

        // group 的 absent 与显式空串语义不同，key 必须不同。
        val cleared = base.copy(group = "")
        assertNotEquals(IdempotencyKeys.forBatch(listOf(base)), IdempotencyKeys.forBatch(listOf(cleared)))

        // 上报别名会改变内容，key 必须不同。
        val aliasEdited = base.copy(name = "客厅开发板")
        assertNotEquals(IdempotencyKeys.forBatch(listOf(base)), IdempotencyKeys.forBatch(listOf(aliasEdited)))

        // 显式清空别名（空串 + 脏标记）与「未上报别名」是不同请求。
        val aliasCleared = base.copy(name = "", aliasDirty = true)
        assertNotEquals(
            IdempotencyKeys.forBatch(listOf(base)),
            IdempotencyKeys.forBatch(listOf(aliasCleared)),
        )
        // 脏标记相同、内容相同则重试 key 稳定。
        assertEquals(
            IdempotencyKeys.forBatch(listOf(aliasCleared)),
            IdempotencyKeys.forBatch(listOf(aliasCleared.copy())),
        )
    }

    @Test
    fun `410 clears cursor refetches from zero and keeps pending`() = runBlocking {
        val pending = SyncRecord(
            id = "client-1",
            platform = "android",
            externalId = "AA:BB:CC:DD:EE:11",
            name = "本机待上传",
            lastRssi = -50,
            lastSeenTimestamp = 200,
            updatedAt = 200,
            syncState = SyncState.PENDING,
        )
        val local = FakeLocalStore(listOf(pending))
        val remote = FakeRemote().apply {
            pullErrors += CursorExpiredException()
            pullPage = SyncPullPage(
                changes = listOf(
                    pending.copy(
                        name = "服务端旧名",
                        updatedAt = 100,
                        syncState = SyncState.SYNCED,
                    ),
                ),
                nextCursor = "7",
            )
        }
        val cursorStore = FakeCursorStore("12")
        var expiredCallbacks = 0
        val engine = SyncEngine(
            local = local,
            remote = remote,
            cursorStore = cursorStore,
            onCursorExpired = { expiredCallbacks += 1 },
        )

        engine.pullChanges()

        assertEquals(1, expiredCallbacks)
        assertTrue(engine.lastPullWasResync)
        // 第一次用旧游标，410 后立即用 cursor=0 全量重拉。
        assertEquals(listOf<String?>("12", SyncEngine.CURSOR_START), remote.pullCursors)
        assertEquals("7", cursorStore.cursor)
        // 本地 pending 不被服务端较旧数据覆盖，也不被删除。
        assertEquals("本机待上传", local.records.getValue(pending.externalId).name)
        assertEquals(SyncState.PENDING, local.records.getValue(pending.externalId).syncState)
    }

    @Test
    fun `second 410 is not retried again and surfaces once`() = runBlocking {
        // 连续两次 410：旧游标一次、cursor=0 一次；只处理一次，不再做第三次请求。
        val remote = FakeRemote().apply {
            pullErrors += CursorExpiredException()
            pullErrors += CursorExpiredException()
        }
        val cursorStore = FakeCursorStore("12")
        var expiredCallbacks = 0
        val engine = SyncEngine(
            local = FakeLocalStore(),
            remote = remote,
            cursorStore = cursorStore,
            onCursorExpired = { expiredCallbacks += 1 },
        )
        runCatching { engine.pullChanges() }

        assertEquals(listOf<String?>("12", SyncEngine.CURSOR_START), remote.pullCursors)
        assertEquals(1, expiredCallbacks)
    }

    @Test
    fun `server tombstone does not delete newer pending local record`() = runBlocking {
        val pending = SyncRecord(
            id = "client-1",
            platform = "android",
            externalId = "AA:BB:CC:DD:EE:22",
            name = "本机修改",
            lastRssi = -40,
            lastSeenTimestamp = 300,
            updatedAt = 300,
            syncState = SyncState.PENDING,
        )
        val local = FakeLocalStore(listOf(pending))
        val remote = FakeRemote().apply {
            pullPage = SyncPullPage(
                changes = listOf(
                    pending.copy(
                        name = "服务端墓碑",
                        updatedAt = 100,
                        deletedAt = 100,
                        syncState = SyncState.SYNCED,
                    ),
                ),
                nextCursor = "3",
            )
        }
        val engine = SyncEngine(local, remote, FakeCursorStore())

        engine.pullChanges()

        assertTrue(local.records.containsKey(pending.externalId))
        assertEquals(SyncState.PENDING, local.records.getValue(pending.externalId).syncState)
    }
}
