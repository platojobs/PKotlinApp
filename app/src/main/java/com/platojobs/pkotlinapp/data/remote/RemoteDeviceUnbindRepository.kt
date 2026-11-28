package com.platojobs.pkotlinapp.data.remote

import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.repository.DeviceUnbindRepository
import retrofit2.HttpException

/**
 * 解绑本人设备。
 *
 * 204（本次软删）与 404（早已不存在）都按成功返回，调用方统一清理本地行；
 * 其余状态码抛 [HttpException]，由 UI 提示失败并保留本地数据。
 */
class RemoteDeviceUnbindRepository(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) : DeviceUnbindRepository {

    override suspend fun deleteOwnedDevice(platform: String, externalId: String) {
        val token = tokenStore.token()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("尚未登录，无法移除设备")
        val response = api.deleteMyDevice("Bearer $token", platform, externalId)
        if (response.isSuccessful || response.code() == 404) return
        throw HttpException(response)
    }
}
