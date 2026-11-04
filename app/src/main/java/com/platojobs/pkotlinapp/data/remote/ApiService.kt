package com.platojobs.pkotlinapp.data.remote

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

interface ApiService {

    /** 公共设备元数据（无需鉴权）。 */
    @GET("api/v1/devices/{mac}")
    suspend fun getDeviceInfo(@Path("mac") mac: String): PublicDeviceDto

    /** 设备类型列表，产品与市场页的入口。 */
    @GET("api/v1/device-types")
    suspend fun getDeviceTypes(): DeviceTypesResponse

    /** 聚合型号元数据、固件与配件；取代分别拼接历史端点的做法。 */
    @GET("api/v1/device-types/{type}/catalog")
    suspend fun getDeviceCatalog(@Path("type") type: String): DeviceCatalogDto

    /**
     * 按 (platform, externalId) 查询当前用户设备。
     * Android 的 externalId 必须是规范化大写 MAC；旧 `/my/devices/{mac}` 客户端不再使用。
     */
    @GET("api/v1/my/devices/{platform}/{externalId}")
    suspend fun getMyDevice(
        @Header("Authorization") authorization: String,
        @Path("platform") platform: String,
        @Path("externalId") externalId: String,
    ): UserDeviceDto
}
