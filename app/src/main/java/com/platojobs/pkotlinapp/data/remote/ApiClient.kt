package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object ApiClient {

    fun create(baseUrl: String = BuildConfig.API_BASE_URL): ApiService =
        retrofit(baseUrl).create(ApiService::class.java)

    fun createSync(baseUrl: String = BuildConfig.API_BASE_URL): SyncApi =
        retrofit(baseUrl).create(SyncApi::class.java)

    /**
     * 共享 Moshi：先注册 [Reported] 三态 adapter，再交给 Kotlin 反射 adapter。
     * e2e 契约测试复用同一实例，避免测试与生产序列化行为漂移。
     */
    fun moshi(): Moshi = Moshi.Builder()
        .add(ReportedStringType, ReportedStringAdapter)
        .add(KotlinJsonAdapterFactory())
        .build()

    private fun retrofit(baseUrl: String): Retrofit {
        val moshi = moshi()

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi).asLenient())
            .build()
    }
}
