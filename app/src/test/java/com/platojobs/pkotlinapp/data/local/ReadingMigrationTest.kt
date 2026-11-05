package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** 用真实 SQLite 执行 v6 -> v7：新建 readings 表、索引与外键，且不丢设备数据。 */
class ReadingMigrationTest {

    private val v6DevicesCreateSql = """
        CREATE TABLE IF NOT EXISTS devices (
            `mac` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            PRIMARY KEY(`mac`)
        )
    """.trimIndent()

    @Test
    fun `v6 to v7 creates readings table with indexes and keeps devices`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { it.execute(v6DevicesCreateSql) }
            connection.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO devices (mac, name) VALUES ('AA:BB:CC:00:00:01', '温湿度计')",
                )
            }

            ReadingMigrationSql.V6_TO_V7.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            assertEquals(1, countRows(connection, "devices"))
            assertEquals(0, countRows(connection, "readings"))

            val columns = tableColumns(connection, "readings")
            assertEquals(
                listOf(
                    "id", "deviceId", "metric", "value", "unit",
                    "recordedAt", "syncState", "rawSnapshot",
                ),
                columns,
            )

            val indexes = indexNames(connection, "readings")
            assertTrue(indexes.contains("index_readings_deviceId"))
            assertTrue(indexes.contains("index_readings_syncState"))
            assertTrue(indexes.contains("index_readings_deviceId_metric_recordedAt"))

            // 外键与 Room 实体声明一致：devices.mac -> readings.deviceId，级联删除。
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_key_list(readings)").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("devices", cursor.getString("table"))
                    assertEquals("mac", cursor.getString("to"))
                    assertEquals("deviceId", cursor.getString("from"))
                    assertEquals("CASCADE", cursor.getString("on_delete"))
                }
            }

            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO readings (id, deviceId, metric, value, unit, recordedAt, syncState)
                    VALUES ('r-1', 'AA:BB:CC:00:00:01', 'temperature', 21.5, '°C', 1760000000000, 'pending')
                    """.trimIndent(),
                )
            }
            assertEquals(1, countRows(connection, "readings"))
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT metric, value, unit, syncState, rawSnapshot FROM readings WHERE id = 'r-1'",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("temperature", cursor.getString("metric"))
                    assertEquals(21.5, cursor.getDouble("value"), 0.0)
                    assertEquals("°C", cursor.getString("unit"))
                    // syncState 默认值应为 pending，供同步器直接查询。
                    assertEquals("pending", cursor.getString("syncState"))
                    assertEquals(null, cursor.getString("rawSnapshot"))
                }
            }
        }
    }

    private fun countRows(connection: Connection, table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.next())
                cursor.getInt(1)
            }
        }

    private fun tableColumns(connection: Connection, table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { cursor ->
                buildList {
                    while (cursor.next()) add(cursor.getString("name"))
                }
            }
        }

    private fun indexNames(connection: Connection, table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list($table)").use { cursor ->
                buildList {
                    while (cursor.next()) add(cursor.getString("name"))
                }
            }
        }
}
