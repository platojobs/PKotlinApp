package com.platojobs.pkotlinapp.data.local

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v18 -> v19 只新增首页聚合索引，必须保留全部历史行。
 * 用真实 SQLite 执行迁移 SQL，并核对索引名与列顺序和 Room 导出的 19.json 一致。
 */
class HomeAggregationIndexMigrationTest {

    @Test
    fun `v18 to v19 adds home aggregation indexes without touching data`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE `readings` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `deviceId` TEXT NOT NULL,
                        `metric` TEXT NOT NULL,
                        `value` REAL NOT NULL,
                        `unit` TEXT NOT NULL,
                        `recordedAt` INTEGER NOT NULL,
                        `syncState` TEXT NOT NULL DEFAULT 'pending',
                        `rawSnapshot` TEXT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE TABLE `devices` (`mac` TEXT NOT NULL PRIMARY KEY, `lastSeenTimestamp` INTEGER NOT NULL)",
                )
                statement.execute(
                    "CREATE TABLE `rule_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL)",
                )
                repeat(3) { index ->
                    statement.executeUpdate(
                        "INSERT INTO readings (id, deviceId, metric, value, unit, recordedAt, syncState) " +
                            "VALUES ('r$index', 'AA:BB', 'temperature', 20.0, '°C', ${1_000 + index}, 'sent')",
                    )
                }
                statement.executeUpdate("INSERT INTO devices (mac, lastSeenTimestamp) VALUES ('AA:BB', 1000)")
                statement.executeUpdate("INSERT INTO rule_events (timestamp) VALUES (1000)")
            }

            HomeAggregationIndexSql.V18_TO_V19.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            assertEquals(3, countRows(connection, "readings"))
            assertEquals(1, countRows(connection, "devices"))
            assertEquals(1, countRows(connection, "rule_events"))

            assertIndex(connection, "readings", "index_readings_recordedAt", listOf("recordedAt"))
            assertIndex(
                connection,
                "readings",
                "index_readings_deviceId_recordedAt",
                listOf("deviceId", "recordedAt"),
            )
            assertIndex(
                connection,
                "readings",
                "index_readings_metric_deviceId_recordedAt",
                listOf("metric", "deviceId", "recordedAt"),
            )
            assertIndex(connection, "rule_events", "index_rule_events_timestamp", listOf("timestamp"))
            assertIndex(
                connection,
                "devices",
                "index_devices_lastSeenTimestamp",
                listOf("lastSeenTimestamp"),
            )
        }
    }

    private fun assertIndex(
        connection: Connection,
        table: String,
        name: String,
        columns: List<String>,
    ) {
        val names = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list('$table')").use { cursor ->
                while (cursor.next()) names += cursor.getString("name")
            }
        }
        assertTrue("$table 缺少索引 $name，实际 $names", name in names)

        val indexColumns = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_info('$name')").use { cursor ->
                while (cursor.next()) indexColumns += cursor.getString("name")
            }
        }
        assertEquals("$name 列不匹配", columns, indexColumns)
    }

    private fun countRows(connection: Connection, table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.next())
                cursor.getInt(1)
            }
        }
}
