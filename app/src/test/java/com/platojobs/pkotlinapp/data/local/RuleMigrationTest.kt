package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

/**
 * 用真实 SQLite 执行 v9 → v10 迁移：
 * - 新列全部存在；
 * - 旧 RSSI 规则的 type/threshold 不变（升级后仍按原语义触发）；
 * - 指标字段回填为 `< 阈值`，其它规则保持默认值；
 * - 旧命中记录与新增证据列共存。
 */
class RuleMigrationTest {

    private val v9RulesCreateSql = """
        CREATE TABLE IF NOT EXISTS rules (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `type` TEXT NOT NULL,
            `threshold` INTEGER NOT NULL,
            `enabled` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `lastTriggeredTimestamp` INTEGER NOT NULL
        )
    """.trimIndent()

    private val v9EventsCreateSql = """
        CREATE TABLE IF NOT EXISTS rule_events (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `ruleType` TEXT NOT NULL,
            `mac` TEXT NOT NULL,
            `message` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL
        )
    """.trimIndent()

    @Test
    fun `v9 to v10 keeps old rssi rule and backfills metric columns`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(v9RulesCreateSql)
                statement.execute(v9EventsCreateSql)
                statement.executeUpdate(
                    """
                    INSERT INTO rules (type, threshold, enabled, createdAt, lastTriggeredTimestamp)
                    VALUES ('LOW_RSSI', -70, 1, 1762300000000, 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO rules (type, threshold, enabled, createdAt, lastTriggeredTimestamp)
                    VALUES ('DISCONNECTED', 0, 1, 1762300001000, 0)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO rule_events (ruleType, mac, message, timestamp)
                    VALUES ('LOW_RSSI', 'AA:BB:CC:DD:EE:01', '信号弱', 1762300002000)
                    """.trimIndent(),
                )
                RuleMigrationSql.V9_TO_V10.forEach(statement::execute)
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT type, threshold, enabled, metric, comparator, thresholdValue, consecutive
                    FROM rules WHERE type = 'LOW_RSSI'
                    """.trimIndent(),
                ).use { cursor ->
                    assertEquals(true, cursor.next())
                    assertEquals("LOW_RSSI", cursor.getString("type"))
                    assertEquals(-70, cursor.getInt("threshold"))
                    assertEquals(1, cursor.getInt("enabled"))
                    assertNull(cursor.getString("metric"))
                    assertEquals("<", cursor.getString("comparator"))
                    assertEquals(-70.0, cursor.getDouble("thresholdValue"), 0.001)
                    assertEquals(1, cursor.getInt("consecutive"))
                }
                statement.executeQuery(
                    """
                    SELECT threshold, metric, comparator, thresholdValue, consecutive
                    FROM rules WHERE type = 'DISCONNECTED'
                    """.trimIndent(),
                ).use { cursor ->
                    assertEquals(true, cursor.next())
                    assertEquals(0, cursor.getInt("threshold"))
                    assertNull(cursor.getString("metric"))
                    assertEquals(">", cursor.getString("comparator"))
                    assertEquals(0.0, cursor.getDouble("thresholdValue"), 0.001)
                    assertEquals(1, cursor.getInt("consecutive"))
                }
                statement.executeQuery(
                    """
                    SELECT ruleType, mac, message, timestamp, metric, value, thresholdValue, unit
                    FROM rule_events
                    """.trimIndent(),
                ).use { cursor ->
                    assertEquals(true, cursor.next())
                    assertEquals("LOW_RSSI", cursor.getString("ruleType"))
                    assertEquals("AA:BB:CC:DD:EE:01", cursor.getString("mac"))
                    assertEquals("信号弱", cursor.getString("message"))
                    assertEquals(1762300002000L, cursor.getLong("timestamp"))
                    assertNull(cursor.getString("metric"))
                    assertNull(cursor.getObject("value"))
                    assertNull(cursor.getObject("thresholdValue"))
                    assertNull(cursor.getString("unit"))
                }
                assertEquals(2, countRules(connection))
            }
        }
    }

    private fun countRules(connection: java.sql.Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM rules").use { cursor ->
                cursor.next()
                cursor.getInt(1)
            }
        }
}
