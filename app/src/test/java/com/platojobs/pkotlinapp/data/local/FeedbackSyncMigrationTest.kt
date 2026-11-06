package com.platojobs.pkotlinapp.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * 真实 SQLite 执行 v16 -> v17（反馈发送状态机）迁移：
 * - feedback_drafts 增加 retryCount / lastError / lastErrorCode /
 *   lastErrorRetryAfterSeconds / remoteFeedbackId / lastAttemptAt；
 * - 旧草稿内容、state 原样保留，新增列使用默认值（0 次重试、无错误、无远端 ID）。
 */
class FeedbackSyncMigrationTest {

    private val v16FeedbackDrafts = """
        CREATE TABLE IF NOT EXISTS feedback_drafts (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `category` TEXT NOT NULL,
            `description` TEXT NOT NULL,
            `contact` TEXT,
            `createdAt` INTEGER NOT NULL,
            `state` TEXT NOT NULL DEFAULT 'pending'
        )
    """.trimIndent()

    @Test
    fun `v16 to v17 keeps old drafts and adds sync state columns`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(v16FeedbackDrafts)
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS `index_feedback_drafts_state` " +
                        "ON `feedback_drafts` (`state`)",
                )
                // 旧版本的两类草稿：未发送与（历史上被标记的）已发送。
                statement.executeUpdate(
                    """
                    INSERT INTO feedback_drafts (category, description, contact, createdAt, state)
                    VALUES ('CONNECTION', '连接失败', 'me@example.com', 100, 'pending')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO feedback_drafts (category, description, contact, createdAt, state)
                    VALUES ('SYNC', '同步冲突', NULL, 200, 'sent')
                    """.trimIndent(),
                )

                FeedbackSyncMigrationSql.V16_TO_V17.forEach { sql -> statement.execute(sql) }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT category, description, contact, state, retryCount, " +
                        "lastError, lastErrorCode, lastErrorRetryAfterSeconds, " +
                        "remoteFeedbackId, lastAttemptAt FROM feedback_drafts ORDER BY id",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("CONNECTION", cursor.getString("category"))
                    assertEquals("连接失败", cursor.getString("description"))
                    assertEquals("me@example.com", cursor.getString("contact"))
                    assertEquals("pending", cursor.getString("state"))
                    assertEquals(0, cursor.getInt("retryCount"))
                    assertNull(cursor.getObject("lastError"))
                    assertNull(cursor.getObject("lastErrorCode"))
                    assertNull(cursor.getObject("lastErrorRetryAfterSeconds"))
                    assertNull(cursor.getObject("remoteFeedbackId"))
                    assertNull(cursor.getObject("lastAttemptAt"))

                    assertTrue(cursor.next())
                    assertEquals("SYNC", cursor.getString("category"))
                    assertEquals("同步冲突", cursor.getString("description"))
                    assertEquals("sent", cursor.getString("state"))
                    assertEquals(0, cursor.getInt("retryCount"))
                    assertNull(cursor.getObject("remoteFeedbackId"))
                }

                // 新增列可被真实状态机写入。
                statement.executeUpdate(
                    """
                    UPDATE feedback_drafts
                    SET state = 'failed', retryCount = 3, lastError = 'offline',
                        lastErrorCode = 'NETWORK', lastErrorRetryAfterSeconds = NULL,
                        lastAttemptAt = 400
                    WHERE id = 1
                    """.trimIndent(),
                )
                statement.executeQuery(
                    "SELECT state, retryCount, lastError, lastErrorCode FROM feedback_drafts WHERE id = 1",
                ).use { cursor ->
                    assertTrue(cursor.next())
                    assertEquals("failed", cursor.getString("state"))
                    assertEquals(3, cursor.getInt("retryCount"))
                    assertEquals("offline", cursor.getString("lastError"))
                    assertEquals("NETWORK", cursor.getString("lastErrorCode"))
                }

                // 旧索引仍在，列表查询不会退化。
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'index' " +
                        "AND name = 'index_feedback_drafts_state'",
                ).use { cursor ->
                    assertTrue(cursor.next())
                }
            }
        }
    }
}
