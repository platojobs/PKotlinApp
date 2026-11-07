package com.platojobs.pkotlinapp.data.sync

import androidx.room.Room
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.sync.SyncRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `lastSeenAt` 的三态落地：属性缺失=老后端（保留本地值）与显式 JSON null=从未上报（清空）
 * 必须走两条不同的分支，不能被合并成同一个「保持」路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomSyncLocalStorePresenceTest {

    private lateinit var db: AppDatabase
    private lateinit var store: RoomSyncLocalStore

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomSyncLocalStore(db.deviceDao())
        db.deviceDao().insertOrUpdate(
            DeviceEntity(
                mac = MAC,
                name = "设备",
                lastRssi = -60,
                lastSeenTimestamp = NOW - 60_000,
                id = MAC,
                platform = "android",
                externalId = MAC,
                updatedAt = NOW - 30_000,
                deletedAt = null,
                syncState = "synced",
                lastSeenAt = EXISTING,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `replaceWithServer keeps local lastSeenAt when the field is absent`() = runBlocking {
        // 老后端不返回该字段：不能把已缓存的服务端心跳清空。
        store.replaceWithServer(record(hasLastSeenAt = false, lastSeenAt = null))

        assertEquals(EXISTING, localLastSeenAt())
    }

    @Test
    fun `replaceWithServer clears local lastSeenAt on explicit null`() = runBlocking {
        // 服务端明确「从未上报」：旧值已失效，必须清空并按 unknown 渲染。
        store.replaceWithServer(record(hasLastSeenAt = true, lastSeenAt = null))

        assertNull(localLastSeenAt())
    }

    @Test
    fun `replaceWithServer overwrites local lastSeenAt when present`() = runBlocking {
        store.replaceWithServer(record(hasLastSeenAt = true, lastSeenAt = FRESH))

        assertEquals(FRESH, localLastSeenAt())
    }

    @Test
    fun `applyServerPresence keeps local value when the field is absent`() = runBlocking {
        store.applyServerPresence(MAC, hasLastSeenAt = false, lastSeenAt = null)

        assertEquals(EXISTING, localLastSeenAt())
    }

    @Test
    fun `applyServerPresence clears local value on explicit null`() = runBlocking {
        store.applyServerPresence(MAC, hasLastSeenAt = true, lastSeenAt = null)

        assertNull(localLastSeenAt())
    }

    @Test
    fun `applyServerPresence writes the latest value without dirtying sync state`() = runBlocking {
        val before = requireNotNull(db.deviceDao().find(MAC))

        store.applyServerPresence(MAC, hasLastSeenAt = true, lastSeenAt = FRESH)

        val after = requireNotNull(db.deviceDao().find(MAC))
        assertEquals(FRESH, after.lastSeenAt)
        assertEquals(before.updatedAt, after.updatedAt)
        assertEquals("synced", after.syncState)
        assertEquals(0, db.deviceDao().pendingCount())
    }

    private suspend fun localLastSeenAt(): Long? =
        requireNotNull(db.deviceDao().findByExternalId(MAC)).lastSeenAt

    private fun record(hasLastSeenAt: Boolean, lastSeenAt: Long?) = SyncRecord(
        id = MAC,
        platform = "android",
        externalId = MAC,
        name = "服务端名称",
        lastRssi = -55,
        lastSeenTimestamp = NOW - 120_000,
        updatedAt = NOW,
        syncState = SyncState.SYNCED,
        lastSeenAt = lastSeenAt,
        hasLastSeenAt = hasLastSeenAt,
    )

    private companion object {
        const val MAC = "C8:47:8C:AA:BB:CC"
        const val NOW = 1_762_000_000_000L
        const val EXISTING = NOW - 300_000L
        const val FRESH = NOW - 10_000L
    }
}
