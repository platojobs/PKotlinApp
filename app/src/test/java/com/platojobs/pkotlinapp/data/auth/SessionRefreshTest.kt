package com.platojobs.pkotlinapp.data.auth

import com.platojobs.pkotlinapp.data.sync.TokenStore
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 401 处理链路：
 * - 401 → 单飞刷新 → 用新 token 原样重放，原请求成功；
 * - 刷新失败 → 清 token + reloginRequired，界面进入「需要重新登录」；
 * - 并发 5 个 401 只发起 1 次刷新。
 */
class SessionRefreshTest {

    @Test
    fun `401 refreshes once then replays the original request`() {
        val store = FakeTokenStore("stale")
        val refreshCalls = AtomicInteger()
        val authenticator = TokenRefreshAuthenticator(
            tokenStore = store,
            refresher = SessionRefresher { stale ->
                assertEquals("stale", stale)
                refreshCalls.incrementAndGet()
                "fresh"
            },
        )
        // 401 必须由真实网络响应触发，OkHttp 才会调用 Authenticator 并重放请求。
        val server = TinyAuthServer(unauthorizedOnFirst = true)
        try {
            val client = OkHttpClient.Builder().authenticator(authenticator).build()
            client.newCall(
                Request.Builder()
                    .url(server.url("/device"))
                    .header("Authorization", "Bearer stale")
                    .build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
                // 重放的仍是同一个请求，只替换了 Authorization。
                assertEquals("Bearer fresh", response.request.header("Authorization"))
                assertEquals("/device", response.request.url.encodedPath)
            }
        } finally {
            server.close()
        }
        assertEquals(1, refreshCalls.get())
        assertEquals(listOf("Bearer stale", "Bearer fresh"), server.seenAuth)
    }

    @Test
    fun `refresh failure enters relogin state and keeps no silent anonymous login`() {
        val store = FakeTokenStore("stale")
        var notified = 0
        val authenticator = TokenRefreshAuthenticator(
            tokenStore = store,
            refresher = SessionRefresher {
                // 与 HttpSessionRefresher 对 401 的处理一致：清 token 并标记需要重新登录。
                store.clear()
                store.setReloginRequired(true)
                null
            },
            onReloginRequired = { notified++ },
        )
        val server = TinyAuthServer(unauthorizedOnFirst = false)
        try {
            val client = OkHttpClient.Builder().authenticator(authenticator).build()
            client.newCall(
                Request.Builder()
                    .url(server.url("/device"))
                    .header("Authorization", "Bearer stale")
                    .build(),
            ).execute().use { response ->
                assertEquals(401, response.code)
            }
        } finally {
            server.close()
        }
        assertNull(store.token())
        assertTrue(store.reloginRequired())
        assertEquals(1, notified)
        assertEquals(listOf("Bearer stale"), server.seenAuth)
    }

    @Test
    fun `five concurrent 401s trigger exactly one refresh`() {
        val store = FakeTokenStore("stale")
        val refreshCalls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authenticator = TokenRefreshAuthenticator(
            tokenStore = store,
            refresher = SessionRefresher {
                refreshCalls.incrementAndGet()
                started.countDown()
                // 持锁期间其它线程在 synchronized 上等待，不会重复刷新。
                release.await(5, TimeUnit.SECONDS)
                store.saveSession("fresh", 1L)
                "fresh"
            },
        )
        val unauthorized = staleResponse("stale")
        val pool = Executors.newFixedThreadPool(5)
        val results = java.util.Collections.synchronizedList(mutableListOf<Request?>())
        val gate = CountDownLatch(1)
        try {
            repeat(5) {
                pool.execute {
                    gate.await()
                    results += authenticator.authenticate(null, unauthorized)
                }
            }
            gate.countDown()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, refreshCalls.get())
        assertEquals(5, results.size)
        results.forEach { retried ->
            assertNotNull(retried)
            assertEquals("Bearer fresh", retried!!.header("Authorization"))
        }
    }

    @Test
    fun `http refresher replaces token atomically and clears relogin flag`() {
        val store = FakeTokenStore("stale").apply { setReloginRequired(true) }
        val refresher = HttpSessionRefresher(
            baseUrl = "http://127.0.0.1",
            tokenStore = store,
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"userId":"u-1","token":"fresh","expiresAt":"2026-01-01T00:00:00Z"}"""
                                .toResponseBody("application/json".toMediaType()),
                        )
                        .build()
                }
                .build(),
        )

        val fresh = refresher.refresh("stale")

        assertEquals("fresh", fresh)
        assertEquals("fresh", store.token())
        assertNotNull(store.sessionExpiresAt())
        assertFalse(store.reloginRequired())
    }

    @Test
    fun `http refresher marks relogin only when server rejects the token`() {
        val store = FakeTokenStore("stale")
        val refresher = HttpSessionRefresher(
            baseUrl = "http://127.0.0.1",
            tokenStore = store,
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("".toResponseBody(null))
                        .build()
                }
                .build(),
        )

        assertNull(refresher.refresh("stale"))
        assertNull(store.token())
        assertTrue(store.reloginRequired())
    }

    @Test
    fun `http refresher keeps token on network failure`() {
        val store = FakeTokenStore("stale")
        val refresher = HttpSessionRefresher(
            baseUrl = "http://127.0.0.1",
            tokenStore = store,
            client = OkHttpClient.Builder()
                .addInterceptor { throw IOException("offline") }
                .build(),
        )

        assertNull(refresher.refresh("stale"))
        assertEquals("stale", store.token())
        assertFalse(store.reloginRequired())
    }

    private fun staleResponse(stale: String): Response {
        val request = Request.Builder()
            .url("http://127.0.0.1/device")
            .header("Authorization", "Bearer $stale")
            .build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .header("WWW-Authenticate", "Bearer realm=\"test\"")
            .body("".toResponseBody(null))
            .build()
    }

    /**
     * 极小的 HTTP 服务：第一条请求 401（带 WWW-Authenticate），重放后 200。
     * 用真实连接才能让 OkHttp 的 Authenticator/重放链路生效。
     */
    private class TinyAuthServer(private val unauthorizedOnFirst: Boolean) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val seenAuth = java.util.Collections.synchronizedList(mutableListOf<String>())

        private val worker = thread(isDaemon = true, name = "tiny-auth-server") {
            runCatching {
                var served = 0
                while (true) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            if (line!!.startsWith("Authorization:")) {
                                seenAuth += line.substringAfter(':').trim()
                            }
                            line = reader.readLine()
                        }
                        val code = when {
                            served == 0 && unauthorizedOnFirst -> 401
                            unauthorizedOnFirst -> 200
                            else -> 401
                        }
                        writeResponse(socket.getOutputStream(), code)
                    }
                    served++
                    if ((unauthorizedOnFirst && served >= 2) || (!unauthorizedOnFirst && served >= 1)) {
                        break
                    }
                }
            }
        }

        fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

        override fun close() {
            runCatching { server.close() }
            worker.join(5_000)
        }

        private fun writeResponse(out: java.io.OutputStream, code: Int) {
            val reason = if (code == 200) "OK" else "Unauthorized"
            val authHeader = if (code == 401) "WWW-Authenticate: Bearer realm=\"test\"\r\n" else ""
            val body = if (code == 200) "ok" else ""
            out.write(
                (
                    "HTTP/1.1 $code $reason\r\n" +
                        authHeader +
                        "Content-Length: ${body.length}\r\n" +
                        "Connection: close\r\n\r\n" +
                        body
                    ).toByteArray(),
            )
            out.flush()
        }
    }

    private class FakeTokenStore(initial: String?) : TokenStore {
        private var value: String? = initial
        private var expires: Long? = null
        private var relogin = false

        @Synchronized
        override fun token(): String? = value

        override fun saveToken(token: String) {
            value = token
        }

        @Synchronized
        override fun saveSession(token: String, expiresAt: Long?) {
            value = token
            expires = expiresAt
        }

        override fun sessionExpiresAt(): Long? = expires
        override fun reloginRequired(): Boolean = relogin
        override fun setReloginRequired(required: Boolean) {
            relogin = required
        }

        @Synchronized
        override fun clear() {
            value = null
            expires = null
        }
    }
}
