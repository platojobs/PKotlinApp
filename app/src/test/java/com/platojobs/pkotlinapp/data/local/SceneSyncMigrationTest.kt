package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * 真实 SQLite 执行 v19 -> v20（场景接入服务端模型）：
 * - 旧 scenes / scene_actions / rules 全部保留，不因新增同步列被清空；
 * - 旧场景默认 syncState=pending、cooldown=300、五字段时间戳回填 createdAt；
 * - remoteId 唯一索引允许多个 NULL（本机未上传场景不会互相冲突）。
 */
class SceneSyncMigrationTest {

    private val v19Scenes = """
        CREATE TABLE IF NOT EXISTS scenes (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL DEFAULT 1
        )
    """.trimIndent()

    private val v19SceneActions = """
        CREATE TABLE IF NOT EXISTS scene_actions (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `sceneId` INTEGER NOT NULL,
            `position` INTEGER NOT NULL,
            `type` TEXT NOT NULL,
            `deviceMac` TEXT,
            `muteMinutes` INTEGER
        )
    """.trimIndent()

    private val v19Rules = """
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
            `consecutive` INTEGER NOT NULL,
            `sceneId` INTEGER,
            `intervalMinutes` INTEGER
        )
    """.trimIndent()

    @Test
    fun `v19 to v20 keeps scenes actions and rules`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(v19Scenes)
                statement.execute(v19SceneActions)
                statement.execute(v19Rules)
                statement.executeUpdate(
                    "INSERT INTO scenes (name, createdAt, enabled) VALUES ('回家', 1000, 1)",
                )
                statement.executeUpdate(
                    """
                    INSERT INTO scene_actions (sceneId, position, type, deviceMac, muteMinutes)
                    VALUES (1, 0, 'CONNECT_DEVICE', 'AA:BB:CC:DD:EE:01', NULL)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO rules
                        (type, threshold, enabled, createdAt, lastTriggeredTimestamp,
                         metric, comparator, thresholdValue, consecutive, sceneId, intervalMinutes)
                    VALUES ('LOW_RSSI', -75, 1, 1000, 0, NULL, 'LESS', -75.0, 1, 1, NULL)
                    """.trimIndent(),
                )

                SceneSyncMigrationSql.V19_TO_V20.forEach { sql -> statement.execute(sql) }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT name, enabled, cooldownSeconds, remoteId, version, syncState,
                           deletedAt, serverUpdatedAt, nameUpdatedAt, enabledUpdatedAt,
                           cooldownUpdatedAt, triggerUpdatedAt, actionsUpdatedAt,
                           triggerJson, actionsJson, syncError
                    FROM scenes
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("回家", cursor.getString("name"))
                    assertEquals(1, cursor.getInt("enabled"))
                    assertEquals(300, cursor.getInt("cooldownSeconds"))
                    assertNull(cursor.getObject("remoteId"))
                    assertEquals(0L, cursor.getLong("version"))
                    assertEquals("pending", cursor.getString("syncState"))
                    assertNull(cursor.getObject("deletedAt"))
                    assertEquals(0L, cursor.getLong("serverUpdatedAt"))
                    assertEquals(1000L, cursor.getLong("nameUpdatedAt"))
                    assertEquals(1000L, cursor.getLong("enabledUpdatedAt"))
                    assertEquals(1000L, cursor.getLong("cooldownUpdatedAt"))
                    assertEquals(1000L, cursor.getLong("triggerUpdatedAt"))
                    assertEquals(1000L, cursor.getLong("actionsUpdatedAt"))
                    assertNull(cursor.getString("triggerJson"))
                    assertNull(cursor.getString("actionsJson"))
                    assertNull(cursor.getString("syncError"))
                }

                statement.executeQuery(
                    "SELECT type, deviceMac, title, body, message, level, mark FROM scene_actions",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("CONNECT_DEVICE", cursor.getString("type"))
                    assertEquals("AA:BB:CC:DD:EE:01", cursor.getString("deviceMac"))
                    assertNull(cursor.getString("title"))
                    assertNull(cursor.getString("message"))
                    assertNull(cursor.getString("mark"))
                }

                statement.executeQuery(
                    "SELECT type, threshold, deviceMac, offlineSeconds, timeOfDay, timezone, daysOfWeek FROM rules",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("LOW_RSSI", cursor.getString("type"))
                    assertEquals(-75, cursor.getInt("threshold"))
                    assertNull(cursor.getString("deviceMac"))
                    assertEquals(300, cursor.getInt("offlineSeconds"))
                    assertNull(cursor.getString("timeOfDay"))
                    assertNull(cursor.getString("timezone"))
                    assertNull(cursor.getString("daysOfWeek"))
                }

                // 唯一索引存在，但允许第二条本机场景（remoteId NULL）。
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_scenes_remoteId'",
                ).use { cursor -> assertTrue(cursor.next()) }
                statement.executeUpdate(
                    "INSERT INTO scenes (name, createdAt, enabled, syncState) VALUES ('离家', 2000, 1, 'pending')",
                )
                statement.executeQuery("SELECT COUNT(*) FROM scenes").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(2, cursor.getInt(1))
                }
            }
        }
    }
}
