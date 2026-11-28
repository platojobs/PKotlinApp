package com.platojobs.pkotlinapp.data.sync

import androidx.room.Room
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.domain.repository.DeviceUnbindRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 「待解绑」队列：与 iOS 对齐的离线策略。
 *
 * 离线点删除 → 本地行保留并标记 pendingDelete（不进入 push）；
 * 恢复网络同步一次 → 服务端 204 → 物理删除本地行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PendingUnbindProcessorTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class FakeUnbind(var offline: Boolean) : DeviceUnbindRepository {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun deleteOwnedDevice(platform: String, externalId: String) {
            calls += platform to externalId
            if (offline) throw IOException("network unavailable")
        }
    }

    @Test
    fun `offline delete keeps row as pending then online sync removes it`() = runBlocking {
        val dao = db.deviceDao()
        dao.insertOrUpdate(device())
        val unbind = FakeUnbind(offline = true)
        val processor = PendingUnbindProcessor(dao, unbind)

        // 1) 离线点删除：只落本地待解绑，行仍在。
        dao.markPendingDelete(MAC)
        val pending = requireNotNull(dao.find(MAC))
        assertTrue(pending.pendingDelete)
        assertEquals("synced", pending.syncState)
        // 待解绑行不能进入 /sync/push。
        assertTrue(dao.pendingDevices(50).isEmpty())

        // 2) 离线同步一次：失败，行与标记都保留，等联网重试。
        var failed = false
        try {
            processor.process()
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        val stillPending = requireNotNull(dao.find(MAC))
        assertTrue(stillPending.pendingDelete)
        assertEquals(listOf("android" to MAC), unbind.calls)

        // 3) 恢复网络同步一次：服务端 204 → 物理删除本地行。
        unbind.offline = false
        val removed = processor.process()

        assertEquals(1, removed)
        assertNull(dao.find(MAC))
        assertTrue(dao.pendingDeletes().isEmpty())
    }

    private fun device() = DeviceEntity(
        mac = MAC,
        name = "待解绑设备",
        lastRssi = -60,
        lastSeenTimestamp = AT,
        id = MAC,
        platform = "android",
        externalId = MAC,
        updatedAt = AT,
        deletedAt = null,
        syncState = "synced",
    )

    private companion object {
        const val MAC = "C8:47:8C:AA:BB:CC"
        const val AT = 1_762_000_000_000L
    }
}
