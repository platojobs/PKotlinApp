package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.remote.SyncChangesResponse
import com.platojobs.pkotlinapp.domain.sync.StructuralChange
import com.platojobs.pkotlinapp.domain.sync.SyncCursorStore
import com.platojobs.pkotlinapp.domain.sync.SyncEngine
import com.platojobs.pkotlinapp.domain.sync.SyncLocalStore
import com.platojobs.pkotlinapp.domain.sync.SyncPullPage
import com.platojobs.pkotlinapp.domain.sync.SyncRecord
import com.platojobs.pkotlinapp.domain.sync.SyncRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定 JSON 用例：未知 entity 必须被跳过，游标仍按 nextCursor 前进。
 * 老客户端遇到不认识的 change_log 类型不能卡住整条同步链路。
 */
class SyncUnknownEntityTest {

    @Test
    fun `unknown entity is skipped while cursor still advances`() = kotlinx.coroutines.runBlocking {
        val adapter = ApiClient.moshi().adapter(SyncChangesResponse::class.java)
        val response = requireNotNull(adapter.fromJson(FIXED_JSON))

        val entries = response.changes.orEmpty().mapNotNull { it.toSyncChangeEntry() }
        val devices = entries.filterIsInstance<SyncChangeEntry.Device>().map { it.record }
        val structural = entries.filterIsInstance<SyncChangeEntry.Structure>().map { it.change }

        assertEquals(1, devices.size)
        assertEquals(3, structural.size)
        assertTrue(structural[0] is StructuralChange.Household)
        assertTrue(structural[1] is StructuralChange.Room)
        assertTrue((structural[2] as StructuralChange.Room).deleted)

        val cursor = InMemoryCursor()
        val engine = SyncEngine(
            local = NoopLocalStore(),
            remote = StaticRemote(
                SyncPullPage(
                    changes = devices,
                    nextCursor = response.nextCursor?.toString(),
                    structuralChanges = structural,
                ),
            ),
            cursorStore = cursor,
        )

        val pulled = engine.pullChanges()

        assertEquals(1, pulled)
        assertEquals("9", cursor.cursor)
        assertEquals(3, engine.lastStructuralChanges.size)
        // 未知 entity 不会进入任何业务集合。
        assertTrue(engine.lastStructuralChanges.none { it is StructuralChange.Room && it.roomRemoteId == "x-1" })
    }

    private class StaticRemote(private val page: SyncPullPage) : SyncRemote {
        override suspend fun push(records: List<SyncRecord>, idempotencyKey: String) =
            com.platojobs.pkotlinapp.domain.sync.SyncPushAck(accepted = emptyList())

        override suspend fun pull(cursor: String?) = page
    }

    private class InMemoryCursor : SyncCursorStore {
        override var cursor: String? = "3"
    }

    private class NoopLocalStore : SyncLocalStore {
        override suspend fun pending(limit: Int): List<SyncRecord> = emptyList()

        override suspend fun findActive(externalId: String): SyncRecord? = null

        override suspend fun markSynced(externalIds: List<String>) = Unit

        override suspend fun replaceWithServer(record: SyncRecord) = Unit

        override suspend fun deleteByExternalId(externalId: String) = Unit
    }

    private companion object {
        /**
         * 顺序：设备 -> household -> room -> 未知 entity（`automation_rule`）。
         * 未知 entity 位于中间，确保它后面的变更仍被处理、游标仍推进。
         */
        const val FIXED_JSON = """
            {
              "changes": [
                {
                  "seq": 4,
                  "entity": "user_device",
                  "entityId": "AA:BB:CC:00:00:01",
                  "op": "upsert",
                  "payload": {
                    "id": "d-1",
                    "platform": "android",
                    "externalId": "AA:BB:CC:00:00:01",
                    "alias": "温湿度计",
                    "updatedAt": "2026-11-01T00:00:00Z",
                    "deleted": false
                  },
                  "serverTs": "2026-11-01T00:00:00Z"
                },
                {
                  "seq": 5,
                  "entity": "household",
                  "entityId": "h-1",
                  "op": "upsert",
                  "payload": {
                    "id": "h-1",
                    "name": "全家",
                    "ownerUserId": "u-1",
                    "role": "member",
                    "memberCount": 3,
                    "deleted": false
                  },
                  "serverTs": "2026-11-01T00:00:01Z"
                },
                {
                  "seq": 6,
                  "entity": "room",
                  "entityId": "r-1",
                  "op": "upsert",
                  "payload": {
                    "id": "r-1",
                    "householdId": "h-1",
                    "name": "客厅",
                    "sortOrder": 0,
                    "deleted": false
                  },
                  "serverTs": "2026-11-01T00:00:02Z"
                },
                {
                  "seq": 7,
                  "entity": "automation_rule",
                  "entityId": "x-1",
                  "op": "upsert",
                  "payload": {
                    "id": "x-1",
                    "name": "夜间小夜灯",
                    "enabled": true,
                    "unknownField": [1, 2, 3]
                  },
                  "serverTs": "2026-11-01T00:00:03Z"
                },
                {
                  "seq": 8,
                  "entity": "room",
                  "entityId": "r-2",
                  "op": "delete",
                  "payload": {
                    "id": "r-2",
                    "householdId": "h-1",
                    "name": "卧室",
                    "sortOrder": 1,
                    "deleted": true
                  },
                  "serverTs": "2026-11-01T00:00:04Z"
                }
              ],
              "nextCursor": 9,
              "hasMore": false
            }
        """
    }
}
