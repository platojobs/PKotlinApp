package com.platojobs.pkotlinapp.data.local

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 设备标记在账号维度上的边界：
 * - 本账号设备（shared = 0）标记后进入 pending，随 /sync/push 上报；
 * - 其他家庭成员共享的设备（shared = 1）只读，标记动作不落到本机，
 *   避免把别人的设备写进自己的同步队列；
 * - 删除账号会清空全部业务表，下一个账号不会继承上一个账号的本机标记。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceMarkSyncIsolationTest {

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

    @Test
    fun `marking own device queues sync and leaves shared device untouched`() = runBlocking {
        val dao = db.deviceDao()
        dao.insertOrUpdate(device(OWN_MAC, shared = false))
        dao.insertOrUpdate(device(SHARED_MAC, shared = true))

        dao.markDevice(OWN_MAC, MARK_AT)

        val own = requireNotNull(dao.find(OWN_MAC))
        assertEquals(MARK_AT, own.markedAt)
        assertTrue(own.markedAtDirty)
        assertEquals("pending", own.syncState)
        assertEquals(MARK_AT, own.updatedAt)

        // 共享设备属于其他账号，标记动作不能写进本机同步队列。
        val shared = requireNotNull(dao.find(SHARED_MAC))
        assertNull(shared.markedAt)
        assertFalse(shared.markedAtDirty)
        assertEquals("synced", shared.syncState)
    }

    @Test
    fun `clearing mark reports explicit null and ack clears the dirty flag`() = runBlocking {
        val dao = db.deviceDao()
        dao.insertOrUpdate(device(OWN_MAC, shared = false).copy(markedAt = MARK_AT, syncState = "synced"))
        val externalId = requireNotNull(dao.find(OWN_MAC)).externalId

        dao.clearDeviceMark(OWN_MAC, MARK_AT + 1_000L)
        val cleared = requireNotNull(dao.find(OWN_MAC))
        assertNull(cleared.markedAt)
        assertTrue(cleared.markedAtDirty)
        assertEquals("pending", cleared.syncState)
        assertEquals(MARK_AT + 1_000L, cleared.updatedAt)

        dao.markSyncedByExternalIds(listOf(externalId))
        val synced = requireNotNull(dao.find(OWN_MAC))
        assertEquals("synced", synced.syncState)
        assertFalse(synced.markedAtDirty)
        assertNull(synced.markedAt)
    }

    @Test
    fun `account deletion wipe does not leak marks to the next account`() = runBlocking {
        val dao = db.deviceDao()
        dao.insertOrUpdate(device(OWN_MAC, shared = false))
        dao.markDevice(OWN_MAC, MARK_AT)

        // 删除账号走 database.clearAllTables()，与 AccountService.deleteAccount 一致。
        db.clearAllTables()

        assertNull(dao.findAny(OWN_MAC))
        assertEquals(0, dao.pendingCount())
        assertTrue(dao.getAllDevices().isEmpty())
    }

    private fun device(mac: String, shared: Boolean) = DeviceEntity(
        mac = mac,
        name = "设备 $mac",
        lastRssi = -60,
        lastSeenTimestamp = MARK_AT,
        id = mac,
        platform = "android",
        externalId = mac,
        updatedAt = MARK_AT,
        deletedAt = null,
        syncState = "synced",
        shared = shared,
    )

    private companion object {
        const val OWN_MAC = "C8:47:8C:AA:BB:CC"
        const val SHARED_MAC = "C8:47:8C:DD:EE:FF"
        const val MARK_AT = 1_762_000_000_000L
    }
}
