package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * v13 -> v14：只新增 feedback_drafts 表，既有家庭/设备数据必须原样保留。
 * 反馈草稿默认 state=pending，与 UI 的「未发送」语义一致。
 */
class FeedbackMigrationTest {

    @Test
    fun `v13 to v14 adds feedback drafts without touching existing data`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
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
                statement.executeUpdate(
                    "INSERT INTO households (remoteId, name, isPersonal, role, memberCount, " +
                        "deviceCount, roomCount) VALUES ('h-1', '我的家', 1, 'owner', 2, 3, 4)",
                )
            }

            FeedbackMigrationSql.V13_TO_V14.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            assertEquals(1, countRows(connection, "households"))
            assertTrue(tableColumns(connection, "feedback_drafts").containsAll(
                listOf("id", "category", "description", "contact", "createdAt", "state"),
            ))

            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO feedback_drafts (category, description, contact, createdAt) " +
                        "VALUES ('CONNECTION', '连接失败', NULL, 1762300000000)",
                )
                statement.executeQuery(
                    "SELECT category, description, contact, state FROM feedback_drafts",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("CONNECTION", cursor.getString("category"))
                    assertEquals("连接失败", cursor.getString("description"))
                    assertNull(cursor.getObject("contact"))
                    assertEquals("pending", cursor.getString("state"))
                }
                statement.executeQuery("SELECT name, roomCount FROM households").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("我的家", cursor.getString("name"))
                    assertEquals(4, cursor.getInt("roomCount"))
                }
            }
        }
    }

    private fun countRows(connection: java.sql.Connection, table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.next())
                cursor.getInt(1)
            }
        }

    private fun tableColumns(connection: java.sql.Connection, table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { cursor ->
                buildList {
                    while (cursor.next()) add(cursor.getString("name"))
                }
            }
        }
}
