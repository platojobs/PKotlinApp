package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * 真实 SQLite 执行 v17 -> v18（设备类型目录缓存）迁移：
 * - 新增 device_type_catalog / catalog_sync_state 两张表；
 * - 既有 devices 数据不受影响；
 * - 条目与 ETag/Last-Modified 可在同一事务写入，304 只刷新 lastUsedAt。
 */
class CatalogCacheMigrationTest {

    private val v17Devices = """
        CREATE TABLE IF NOT EXISTS devices (
            `mac` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `lastRssi` INTEGER NOT NULL,
            `lastSeenTimestamp` INTEGER NOT NULL,
            PRIMARY KEY(`mac`)
        )
    """.trimIndent()

    @Test
    fun `v17 to v18 creates catalog cache tables without touching devices`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(v17Devices)
                statement.executeUpdate(
                    """
                    INSERT INTO devices (mac, name, lastRssi, lastSeenTimestamp)
                    VALUES ('AA:BB:CC:DD:EE:FF', '旧设备', -60, 100)
                    """.trimIndent(),
                )

                CatalogMigrationSql.V17_TO_V18.forEach { sql -> statement.execute(sql) }
            }

            connection.createStatement().use { statement ->
                // 旧数据仍在。
                statement.executeQuery("SELECT name FROM devices").use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("旧设备", cursor.getString("name"))
                }

                // 目录条目可写入，payload 默认空对象。
                statement.executeUpdate(
                    """
                    INSERT INTO device_type_catalog
                        (type, displayName, description, updatedAt, sortOrder)
                    VALUES ('environmental_sensor', '环境传感器', '温湿度采集',
                            '2026-10-01T08:00:00Z', 0)
                    """.trimIndent(),
                )
                statement.executeQuery(
                    "SELECT payloadJson FROM device_type_catalog WHERE type = 'environmental_sensor'",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("{}", cursor.getString("payloadJson"))
                }

                // 校验器与条目一起保存；任何更新都必须换 ETag。
                statement.executeUpdate(
                    """
                    INSERT INTO catalog_sync_state
                        (id, version, generatedAt, etag, lastModified, fetchedAt, lastUsedAt)
                    VALUES (1, 12, '2026-10-01T08:00:00Z', '"catalog-v12"',
                            'Wed, 01 Oct 2026 08:00:00 GMT', 500, 500)
                    """.trimIndent(),
                )
                statement.executeQuery(
                    "SELECT version, etag, lastModified, fetchedAt, lastUsedAt " +
                        "FROM catalog_sync_state WHERE id = 1",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(12, cursor.getInt("version"))
                    assertEquals("\"catalog-v12\"", cursor.getString("etag"))
                    assertEquals("Wed, 01 Oct 2026 08:00:00 GMT", cursor.getString("lastModified"))
                    assertEquals(500, cursor.getLong("fetchedAt"))
                    assertEquals(500, cursor.getLong("lastUsedAt"))
                }

                // 304 分支只刷新使用时间，条目与校验器保持不变。
                statement.executeUpdate(
                    "UPDATE catalog_sync_state SET lastUsedAt = 900 WHERE id = 1",
                )
                statement.executeQuery(
                    "SELECT etag, fetchedAt, lastUsedAt FROM catalog_sync_state WHERE id = 1",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("\"catalog-v12\"", cursor.getString("etag"))
                    assertEquals(500, cursor.getLong("fetchedAt"))
                    assertEquals(900, cursor.getLong("lastUsedAt"))
                }

                // 主键约束：同一类型不能出现两份互相竞争的缓存。
                val duplicate = runCatching {
                    statement.executeUpdate(
                        """
                        INSERT INTO device_type_catalog
                            (type, displayName, description, updatedAt, sortOrder)
                        VALUES ('environmental_sensor', '重复', '', '', 1)
                        """.trimIndent(),
                    )
                }
                assertTrue(duplicate.isFailure)
                statement.executeQuery(
                    "SELECT COUNT(*) AS total FROM device_type_catalog",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals(1, cursor.getInt("total"))
                }
            }
        }
    }
}
