package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * v14 -> v15：房间新增 pendingEdit / serverName / serverSortOrder 三列。
 * 既有房间、家庭与设备数据必须原样保留，旧房间默认无 pending、无暂存值。
 */
class RoomStructuralMigrationTest {

    @Test
    fun `v14 to v15 adds pending columns and keeps existing rooms`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            createV14Schema(connection)
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO households (remoteId, name, isPersonal, role, memberCount, " +
                        "deviceCount, roomCount) VALUES ('h-1', '全家', 0, 'owner', 2, 3, 2)",
                )
                statement.executeUpdate(
                    "INSERT INTO rooms (name, sortOrder, createdAt, remoteId, householdId) " +
                        "VALUES ('客厅', 0, 1762300000000, 'r-1', 1)",
                )
                statement.executeUpdate(
                    "INSERT INTO rooms (name, sortOrder, createdAt, remoteId, householdId) " +
                        "VALUES ('卧室', 1, 1762300001000, 'r-2', 1)",
                )
                statement.executeUpdate(
                    "INSERT INTO rooms (name, sortOrder, createdAt, remoteId, householdId) " +
                        "VALUES ('本机新建', 2, 1762300002000, NULL, 1)",
                )
            }

            RoomStructuralSyncMigrationSql.V14_TO_V15.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            assertEquals(3, countRows(connection, "rooms"))
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name, sortOrder, remoteId, householdId, pendingEdit, serverName, " +
                        "serverSortOrder FROM rooms ORDER BY id",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("客厅", cursor.getString("name"))
                    assertEquals(0, cursor.getInt("sortOrder"))
                    assertEquals("r-1", cursor.getString("remoteId"))
                    assertEquals(1, cursor.getInt("householdId"))
                    assertEquals(0, cursor.getInt("pendingEdit"))
                    assertNull(cursor.getString("serverName"))
                    assertNull(cursor.getObject("serverSortOrder"))

                    assertTrue(cursor.next())
                    assertEquals("卧室", cursor.getString("name"))
                    assertEquals(1, cursor.getInt("sortOrder"))

                    assertTrue(cursor.next())
                    assertEquals("本机新建", cursor.getString("name"))
                    assertNull(cursor.getString("remoteId"))
                }
            }

            // 新列可正常写入，且不影响既有行计数。
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE rooms SET pendingEdit = 1, serverName = '服务端名', " +
                        "serverSortOrder = 5 WHERE remoteId = 'r-2'",
                )
                statement.executeQuery(
                    "SELECT pendingEdit, serverName, serverSortOrder FROM rooms " +
                        "WHERE remoteId = 'r-2'",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(1, cursor.getInt("pendingEdit"))
                    assertEquals("服务端名", cursor.getString("serverName"))
                    assertEquals(5, cursor.getInt("serverSortOrder"))
                }
            }
            assertEquals(3, countRows(connection, "rooms"))
        }
    }

    private fun createV14Schema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE `households` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `remoteId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `isPersonal` INTEGER NOT NULL DEFAULT 0,
                    `role` TEXT NOT NULL,
                    `memberCount` INTEGER NOT NULL DEFAULT 0,
                    `deviceCount` INTEGER NOT NULL DEFAULT 0,
                    `sortOrder` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `roomCount` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE `rooms` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `sortOrder` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL,
                    `remoteId` TEXT,
                    `householdId` INTEGER
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
}
