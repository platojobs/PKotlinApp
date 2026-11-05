package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * v11 -> v12：新增 households 表与远端房间字段，必须原样保留本机房间和设备归属。
 */
class HouseholdMigrationTest {

    @Test
    fun `v11 to v12 keeps local rooms and device assignments`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            createV11Schema(connection)
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO rooms (id, name, sortOrder, createdAt) " +
                        "VALUES (1, '客厅', 0, 1762300000000)",
                )
                statement.executeUpdate(
                    "INSERT INTO rooms (id, name, sortOrder, createdAt) " +
                        "VALUES (2, '卧室', 1, 1762300001000)",
                )
                statement.executeUpdate(
                    "INSERT INTO devices (mac, name, lastSeenTimestamp, roomId) " +
                        "VALUES ('AA:BB:CC:00:00:01', '温湿度计', 1762300002000, 1)",
                )
                statement.executeUpdate(
                    "INSERT INTO devices (mac, name, lastSeenTimestamp, roomId) " +
                        "VALUES ('AA:BB:CC:00:00:02', '传感器', 1762300003000, NULL)",
                )
            }

            HouseholdMigrationSql.V11_TO_V12.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            // 房间与设备归属完全保留。
            assertEquals(2, countRows(connection, "rooms"))
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT mac, roomId FROM devices ORDER BY mac").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(1, cursor.getInt("roomId"))
                    assertTrue(cursor.next())
                    assertNull(cursor.getObject("roomId"))
                }
                statement.executeQuery("SELECT id, name, remoteId, householdId FROM rooms ORDER BY id")
                    .use { cursor ->
                        assertTrue(cursor.next())
                        assertEquals(1, cursor.getInt("id"))
                        assertEquals("客厅", cursor.getString("name"))
                        assertNull(cursor.getString("remoteId"))
                        assertNull(cursor.getObject("householdId"))
                    }
            }

            // 新字段与 households 表可用，且设备 pending 标记默认关闭。
            assertTrue(tableColumns(connection, "households").contains("remoteId"))
            assertTrue(tableColumns(connection, "rooms").contains("remoteId"))
            assertTrue(tableColumns(connection, "devices").contains("remoteRoomId"))
            assertTrue(tableColumns(connection, "devices").contains("pendingRoomPatch"))
            assertTrue(tableColumns(connection, "devices").contains("shared"))
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM devices WHERE pendingRoomPatch = 0 AND shared = 0",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(2, cursor.getInt(1))
                }
                statement.executeUpdate(
                    "INSERT INTO households (remoteId, name, isPersonal, role) " +
                        "VALUES ('h-1', '我的家', 1, 'owner')",
                )
            }
            assertEquals(1, countRows(connection, "households"))

            // 迁移后仍可正常写入远端房间映射。
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE rooms SET remoteId = 'r-1', householdId = 1 WHERE id = 1",
                )
            }
            assertEquals(2, countRows(connection, "rooms"))
        }
    }

    private fun createV11Schema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS `devices` (
                    `mac` TEXT NOT NULL PRIMARY KEY,
                    `name` TEXT NOT NULL,
                    `lastSeenTimestamp` INTEGER NOT NULL,
                    `deviceGroup` TEXT,
                    `roomId` INTEGER
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS `rooms` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `sortOrder` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
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
