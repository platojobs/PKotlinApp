package com.platojobs.pkotlinapp.data.reading

import com.platojobs.pkotlinapp.domain.reading.CloudReadingState
import com.platojobs.pkotlinapp.domain.reading.LatestReadingRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 云端最近读数的缓存与并发收敛层：
 * - 同一设备同一时刻只发一次请求（in-flight 合并），避免列表逐行重复请求；
 * - 成功/空/失败三种结果都短时缓存，[force] 可绕过（下拉刷新）；
 * - 全局最多 [maxConcurrency] 个请求并发，防止一次刷新打满服务端。
 */
class LatestReadingCache(
    private val remote: LatestReadingRemote,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MS,
    maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
) {

    private data class Entry(val state: CloudReadingState, val fetchedAt: Long)

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Entry>()
    private val inFlight = mutableMapOf<String, Deferred<CloudReadingState>>()
    private val gate = Semaphore(maxConcurrency.coerceAtLeast(1))

    suspend fun load(
        platform: String,
        externalId: String,
        force: Boolean = false,
    ): CloudReadingState {
        val normalizedPlatform = platform.trim().lowercase()
        // 服务端按 platform 区分 externalId；Android 必须是规范化大写 MAC。
        val normalizedExternalId = externalId.trim().uppercase()
        val key = "$normalizedPlatform|$normalizedExternalId"

        mutex.withLock { cache[key] }?.let { entry ->
            if (!force && now() - entry.fetchedAt < ttlMillis) return entry.state
        }

        val deferred = mutex.withLock {
            inFlight.getOrPut(key) {
                // LAZY 保证只启动一次；共享 Deferred 的生命周期由 [scope] 管理，
                // 单个调用方取消不会打断其他行的等待。
                scope.async(start = CoroutineStart.LAZY) {
                    fetch(normalizedPlatform, normalizedExternalId)
                }.also { it.start() }
            }
        }

        val state = deferred.await()
        mutex.withLock {
            cache[key] = Entry(state, now())
            if (inFlight[key] === deferred) inFlight.remove(key)
        }
        return state
    }

    private suspend fun fetch(platform: String, externalId: String): CloudReadingState =
        gate.withPermit {
            try {
                val reading = remote.latest(platform, externalId)
                if (reading == null) CloudReadingState.Empty else CloudReadingState.Available(reading)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CloudReadingState.Failed
            }
        }

    companion object {
        /** 5 分钟；房间列表/首页切换不重复打服务端，下拉刷新可绕过。 */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        const val DEFAULT_MAX_CONCURRENCY = 4
    }
}
