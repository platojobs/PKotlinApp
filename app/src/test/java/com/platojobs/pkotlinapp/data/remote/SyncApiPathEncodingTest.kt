package com.platojobs.pkotlinapp.data.remote

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * 锁住带冒号 MAC 的路径编码：Retrofit @Path 默认 encoded=false。
 * 冒号是合法 path 字符，OkHttp 保留为 `:`；无论保留还是编码成 `%3A`，都只能出现一次。
 * 若某处手工拼 URL 或做了二次编码，会出现 %253A，服务端解码后得到 %3A 并返 INVALID_MAC 400。
 */
class SyncApiPathEncodingTest {

    private fun api(capture: (HttpUrl) -> Unit): SyncApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            capture(chain.request().url)
            throw IOException("abort before network")
        }.build()
        return Retrofit.Builder()
            .baseUrl("http://127.0.0.1/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(ApiClient.moshi()).asLenient())
            .build()
            .create(SyncApi::class.java)
    }

    private fun capturedUrl(block: suspend (SyncApi) -> Unit): String {
        var captured: HttpUrl? = null
        val api = api { captured = it }
        runBlocking { runCatching { block(api) } }
        assertNotNull("request was not built", captured)
        return captured.toString()
    }

    @Test
    fun `metrics path keeps mac colon exactly once`() {
        val url = capturedUrl { it.readingsMetrics("Bearer t", "android", "AA:BB:CC:DD:EE:FF") }

        assertEquals(
            "http://127.0.0.1/api/v1/my/devices/android/AA:BB:CC:DD:EE:FF/readings/metrics",
            url,
        )
        assertFalse(url.contains("%25"))
        assertFalse(url.contains("%3A"))
    }

    @Test
    fun `latest reading query keeps limit and single mac colon`() {
        val url = capturedUrl {
            it.readings(
                authorization = "Bearer t",
                platform = "android",
                externalId = "AA:BB:CC:DD:EE:FF",
                metric = "temperature",
                from = null,
                to = null,
                limit = 1,
                cursor = null,
            )
        }

        assertEquals(
            "http://127.0.0.1/api/v1/my/devices/android/AA:BB:CC:DD:EE:FF/readings" +
                "?metric=temperature&limit=1",
            url,
        )
        assertFalse(url.contains("%25"))
        assertFalse(url.contains("%3A"))
    }

    @Test
    fun `ios external id needs no mac encoding`() {
        val url = capturedUrl { it.readingsMetrics("Bearer t", "ios", "1E2D3C4B-AAAA-BBBB-CCCC-DDDDEEEEFFFF") }

        assertEquals(
            "http://127.0.0.1/api/v1/my/devices/ios/1E2D3C4B-AAAA-BBBB-CCCC-DDDDEEEEFFFF/readings/metrics",
            url,
        )
    }
}
