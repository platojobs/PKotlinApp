package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * v10 -> v11：新增本机 rooms 表，devices 增加可空 roomId。
 * 旧设备数量与字段原样保留，roomId 为 null（未分配），deviceGroup 不丢失。
 */
class RoomMigrationTest {

    @Test
    fun `v10 to v11 creates rooms and keeps existing devices unassigned`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS `devices` (
                        `mac` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `lastSeenTimestamp` INTEGER NOT NULL,
                        `deviceGroup` TEXT
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO devices (mac, name, lastSeenTimestamp, deviceGroup)
                    VALUES ('AA:BB:CC:00:00:01', '客厅温湿度计', 1762300000000, '客厅')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO devices (mac, name, lastSeenTimestamp, deviceGroup)
                    VALUES ('AA:BB:CC:00:00:02', '卧室传感器', 1762300001000, NULL)
                    """.trimIndent(),
                )
            }

            RoomMigrationSql.V10_TO_V11.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            assertEquals(2, countRows(connection, "devices"))
            assertEquals(0, countRows(connection, "rooms"))
            assertEquals(
                listOf("id", "name", "sortOrder", "createdAt"),
                tableColumns(connection, "rooms"),
            )
            assertTrue(tableColumns(connection, "devices").contains("roomId"))

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT mac, deviceGroup, roomId FROM devices ORDER BY mac",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("AA:BB:CC:00:00:01", cursor.getString("mac"))
                    assertEquals("客厅", cursor.getString("deviceGroup"))
                    assertNull(cursor.getObject("roomId"))
                    assertTrue(cursor.next())
                    assertEquals("AA:BB:CC:00:00:02", cursor.getString("mac"))
                    assertNull(cursor.getString("deviceGroup"))
                    assertNull(cursor.getObject("roomId"))
                }
                statement.executeUpdate(
                    "INSERT INTO rooms (name, sortOrder, createdAt) VALUES ('客厅', 0, 1762300002000)",
                )
                statement.executeUpdate(
                    "UPDATE devices SET roomId = 1 WHERE mac = 'AA:BB:CC:00:00:01'",
                )
                statement.executeQuery(
                    "SELECT COUNT(*) FROM devices WHERE roomId = 1",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(1, cursor.getInt(1))
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
}
