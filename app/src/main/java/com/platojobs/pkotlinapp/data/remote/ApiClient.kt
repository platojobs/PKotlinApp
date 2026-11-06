package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.BuildConfig
import com.platojobs.pkotlinapp.data.auth.HttpSessionRefresher
import com.platojobs.pkotlinapp.data.auth.TokenRefreshAuthenticator
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object ApiClient {

    fun create(
        baseUrl: String = BuildConfig.API_BASE_URL,
        tokenStore: TokenStore? = null,
        onReloginRequired: () -> Unit = {},
    ): ApiService = retrofit(baseUrl, tokenStore, onReloginRequired).create(ApiService::class.java)

    fun createSync(
        baseUrl: String = BuildConfig.API_BASE_URL,
        tokenStore: TokenStore? = null,
        onReloginRequired: () -> Unit = {},
    ): SyncApi = retrofit(baseUrl, tokenStore, onReloginRequired).create(SyncApi::class.java)

    fun createScenes(
        baseUrl: String = BuildConfig.API_BASE_URL,
        tokenStore: TokenStore? = null,
        onReloginRequired: () -> Unit = {},
    ): SceneApi = retrofit(baseUrl, tokenStore, onReloginRequired).create(SceneApi::class.java)

    /**
     * 共享 Moshi：先注册 [Reported] 三态 adapter，再交给 Kotlin 反射 adapter。
     * e2e 契约测试复用同一实例，避免测试与生产序列化行为漂移。
     */
    fun moshi(): Moshi = Moshi.Builder()
        .add(ReportedStringType, ReportedStringAdapter)
        .add(KotlinJsonAdapterFactory())
        .build()

    private fun retrofit(
        baseUrl: String,
        tokenStore: TokenStore?,
        onReloginRequired: () -> Unit,
    ): Retrofit {
        val moshi = moshi()

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
        if (tokenStore != null) {
            // 401：先单飞刷新再重放；刷新失败才进入重新登录态（不自动匿名登录）。
            builder.authenticator(
                TokenRefreshAuthenticator(
                    tokenStore = tokenStore,
                    refresher = HttpSessionRefresher(baseUrl, tokenStore),
                    onReloginRequired = onReloginRequired,
                ),
            )
        }
        val client = builder.build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi).asLenient())
            .build()
    }
}
