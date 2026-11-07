package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

/**
 * v24 -> v25：新增服务端只读的 lastSeenAt。
 * 历史设备从未拿到过服务端心跳，迁移后必须保持 null，
 * 不能用 updatedAt / lastSeenTimestamp 伪造。
 */
class DeviceLastSeenMigrationTest {

    @Test
    fun `v24 to v25 adds lastSeenAt as null without touching existing rows`() {
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
                        `markedAtDirty` INTEGER NOT NULL DEFAULT 0
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

            DeviceMarkDirtyMigrationSql.V24_TO_V25.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT mac, lastSeenAt, updatedAt, syncState FROM devices",
                ).use { cursor ->
                    assertEquals(true, cursor.next())
                    assertEquals("C8:47:8C:AA:BB:CC", cursor.getString("mac"))
                    // 没有服务端心跳记录：保持 null，不拿 updatedAt 冒充。
                    assertNull(cursor.getObject("lastSeenAt"))
                    assertEquals(1760000005000L, cursor.getLong("updatedAt"))
                    assertEquals("synced", cursor.getString("syncState"))
                }
            }
        }
    }
}
