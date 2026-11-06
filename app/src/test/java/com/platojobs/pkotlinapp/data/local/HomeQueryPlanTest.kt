package com.platojobs.pkotlinapp.data.local

import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 首页聚合 SQL 的查询计划：所有窗口统计必须走索引，不能出现无索引的表扫描。
 * 与 HomeAggregationTest 互补：那边验证结果正确，这里验证执行路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeQueryPlanTest {

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
    fun `home aggregates use indexes`() {
        assertUsesIndex(
            table = "readings",
            sql = "SELECT COUNT(*) FROM readings WHERE recordedAt >= 0 AND recordedAt < 1",
        )
        assertUsesIndex(
            table = "readings",
            sql = "SELECT metric, COUNT(*) FROM readings " +
                "WHERE recordedAt >= 0 AND recordedAt < 1 GROUP BY metric",
        )
        assertUsesIndex(
            table = "readings",
            sql = "SELECT CAST((recordedAt - 0) / 3600000 AS INTEGER) AS hourIndex, COUNT(*) " +
                "FROM readings WHERE recordedAt >= 0 AND recordedAt < 1 GROUP BY hourIndex",
        )
        assertUsesIndex(
            table = "readings",
            sql = "SELECT deviceId, MAX(recordedAt) AS maxAt FROM readings GROUP BY deviceId",
        )
        assertUsesIndex(
            table = "readings",
            sql = "SELECT deviceId, MAX(recordedAt) AS maxAt FROM readings " +
                "WHERE metric = 'battery' GROUP BY deviceId",
        )
        assertUsesIndex(
            table = "rule_events",
            sql = "SELECT COUNT(*) FROM rule_events WHERE timestamp >= 0 AND timestamp < 1",
        )
        assertUsesIndex(
            table = "devices",
            sql = "SELECT COUNT(*) FROM devices WHERE deletedAt IS NULL AND lastSeenTimestamp >= 0",
        )
        assertUsesIndex(
            table = "devices",
            sql = "SELECT COUNT(*) FROM devices WHERE deletedAt IS NULL AND lastSeenTimestamp < 0",
        )
    }

    @Test
    fun `recent events use timestamp index`() {
        val plan = queryPlan("SELECT * FROM rule_events ORDER BY timestamp DESC LIMIT 10")
        assertTrue("计划应使用 timestamp 索引：$plan", plan.any { it.contains("index_rule_events_timestamp") })
        assertFalse("不应回表全扫描：$plan", plan.any { it.startsWith("SCAN rule_events") })
    }

    private fun assertUsesIndex(table: String, sql: String) {
        val plan = queryPlan(sql)
        assertFalse(
            "聚合查询出现无索引全表扫描：\n$sql\n$plan",
            plan.any { it.contains("SCAN $table") && !it.contains("USING") },
        )
        assertTrue(
            "聚合查询未走索引：\n$sql\n$plan",
            plan.any { it.contains("USING") && it.contains("INDEX") },
        )
    }

    private fun queryPlan(sql: String): List<String> {
        val details = mutableListOf<String>()
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", emptyArray()).use { cursor ->
            val detailIndex = cursor.getColumnIndex("detail")
            while (cursor.moveToNext()) {
                details += cursor.getString(detailIndex)
            }
        }
        return details
    }
}
