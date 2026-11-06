package com.platojobs.pkotlinapp.data.auth

import com.platojobs.pkotlinapp.data.remote.AnonymousLoginResponse
import com.platojobs.pkotlinapp.data.remote.ApiClient
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.sync.Rfc3339
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/**
 * 刷新 token 的能力抽象。
 *
 * 返回新 token 表示刷新成功且已完成本地原子替换；返回 null 表示本轮刷新失败，
 * 是否进入重新登录态由实现决定（只有服务端明确 401/403 才置 reloginRequired，
 * 网络错误保持 token，等待下一次重试）。
 */
fun interface SessionRefresher {
    fun refresh(staleToken: String): String?
}

/**
 * `POST api/v1/auth/refresh`：后端会让旧 token 在同一事务内立即失效。
 *
 * 成功时把新 token + 到期时间一次性写入 [TokenStore]（[TokenStore.saveSession]
 * 是原子的），并发请求随后读到的就是新值；失败时区分「凭据失效」与「暂时不可达」。
 */
class HttpSessionRefresher(
    baseUrl: String,
    private val tokenStore: TokenStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : SessionRefresher {

    private val refreshUrl = baseUrl.trimEnd('/') + "/api/v1/auth/refresh"

    override fun refresh(staleToken: String): String? {
        val request = Request.Builder()
            .url(refreshUrl)
            .post("".toRequestBody(JSON))
            .header("Authorization", "Bearer $staleToken")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 401/403：token 明确失效；其它（5xx/429）保留 token 等下次重试。
                    if (response.code == 401 || response.code == 403) {
                        enterReloginRequired()
                    }
                    return null
                }
                val parsed = ApiClient.moshi()
                    .adapter(AnonymousLoginResponse::class.java)
                    .fromJson(response.body?.string().orEmpty())
                val fresh = parsed?.token?.takeIf { it.isNotBlank() }
                if (fresh == null) {
                    enterReloginRequired()
                    null
                } else {
                    tokenStore.saveSession(fresh, Rfc3339.parse(parsed.expiresAt))
                    tokenStore.setReloginRequired(false)
                    fresh
                }
            }
        } catch (_: IOException) {
            // 断网/超时不是凭据失效：保留 token，避免把用户误判成需要重新登录。
            null
        }
    }

    private fun enterReloginRequired() {
        tokenStore.clear()
        tokenStore.setReloginRequired(true)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/**
 * OkHttp 401 处理：先刷新，成功就用新 token 原样重放请求；失败才进入重新登录态。
 *
 * 单飞语义：所有并发 401 都进入同一把锁，只有第一个线程真正调用 [SessionRefresher]；
 * 其余线程在锁内看到 [TokenStore.token] 已不是自己请求携带的旧 token，直接重放，
 * 不会再发一次刷新请求。
 */
class TokenRefreshAuthenticator(
    private val tokenStore: TokenStore,
    private val refresher: SessionRefresher,
    private val onReloginRequired: () -> Unit = {},
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val stale = response.request.header(AUTHORIZATION)
            ?.removePrefix(BEARER_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        // 同一个请求已经重放过一次仍然 401：不再刷新，避免无限循环。
        if (response.priorResponse != null) return null
        if (tokenStore.reloginRequired()) {
            onReloginRequired()
            return null
        }
        synchronized(lock) {
            val current = tokenStore.token()?.takeIf { it.isNotBlank() }
            if (current != null && current != stale) {
                // 另一个并发请求已经刷新完成：直接用新 token 重放。
                return response.request.withBearer(current)
            }
            val fresh = refresher.refresh(stale)
            if (fresh == null) {
                if (tokenStore.reloginRequired()) onReloginRequired()
                return null
            }
            return response.request.withBearer(fresh)
        }
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header(AUTHORIZATION, "$BEARER_PREFIX$token").build()

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
