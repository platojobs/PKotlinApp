package com.platojobs.pkotlinapp.data.local

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * 首页聚合必须由 SQL 完成（GROUP BY / COUNT / 每设备最新），
 * 这里用真实 Room + SQLite 验证窗口切分与分组结果，防止退回内存全表扫描。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeAggregationTest {

    private lateinit var db: AppDatabase

    private val todayStart: Long = localMidnight(0)
    private val todayEnd: Long = localMidnight(1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            org.robolectric.RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `today window excludes yesterday and groups metrics and hours in sql`() = runBlocking {
        val withinToday = todayStart + 10 * HOUR + 5 * MINUTE
        insertDevice(MAC_A, lastSeen = withinToday, roomId = null)
        insertDevice(MAC_B, lastSeen = withinToday, roomId = null)
        insertReading("r1", MAC_A, "temperature", 21.0, withinToday)
        insertReading("r2", MAC_A, "temperature", 22.0, withinToday)
        insertReading("r3", MAC_A, "humidity", 45.0, withinToday)
        insertReading("r4", MAC_B, "temperature", 23.0, todayStart + 15 * HOUR)
        insertReading("r5", MAC_B, "temperature", 19.0, todayStart - HOUR)

        val dao = db.readingDao()
        assertEquals(4, dao.observeCountBetween(todayStart, todayEnd).first())

        val counts = dao.observeMetricCountsBetween(todayStart, todayEnd).first()
        assertEquals(listOf("temperature" to 3, "humidity" to 1), counts.map { it.metric to it.count })

        val hourly = dao.observeHourlyCountsBetween(todayStart, todayEnd).first()
        assertEquals(listOf(10, 15), hourly.map { it.hourIndex })
        assertEquals(listOf(3, 1), hourly.map { it.count })
    }

    @Test
    fun `device status counts and low battery latest come from sql`() = runBlocking {
        val now = todayStart + 12 * HOUR
        insertDevice(MAC_A, lastSeen = now, roomId = null)
        insertDevice(MAC_B, lastSeen = now - 2 * HOUR, roomId = 9L)

        insertReading("b1", MAC_A, "battery", 10.0, now)
        insertReading("b2", MAC_B, "battery", 80.0, now)
        insertReading("b3", MAC_A, "battery", 90.0, now - HOUR)

        val deviceDao = db.deviceDao()
        assertEquals(1, deviceDao.observeOnlineCount(now - 15 * MINUTE).first())
        assertEquals(1, deviceDao.observeOfflineCount(now - 15 * MINUTE).first())
        assertEquals(1, deviceDao.observeUnassignedRoomCount().first())

        val latestBattery = db.readingDao().observeLatestPerDeviceMetric("battery").first()
        assertEquals(2, latestBattery.size)
        assertEquals(10.0, latestBattery.first { it.deviceId == MAC_A }.value, 0.0)
        assertEquals(80.0, latestBattery.first { it.deviceId == MAC_B }.value, 0.0)
    }

    @Test
    fun `violations and latest activity are real records`() = runBlocking {
        val withinToday = todayStart + 9 * HOUR
        db.ruleEventDao().insert(
            RuleEventEntity(
                ruleType = "METRIC_THRESHOLD",
                mac = MAC_A,
                message = "温度越界",
                timestamp = withinToday,
                metric = "temperature",
                value = 31.0,
                thresholdValue = 30.0,
                unit = "°C",
            ),
        )
        db.ruleEventDao().insert(
            RuleEventEntity(
                ruleType = "LOW_RSSI",
                mac = MAC_B,
                message = "信号弱",
                timestamp = withinToday + HOUR,
            ),
        )

        assertEquals(2, db.ruleEventDao().observeCountBetween(todayStart, todayEnd).first())
        assertEquals(1, db.ruleEventDao().observeMetricViolationCountBetween(todayStart, todayEnd).first())
        val latest = db.ruleEventDao().observeLatestBetween(todayStart, todayEnd).first()
        assertEquals("LOW_RSSI", latest?.ruleType)

        // 昨天的事件不进入今天的窗口。
        assertNull(db.ruleEventDao().observeLatestBetween(todayStart - HOUR, todayStart).first())

        db.sceneRunDao().insert(
            SceneRunEntity(
                sceneId = 3,
                sceneName = "回家",
                timestamp = withinToday,
                triggerType = "LOW_RSSI",
                triggerValue = "rssi=-85",
                successCount = 1,
                failureCount = 0,
                detail = "NOTIFY: ok",
            ),
        )
        val run = db.sceneRunDao().observeLatest().first()
        assertEquals("回家", run?.sceneName)
    }

    private suspend fun insertReading(
        id: String,
        mac: String,
        metric: String,
        value: Double,
        recordedAt: Long,
    ) {
        db.readingDao().insert(
            ReadingEntity(
                id = id,
                deviceId = mac,
                metric = metric,
                value = value,
                unit = "",
                recordedAt = recordedAt,
                syncState = "sent",
            ),
        )
    }

    private suspend fun insertDevice(mac: String, lastSeen: Long, roomId: Long?) {
        db.deviceDao().insertOrUpdate(
            DeviceEntity(
                mac = mac,
                name = mac,
                lastRssi = -60,
                lastSeenTimestamp = lastSeen,
                id = mac,
                platform = "android",
                externalId = mac,
                updatedAt = lastSeen,
                deletedAt = null,
                syncState = "synced",
                roomId = roomId,
            ),
        )
    }

    private fun localMidnight(daysFromToday: Int): Long {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, daysFromToday)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private companion object {
        const val MAC_A = "AA:BB:CC:DD:EE:01"
        const val MAC_B = "AA:BB:CC:DD:EE:02"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
