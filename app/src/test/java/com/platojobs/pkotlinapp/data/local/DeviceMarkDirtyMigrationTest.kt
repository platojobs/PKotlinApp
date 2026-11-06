package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

/**
 * v22 -> v23：设备新增 markedAtDirty。
 * 迁移不能丢既有 markedAt；新列默认 0（历史标记视为已确认）。
 */
class DeviceMarkDirtyMigrationTest {

    @Test
    fun `v22 to v23 keeps marks and defaults dirty flag to false`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS devices (
                        `mac` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `lastRssi` INTEGER NOT NULL,
                        `lastSeenTimestamp` INTEGER NOT NULL,
                        `markedAt` INTEGER
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO devices (mac, name, lastRssi, lastSeenTimestamp, markedAt) " +
                        "VALUES ('C8:47:8C:AA:BB:CC', '客厅温度计', -62, 1760000000000, 1760000005000)",
                )
                statement.executeUpdate(
                    "INSERT INTO devices (mac, name, lastRssi, lastSeenTimestamp, markedAt) " +
                        "VALUES ('C8:47:8C:DD:EE:FF', '卧室温湿度计', -70, 1760000001000, NULL)",
                )
            }

            DeviceMarkDirtyMigrationSql.V22_TO_V23.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT mac, markedAt, markedAtDirty FROM devices ORDER BY mac",
                ).use { cursor ->
                    // 迁移后既有标记保留，且不当作待上报。
                    assertEquals(true, cursor.next())
                    assertEquals("C8:47:8C:AA:BB:CC", cursor.getString("mac"))
                    assertEquals(1760000005000L, cursor.getLong("markedAt"))
                    assertEquals(0, cursor.getInt("markedAtDirty"))

                    assertEquals(true, cursor.next())
                    assertEquals("C8:47:8C:DD:EE:FF", cursor.getString("mac"))
                    assertNull(cursor.getObject("markedAt"))
                    assertEquals(0, cursor.getInt("markedAtDirty"))
                }
            }

            // 标记只改目标设备，另一台设备的数据不受影响。
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE devices SET markedAt = 1760000009000, markedAtDirty = 1 " +
                        "WHERE mac = 'C8:47:8C:AA:BB:CC'",
                )
                statement.executeQuery(
                    "SELECT mac, markedAt, markedAtDirty FROM devices ORDER BY mac",
                ).use { cursor ->
                    assertEquals(true, cursor.next())
                    assertEquals(1760000009000L, cursor.getLong("markedAt"))
                    assertEquals(1, cursor.getInt("markedAtDirty"))

                    assertEquals(true, cursor.next())
                    assertNull(cursor.getObject("markedAt"))
                    assertEquals(0, cursor.getInt("markedAtDirty"))
                }
            }
        }
    }
}
