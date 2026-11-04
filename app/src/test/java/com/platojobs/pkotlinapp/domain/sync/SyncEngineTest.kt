package com.platojobs.pkotlinapp.domain.sync

import com.platojobs.pkotlinapp.domain.model.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        override suspend fun pull(cursor: String?): SyncPullPage = pullPage
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
}
