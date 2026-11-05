package com.platojobs.pkotlinapp.data.sync

import com.platojobs.pkotlinapp.domain.reading.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 幂等键必须由整批读数内容（含 id）决定，重试与重排都复用同一个 key。 */
class ReadingIdempotencyKeysTest {

    private fun reading(
        id: String,
        metric: String = "temperature",
        value: Double = 21.5,
        recordedAt: Long = 1_760_000_000_000L,
    ) = Reading(
        id = id,
        deviceId = "AA:BB:CC:00:00:01",
        metric = metric,
        value = value,
        unit = "°C",
        recordedAt = recordedAt,
    )

    @Test
    fun `key is stable and prefixed with readings`() {
        val batch = listOf(reading("id-1"), reading("id-2"))
        val first = ReadingIdempotencyKeys.forBatch(batch)
        val second = ReadingIdempotencyKeys.forBatch(batch.reversed())
        assertEquals(first, second)
        assertTrue(first.startsWith("readings-"))
        assertEquals("readings-".length + 40, first.length)
    }

    @Test
    fun `key changes when id metric value or time changes`() {
        val base = ReadingIdempotencyKeys.forBatch(listOf(reading("id-1")))
        assertNotEquals(base, ReadingIdempotencyKeys.forBatch(listOf(reading("id-2"))))
        assertNotEquals(base, ReadingIdempotencyKeys.forBatch(listOf(reading("id-1", "heart_rate"))))
        assertNotEquals(base, ReadingIdempotencyKeys.forBatch(listOf(reading("id-1", value = 22.0))))
        assertNotEquals(
            base,
            ReadingIdempotencyKeys.forBatch(listOf(reading("id-1", recordedAt = 1_760_000_001_000L))),
        )
    }

    @Test
    fun `adding a reading changes the batch key`() {
        val one = ReadingIdempotencyKeys.forBatch(listOf(reading("id-1")))
        val two = ReadingIdempotencyKeys.forBatch(listOf(reading("id-1"), reading("id-2")))
        assertNotEquals(one, two)
    }
}
