package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * 用真实 SQLite 执行 v3 → v4 的迁移 SQL：
 * 验证新增列存在、所有历史行仍在、旧字段值逐列保留。
 */
class DeviceMigrationTest {

    private val v3DevicesCreateSql = """
        CREATE TABLE IF NOT EXISTS devices (
            `mac` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `lastRssi` INTEGER NOT NULL,
            `lastSeenTimestamp` INTEGER NOT NULL,
            `id` TEXT NOT NULL DEFAULT '',
            `platform` TEXT NOT NULL DEFAULT 'android',
            `externalId` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER,
            `syncState` TEXT NOT NULL DEFAULT 'pending',
            `deviceType` TEXT,
            `vendor` TEXT,
            `firmwareVersion` TEXT,
            `compatibility` TEXT,
            PRIMARY KEY(`mac`)
        )
    """.trimIndent()

    @Test
    fun `v3 to v4 keeps every device row and column`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { it.execute(v3DevicesCreateSql) }

            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO devices (
                        mac, name, lastRssi, lastSeenTimestamp, id, platform, externalId,
                        updatedAt, deletedAt, syncState, deviceType, vendor, firmwareVersion,
                        compatibility
                    ) VALUES (
                        'AA:BB:CC:DD:EE:01', '温湿度计', -55, 1762300000000,
                        'client-uuid-1', 'android', 'AA:BB:CC:DD:EE:01', 1762300000000,
                        NULL, 'synced', 'nordic_uart', 'Nordic', '1.2.0', 'android · ble'
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO devices (
                        mac, name, lastRssi, lastSeenTimestamp, id, platform, externalId,
                        updatedAt, deletedAt, syncState, deviceType, vendor, firmwareVersion,
                        compatibility
                    ) VALUES (
                        'AA:BB:CC:DD:EE:02', '已删除设备', -80, 1762290000000,
                        'client-uuid-2', 'android', 'AA:BB:CC:DD:EE:02', 1762290000000,
                        1762300000000, 'pending', NULL, NULL, NULL, NULL
                    )
                    """.trimIndent(),
                )
            }

            assertEquals(2, countRows(connection))

            DeviceMigrationSql.V3_TO_V4.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            // v3 的 14 列 + v4 的 2 个可空列，历史列与行数保持不变。
            val columns = tableColumns(connection)
            assertTrue(columns.contains("deviceTypeName"))
            assertTrue(columns.contains("deviceGroup"))
            assertEquals(16, columns.size)
            assertEquals(2, countRows(connection))

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT name, lastRssi, id, syncState, deviceType, vendor,
                           firmwareVersion, compatibility, deviceTypeName, deviceGroup
                    FROM devices WHERE mac = 'AA:BB:CC:DD:EE:01'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("温湿度计", cursor.getString("name"))
                    assertEquals(-55, cursor.getInt("lastRssi"))
                    assertEquals("client-uuid-1", cursor.getString("id"))
                    assertEquals("synced", cursor.getString("syncState"))
                    assertEquals("nordic_uart", cursor.getString("deviceType"))
                    assertEquals("Nordic", cursor.getString("vendor"))
                    assertEquals("1.2.0", cursor.getString("firmwareVersion"))
                    assertEquals("android · ble", cursor.getString("compatibility"))
                    // 迁移新增列对历史数据为 null，由离线 UI 兜底。
                    assertNull(cursor.getString("deviceTypeName"))
                    assertNull(cursor.getString("deviceGroup"))
                }
            }

            // 新增列可正常写入，且不影响其它行。
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    UPDATE devices SET deviceTypeName = 'Nordic UART 温湿度计',
                        deviceGroup = '客厅' WHERE mac = 'AA:BB:CC:DD:EE:01'
                    """.trimIndent(),
                )
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT deviceTypeName, deviceGroup FROM devices
                    WHERE mac = 'AA:BB:CC:DD:EE:01'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("Nordic UART 温湿度计", cursor.getString("deviceTypeName"))
                    assertEquals("客厅", cursor.getString("deviceGroup"))
                }
            }
            assertEquals(2, countRows(connection))
        }
    }

    @Test
    fun `v4 to v5 adds advertisedName without losing data`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { it.execute(v3DevicesCreateSql) }
            DeviceMigrationSql.V3_TO_V4.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO devices (
                        mac, name, lastRssi, lastSeenTimestamp, id, platform, externalId,
                        updatedAt, deletedAt, syncState, deviceType, vendor, firmwareVersion,
                        compatibility, deviceTypeName, deviceGroup
                    ) VALUES (
                        'AA:BB:CC:DD:EE:01', '客厅温湿度计', -55, 1762300000000,
                        'client-uuid-1', 'android', 'AA:BB:CC:DD:EE:01', 1762300000000,
                        NULL, 'synced', 'nordic_uart', 'Nordic', '1.2.0', 'android · ble',
                        'Nordic UART 温湿度计', '客厅'
                    )
                    """.trimIndent(),
                )
            }

            assertEquals(1, countRows(connection))

            DeviceMigrationSql.V4_TO_V5.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }

            val columns = tableColumns(connection)
            assertTrue(columns.contains("advertisedName"))
            assertEquals(17, columns.size)
            assertEquals(1, countRows(connection))

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT name, deviceGroup, deviceTypeName, advertisedName
                    FROM devices WHERE mac = 'AA:BB:CC:DD:EE:01'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.next())
                    // 用户别名与分组保持不变；新增广播名对历史行为 null。
                    assertEquals("客厅温湿度计", cursor.getString("name"))
                    assertEquals("客厅", cursor.getString("deviceGroup"))
                    assertEquals("Nordic UART 温湿度计", cursor.getString("deviceTypeName"))
                    assertNull(cursor.getString("advertisedName"))
                }
            }

            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE devices SET advertisedName = 'PJ-Sensor' " +
                        "WHERE mac = 'AA:BB:CC:DD:EE:01'",
                )
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name, advertisedName FROM devices " +
                        "WHERE mac = 'AA:BB:CC:DD:EE:01'",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    // 广播名写入不会覆盖用户别名。
                    assertEquals("客厅温湿度计", cursor.getString("name"))
                    assertEquals("PJ-Sensor", cursor.getString("advertisedName"))
                }
            }
        }
    }

    private fun countRows(connection: java.sql.Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM devices").use { cursor ->
                assertTrue(cursor.next())
                cursor.getInt(1)
            }
        }

    private fun tableColumns(connection: java.sql.Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(devices)").use { cursor ->
                buildList {
                    while (cursor.next()) {
                        add(cursor.getString("name"))
                    }
                }
            }
        }
}
