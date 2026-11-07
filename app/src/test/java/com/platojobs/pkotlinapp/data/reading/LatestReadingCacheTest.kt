package com.platojobs.pkotlinapp.data.reading

import com.platojobs.pkotlinapp.domain.reading.CloudReading
import com.platojobs.pkotlinapp.domain.reading.CloudReadingState
import com.platojobs.pkotlinapp.domain.reading.LatestReadingRemote
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReadingCacheTest {

    private class FakeRemote : LatestReadingRemote {
        val calls = mutableMapOf<String, Int>()
        var failKeys = emptySet<String>()
        var emptyKeys = emptySet<String>()
        var inFlight = 0
        var maxInFlight = 0
        var delayMs = 20L

        override suspend fun latest(platform: String, externalId: String): CloudReading? {
            val key = "$platform|$externalId"
            calls[key] = (calls[key] ?: 0) + 1
            inFlight++
            maxInFlight = max(maxInFlight, inFlight)
            try {
                delay(delayMs)
                if (key in failKeys) throw IOException("boom")
                if (key in emptyKeys) return null
                return CloudReading(
                    metric = "temperature",
                    value = 23.5,
                    unit = "℃",
                    recordedAt = 1_700_000_000_000L,
                )
            } finally {
                inFlight--
            }
        }
    }

    @Test
    fun `available empty and failed map to distinct states`() = runTest {
        val remote = FakeRemote()
        val cache = LatestReadingCache(remote = remote, scope = this, now = { 0L })

        val available = cache.load("android", "aa:bb")
        remote.emptyKeys = setOf("android|CC:DD")
        val empty = cache.load("android", "cc:dd")
        remote.failKeys = setOf("android|EE:FF")
        val failed = cache.load("android", "ee:ff")

        assertTrue(available is CloudReadingState.Available)
        assertEquals(23.5, (available as CloudReadingState.Available).reading.value, 0.001)
        assertEquals(CloudReadingState.Empty, empty)
        assertEquals(CloudReadingState.Failed, failed)
    }

    @Test
    fun `concurrent loads for the same device share one request`() = runTest {
        val remote = FakeRemote()
        val cache = LatestReadingCache(remote = remote, scope = this, now = { 0L })

        val jobs = List(20) { launch { cache.load("android", "aa:bb") } }
        jobs.forEach { it.join() }

        assertEquals(1, remote.calls["android|AA:BB"])
    }

    @Test
    fun `cache hit avoids a second request until ttl expires`() = runTest {
        val remote = FakeRemote()
        var clock = 0L
        val cache = LatestReadingCache(
            remote = remote,
            scope = this,
            now = { clock },
            ttlMillis = 1_000L,
        )

        cache.load("android", "aa:bb")
        cache.load("android", "aa:bb")
        assertEquals(1, remote.calls["android|AA:BB"])

        clock = 1_001L
        cache.load("android", "aa:bb")
        assertEquals(2, remote.calls["android|AA:BB"])
    }

    @Test
    fun `force bypasses cache for pull to refresh`() = runTest {
        val remote = FakeRemote()
        val cache = LatestReadingCache(remote = remote, scope = this, now = { 0L })

        cache.load("android", "aa:bb")
        cache.load("android", "aa:bb", force = true)

        assertEquals(2, remote.calls["android|AA:BB"])
    }

    @Test
    fun `global concurrency is capped across devices`() = runTest {
        val remote = FakeRemote().apply { delayMs = 100L }
        val cache = LatestReadingCache(
            remote = remote,
            scope = this,
            now = { 0L },
            maxConcurrency = 2,
        )

        val jobs = List(6) { index ->
            launch { cache.load("android", "AA:BB:CC:DD:EE:0$index") }
        }
        jobs.forEach { it.join() }

        assertEquals(6, remote.calls.size)
        assertTrue("max in flight was ${remote.maxInFlight}", remote.maxInFlight <= 2)
    }
}
