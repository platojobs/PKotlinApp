package com.platojobs.pkotlinapp.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

interface ApiService {

    @GET("api/device/{mac}")
    suspend fun getDeviceInfo(@Path("mac") mac: String): DeviceInfoDto

    @GET("api/products")
    suspend fun getProducts(): ProductsResponse

    @GET("api/market")
    suspend fun getMarketItems(): MarketResponse
}
