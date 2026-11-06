package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * v21 -> v22：新增 `push_devices` 推送设备映射表。
 * 用真实 SQLite 执行迁移，既有设备/场景数据必须原样保留。
 */
class PushDeviceMigrationTest {

    @Test
    fun `v21 to v22 creates push devices and keeps old data`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS devices (
                        `mac` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `lastRssi` INTEGER NOT NULL,
                        `lastSeenTimestamp` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO devices (mac, name, lastRssi, lastSeenTimestamp) " +
                        "VALUES ('C8:47:8C:AA:BB:CC', '客厅温度计', -62, 1760000000000)",
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS scenes (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO scenes (name, createdAt) VALUES ('回家', 1760000001000)",
                )
            }

            PushDeviceMigrationSql.V21_TO_V22.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            // 旧数据原样保留。
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT mac, name, lastRssi, lastSeenTimestamp FROM devices",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("C8:47:8C:AA:BB:CC", cursor.getString("mac"))
                    assertEquals("客厅温度计", cursor.getString("name"))
                    assertEquals(-62, cursor.getInt("lastRssi"))
                    assertEquals(1760000000000L, cursor.getLong("lastSeenTimestamp"))
                }
                statement.executeQuery("SELECT name FROM scenes").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("回家", cursor.getString("name"))
                }
            }

            // 新表默认可写入，未配置状态用空 token 记录，不会注册到服务端。
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO push_devices (token, status, lastError) " +
                        "VALUES ('', 'unconfigured', '未配置 FCM')",
                )
                statement.executeUpdate(
                    "INSERT INTO push_devices (token, remoteDeviceId, status, retryCount) " +
                        "VALUES ('fcm-token-1', 'remote-1', 'registered', 0)",
                )
                statement.executeQuery(
                    "SELECT token, remoteDeviceId, status, platform, lastError FROM push_devices " +
                        "ORDER BY status",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("fcm-token-1", cursor.getString("token"))
                    assertEquals("remote-1", cursor.getString("remoteDeviceId"))
                    assertEquals("registered", cursor.getString("status"))
                    assertEquals("android", cursor.getString("platform"))
                    assertNull(cursor.getObject("lastError"))

                    assertTrue(cursor.next())
                    assertEquals("", cursor.getString("token"))
                    assertEquals("unconfigured", cursor.getString("status"))
                    assertNotNull(cursor.getObject("lastError"))
                }
            }
        }
    }
}
