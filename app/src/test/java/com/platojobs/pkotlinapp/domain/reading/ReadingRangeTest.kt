package com.platojobs.pkotlinapp.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 区间 → bucket 映射与 500 桶上限。 */
class ReadingRangeTest {

    @Test
    fun `each range maps to the agreed bucket`() {
        assertEquals("1m", ReadingRange.HOUR_1.bucket)
        assertEquals("1h", ReadingRange.HOUR_24.bucket)
        assertEquals("1h", ReadingRange.DAY_7.bucket)
        assertEquals("1d", ReadingRange.DAY_30.bucket)
    }

    @Test
    fun `default range is twenty four hours`() {
        assertEquals(ReadingRange.HOUR_24, ReadingRange.DEFAULT)
    }

    @Test
    fun `all ranges stay within five hundred buckets`() {
        ReadingRange.entries.forEach { range ->
            assertTrue("${range.name} -> ${range.bucketCount()}", range.bucketCount() <= ReadingRange.MAX_BUCKETS)
        }
        assertEquals(60, ReadingRange.HOUR_1.bucketCount())
        assertEquals(24, ReadingRange.HOUR_24.bucketCount())
        assertEquals(168, ReadingRange.DAY_7.bucketCount())
        assertEquals(30, ReadingRange.DAY_30.bucketCount())
    }

    @Test
    fun `bucket auto coarsens when preferred granularity exceeds five hundred buckets`() {
        // 24h 用 1m 会产生 1440 桶 → 自动退到 1h（24 桶）。
        assertEquals("1h", ReadingRange.chooseBucket(24 * 3_600_000L, "1m"))
        // 7d 用 1m 超过上限 → 1h（168 桶）。
        assertEquals("1h", ReadingRange.chooseBucket(7 * 24 * 3_600_000L, "1m"))
        // 30d 用 1h 是 720 桶 → 退到 1d。
        assertEquals("1d", ReadingRange.chooseBucket(30 * 24 * 3_600_000L, "1h"))
    }

    @Test
    fun `bucket start aligns to utc grid`() {
        val timestamp = 1_760_003_723_456L
        assertEquals(1_759_968_000_000L, ReadingRange.bucketStart(timestamp, "1d"))
        assertEquals(1_760_000_400_000L, ReadingRange.bucketStart(timestamp, "1h"))
        assertEquals(1_760_003_700_000L, ReadingRange.bucketStart(timestamp, "1m"))
    }
}
