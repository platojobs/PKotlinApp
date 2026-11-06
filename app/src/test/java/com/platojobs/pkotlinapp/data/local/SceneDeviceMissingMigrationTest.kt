package com.platojobs.pkotlinapp.data.local

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v20 -> v21：场景表补 `deviceMissing`（与 iOS 的 device_missing 对齐）与 429 `retryAfterAt`。
 * 用真实 SQLite 执行迁移，既有场景必须原样保留。
 */
class SceneDeviceMissingMigrationTest {

    @Test
    fun `v20 to v21 adds device missing and retry columns keeping old scenes`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS scenes (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `remoteId` TEXT,
                        `version` INTEGER NOT NULL DEFAULT 1,
                        `syncState` TEXT NOT NULL DEFAULT 'pending',
                        `syncError` TEXT,
                        `deletedAt` INTEGER
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO scenes (name, createdAt, remoteId, version, syncState) " +
                        "VALUES ('回家', 1760000000000, 'scene-1', 3, 'pending')",
                )
                statement.executeUpdate(
                    "INSERT INTO scenes (name, createdAt, remoteId, version, syncState) " +
                        "VALUES ('睡前', 1760000001000, NULL, 1, 'local_only')",
                )
            }

            SceneDeviceMissingMigrationSql.V20_TO_V21.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name, remoteId, version, syncState, deviceMissing, retryAfterAt " +
                        "FROM scenes ORDER BY id",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("回家", cursor.getString("name"))
                    assertEquals("scene-1", cursor.getString("remoteId"))
                    assertEquals(3, cursor.getInt("version"))
                    assertEquals("pending", cursor.getString("syncState"))
                    assertEquals(0, cursor.getInt("deviceMissing"))
                    assertNull(cursor.getObject("retryAfterAt"))

                    assertTrue(cursor.next())
                    assertEquals("睡前", cursor.getString("name"))
                    assertNull(cursor.getString("remoteId"))
                }
            }

            // 新列可独立写入，不影响既有行。
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE scenes SET deviceMissing = 1, retryAfterAt = 1760000123000 " +
                        "WHERE remoteId = 'scene-1'",
                )
                statement.executeQuery(
                    "SELECT deviceMissing, retryAfterAt FROM scenes WHERE remoteId = 'scene-1'",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(1, cursor.getInt("deviceMissing"))
                    assertEquals(1760000123000L, cursor.getLong("retryAfterAt"))
                }
            }
            assertEquals(2, countRows(connection))
        }
    }

    private fun countRows(connection: java.sql.Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM scenes").use { cursor ->
                assertTrue(cursor.next())
                cursor.getInt(1)
            }
        }
}
