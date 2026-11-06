package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * 真实 SQLite 执行 v15 -> v16（场景引擎）迁移：
 * - rules 增加 sceneId / intervalMinutes，旧规则内容不变；
 * - scenes 增加 enabled 且旧场景默认启用；
 * - devices 增加 markedAt；
 * - 新建 scene_runs 与 timestamp 索引，可正常写入执行记录。
 */
class SceneEngineMigrationTest {

    private val v15Rules = """
        CREATE TABLE IF NOT EXISTS rules (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `type` TEXT NOT NULL,
            `threshold` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `lastTriggeredTimestamp` INTEGER NOT NULL,
            `metric` TEXT,
            `comparator` TEXT NOT NULL,
            `thresholdValue` REAL NOT NULL,
            `consecutive` INTEGER NOT NULL
        )
    """.trimIndent()

    private val v15Scenes = """
        CREATE TABLE IF NOT EXISTS scenes (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL
        )
    """.trimIndent()

    private val v15Devices = """
        CREATE TABLE IF NOT EXISTS devices (
            `mac` TEXT PRIMARY KEY NOT NULL
        )
    """.trimIndent()

    @Test
    fun `v15 to v16 keeps old data and creates scene runs`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(v15Rules)
                statement.execute(v15Scenes)
                statement.execute(v15Devices)
                statement.executeUpdate(
                    """
                    INSERT INTO rules
                        (type, threshold, enabled, createdAt, lastTriggeredTimestamp,
                         metric, comparator, thresholdValue, consecutive)
                    VALUES ('LOW_RSSI', -70, 1, 100, 0, NULL, 'LESS', -70.0, 1)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO scenes (name, createdAt) VALUES ('回家', 200)",
                )
                statement.executeUpdate(
                    "INSERT INTO devices (mac) VALUES ('AA:BB:CC:DD:EE:01')",
                )

                SceneEngineMigrationSql.V15_TO_V16.forEach { sql -> statement.execute(sql) }
            }

            connection.createStatement().use { statement ->
                // 旧规则保留，新增列可空（尚未绑定场景）。
                statement.executeQuery("SELECT type, threshold, enabled, sceneId, intervalMinutes FROM rules")
                    .use { cursor ->
                        assertTrue(cursor.next())
                        assertEquals("LOW_RSSI", cursor.getString("type"))
                        assertEquals(-70, cursor.getInt("threshold"))
                        assertEquals(1, cursor.getInt("enabled"))
                        assertNull(cursor.getObject("sceneId"))
                        assertNull(cursor.getObject("intervalMinutes"))
                    }

                // 旧场景保留且默认启用。
                statement.executeQuery("SELECT name, enabled FROM scenes").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("回家", cursor.getString("name"))
                    assertEquals(1, cursor.getInt("enabled"))
                }

                // 旧设备保留，标记时间为空。
                statement.executeQuery("SELECT mac, markedAt FROM devices").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("AA:BB:CC:DD:EE:01", cursor.getString("mac"))
                    assertNull(cursor.getObject("markedAt"))
                }

                // 新表可写入真实执行记录。
                statement.executeUpdate(
                    """
                    INSERT INTO scene_runs
                        (sceneId, sceneName, timestamp, triggerType, triggerValue,
                         successCount, failureCount, failureReason, detail)
                    VALUES (1, '回家', 300, 'LOW_RSSI', 'rssi=-85', 1, 0, NULL, 'NOTIFY: ok')
                    """.trimIndent(),
                )
                statement.executeQuery("SELECT COUNT(*) FROM scene_runs").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(1, cursor.getInt(1))
                }
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_scene_runs_timestamp'",
                ).use { cursor ->
                    assertTrue(cursor.next())
                }
            }
        }
    }
}
