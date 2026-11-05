package com.platojobs.pkotlinapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.14.0 契约：成员行带 isSelf；老后端缺少该字段时必须保持 null 以便降级。 */
class HouseholdDtoContractTest {

    private val adapter = ApiClient.moshi().adapter(HouseholdMemberDto::class.java)

    @Test
    fun `isSelf true is parsed for the current account row`() {
        val dto = adapter.fromJson(
            """
            {"userId":"user-1","role":"owner","kind":"self","joinedAt":"2026-01-01T00:00:00Z","isSelf":true}
            """.trimIndent(),
        )

        assertEquals("user-1", dto?.userId)
        assertEquals(true, dto?.isSelf)
    }

    @Test
    fun `isSelf false is preserved for other members`() {
        val dto = adapter.fromJson(
            """
            {"userId":"user-2","role":"member","isSelf":false}
            """.trimIndent(),
        )

        assertEquals("user-2", dto?.userId)
        assertEquals(false, dto?.isSelf)
    }

    @Test
    fun `missing isSelf stays null so old backends can be detected`() {
        val dto = adapter.fromJson("""{"userId":"user-1","role":"owner"}""")

        assertTrue(dto != null)
        assertNull(dto?.isSelf)
    }

    @Test
    fun `transfer request serializes only the target userId`() {
        val requestAdapter = ApiClient.moshi().adapter(HouseholdOwnershipTransferRequest::class.java)

        val json = requestAdapter.toJson(HouseholdOwnershipTransferRequest(userId = "user-2"))

        assertEquals("""{"userId":"user-2"}""", json)
    }
}
