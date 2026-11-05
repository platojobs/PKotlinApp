package com.platojobs.pkotlinapp.domain.account

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountExportJsonTest {

    private fun page(
        readings: List<Map<String, Any?>>,
        devices: List<Map<String, Any?>> = emptyList(),
        exportedAt: String = "2026-01-01T00:00:00Z",
    ) = AccountExportPage(
        user = mapOf("id" to "u1"),
        devices = devices,
        readings = readings,
        exportedAt = 0L,
        exportedAtRaw = exportedAt,
        nextCursor = null,
        hasMore = false,
    )

    @Test
    fun `合并时 readings 顺序拼接 devices 按 id 去重`() {
        val deviceA = mapOf("id" to "d1", "externalId" to "AA")
        val deviceB = mapOf("id" to "d2", "externalId" to "BB")
        val merged = AccountExportJson.merge(
            listOf(
                page(
                    readings = listOf(mapOf("id" to "r1")),
                    devices = listOf(deviceA, deviceB),
                ),
                page(
                    readings = listOf(mapOf("id" to "r2")),
                    devices = listOf(deviceA),
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val devices = merged["devices"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val readings = merged["readings"] as List<Map<String, Any?>>
        assertEquals(2, devices.size)
        assertEquals(listOf("d1", "d2"), devices.map { it["id"] })
        assertEquals(listOf("r1", "r2"), readings.map { it["id"] })
        assertEquals("2026-01-01T00:00:00Z", merged["exportedAt"])
    }

    @Test
    fun `渲染为可解析 JSON 且包含页数`() {
        val moshi = Moshi.Builder().build()
        val json = AccountExportJson.render(
            moshi,
            listOf(page(readings = listOf(mapOf("id" to "r1", "metric" to "temperature")))) +
                page(readings = listOf(mapOf("id" to "r2"))),
        )

        assertTrue(json.contains("\"readings\""))
        assertTrue(json.contains("\"pageCount\": 2"))
        assertTrue(json.contains("\"id\": \"r1\""))
    }

    @Test
    fun `文件名包含 userId 与 UTC 时间`() {
        val name = AccountExportJson.fileName("u-123", 1_700_000_000_000L)
        assertEquals("account_export_u-123_20231114T221320Z.json", name)
    }

    @Test
    fun `无 userId 时回退 anonymous`() {
        assertEquals(
            "account_export_anonymous_20231114T221320Z.json",
            AccountExportJson.fileName(null, 1_700_000_000_000L),
        )
    }
}
