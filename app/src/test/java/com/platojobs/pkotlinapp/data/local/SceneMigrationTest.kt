package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** v8 -> v9：新增仅本机的 scenes / scene_actions，校验外键与级联删除。 */
class SceneMigrationTest {

    @Test
    fun `v8 to v9 creates scene tables and keeps device data`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute(
                    "CREATE TABLE IF NOT EXISTS `devices` (`mac` TEXT NOT NULL PRIMARY KEY)",
                )
                statement.executeUpdate(
                    "INSERT INTO devices (mac) VALUES ('AA:BB:CC:00:00:01')",
                )
            }

            SceneMigrationSql.V8_TO_V9.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            assertEquals(1, countRows(connection, "devices"))
            assertEquals(0, countRows(connection, "scenes"))
            assertEquals(0, countRows(connection, "scene_actions"))
            assertEquals(
                listOf("id", "name", "createdAt"),
                tableColumns(connection, "scenes"),
            )
            assertEquals(
                listOf("id", "sceneId", "position", "type", "deviceMac", "muteMinutes"),
                tableColumns(connection, "scene_actions"),
            )
            assertTrue(indexNames(connection, "scene_actions").contains("index_scene_actions_sceneId"))

            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO scenes (id, name, createdAt) VALUES (1, '回家', 1760000000000)",
                )
                statement.executeUpdate(
                    "INSERT INTO scene_actions (sceneId, position, type, deviceMac, muteMinutes) " +
                        "VALUES (1, 0, 'CONNECT_DEVICE', 'AA:BB:CC:00:00:01', NULL)",
                )
            }
            assertEquals(1, countRows(connection, "scenes"))
            assertEquals(1, countRows(connection, "scene_actions"))

            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_key_list(scene_actions)").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("scenes", cursor.getString("table"))
                    assertEquals("id", cursor.getString("to"))
                    assertEquals("sceneId", cursor.getString("from"))
                    assertEquals("CASCADE", cursor.getString("on_delete"))
                }
            }

            connection.createStatement().use {
                it.executeUpdate("DELETE FROM scenes WHERE id = 1")
            }
            assertEquals(0, countRows(connection, "scene_actions"))
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
