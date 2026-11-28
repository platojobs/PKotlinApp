package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.DriverManager

/**
 * v25 -> v26：设备新增「待解绑」标记。
 * 历史数据没有未完成的解绑请求，迁移后必须默认 0，且不影响其它列。
 */
class DevicePendingDeleteMigrationTest {

    @Test
    fun `v25 to v26 adds pendingDelete defaulting to zero`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS devices (
                        `mac` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `lastRssi` INTEGER NOT NULL,
                        `lastSeenTimestamp` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        `syncState` TEXT NOT NULL DEFAULT 'synced',
                        `markedAt` INTEGER,
                        `markedAtDirty` INTEGER NOT NULL DEFAULT 0,
                        `lastSeenAt` INTEGER
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO devices " +
                        "(mac, name, lastRssi, lastSeenTimestamp, updatedAt, syncState) " +
                        "VALUES ('C8:47:8C:AA:BB:CC', '客厅温度计', -62, 1760000000000, " +
                        "1760000005000, 'synced')",
                )
            }

            DevicePendingDeleteMigrationSql.V25_TO_V26.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT mac, pendingDelete, updatedAt, syncState FROM devices",
                ).use { cursor ->
                    assertEquals(true, cursor.next())
                    assertEquals("C8:47:8C:AA:BB:CC", cursor.getString("mac"))
                    assertEquals(0, cursor.getInt("pendingDelete"))
                    assertEquals(1760000005000L, cursor.getLong("updatedAt"))
                    assertEquals("synced", cursor.getString("syncState"))
                }
            }
        }
    }
}
