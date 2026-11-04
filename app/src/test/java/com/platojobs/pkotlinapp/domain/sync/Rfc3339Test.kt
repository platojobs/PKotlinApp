package com.platojobs.pkotlinapp.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rfc3339Test {

    @Test
    fun `parses go nanosecond timestamps and formats to millis`() {
        val millis = Rfc3339.parse("2026-11-05T01:00:00.123456789Z")

        assertTrue(millis != null)
        assertEquals(123L, millis!! % 1_000)
        assertEquals("2026-11-05T01:00:00.123Z", Rfc3339.format(millis))
    }

    @Test
    fun `parses positive timezone offset as the same instant`() {
        val utc = Rfc3339.parse("2026-11-05T01:00:00Z")
        val offset = Rfc3339.parse("2026-11-05T09:00:00+08:00")

        assertEquals(utc, offset)
    }

    @Test
    fun `returns null for blank or malformed values`() {
        assertNull(Rfc3339.parse(null))
        assertNull(Rfc3339.parse(""))
        assertNull(Rfc3339.parse("not-a-time"))
    }
}
