package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

/**
 * v22 -> v23：设备新增 markedAtDirty（默认 0）。
 * v23 -> v24：v0.32.3 之前的纯本机标记补进待同步队列，
 * 保留既有 markedAt，只影响自己账号的已标记设备。
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
                        `markedAt` INTEGER,
                        `shared` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        `syncState` TEXT NOT NULL DEFAULT 'synced'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO devices " +
                        "(mac, name, lastRssi, lastSeenTimestamp, markedAt, shared, updatedAt, syncState) " +
                        "VALUES ('C8:47:8C:AA:BB:CC', '客厅温度计', -62, 1760000000000, " +
                        "1760000005000, 0, 1760000000000, 'synced')",
                )
                statement.executeUpdate(
                    "INSERT INTO devices " +
                        "(mac, name, lastRssi, lastSeenTimestamp, markedAt, shared, updatedAt, syncState) " +
                        "VALUES ('C8:47:8C:DD:EE:FF', '卧室温湿度计', -70, 1760000001000, " +
                        "NULL, 0, 1760000001000, 'synced')",
                )
            }

            DeviceMarkDirtyMigrationSql.V22_TO_V23.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT mac, markedAt, markedAtDirty FROM devices ORDER BY mac",
                ).use { cursor ->
                    // 加列后既有标记保留，且默认不当作待上报。
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

            // v23 -> v24：旧标记从未上报过服务端，必须补成 pending 才能真正同步。
            DeviceMarkDirtyMigrationSql.V23_TO_V24.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT mac, markedAt, markedAtDirty, updatedAt, syncState " +
                        "FROM devices ORDER BY mac",
                ).use { cursor ->
                    assertEquals(true, cursor.next())
                    assertEquals(1760000005000L, cursor.getLong("markedAt"))
                    assertEquals(1, cursor.getInt("markedAtDirty"))
                    // updatedAt 至少推进到标记时间，才能通过服务端 updatedAt 门控。
                    assertEquals(1760000005000L, cursor.getLong("updatedAt"))
                    assertEquals("pending", cursor.getString("syncState"))

                    // 未标记设备不受影响。
                    assertEquals(true, cursor.next())
                    assertNull(cursor.getObject("markedAt"))
                    assertEquals(0, cursor.getInt("markedAtDirty"))
                    assertEquals(1760000001000L, cursor.getLong("updatedAt"))
                    assertEquals("synced", cursor.getString("syncState"))
                }
            }
        }
    }
}
