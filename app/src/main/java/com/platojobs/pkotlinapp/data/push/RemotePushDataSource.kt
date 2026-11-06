package com.platojobs.pkotlinapp.data.push

import com.platojobs.pkotlinapp.data.remote.PushDeviceRegistrationRequest
import com.platojobs.pkotlinapp.data.remote.SyncApi
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.domain.push.PushRegisteredDevice
import com.platojobs.pkotlinapp.domain.push.PushRemote
import com.platojobs.pkotlinapp.domain.push.PushRemoteException
import retrofit2.HttpException
import java.io.IOException

/**
 * 真实 `POST/DELETE /push/devices` 数据源。
 *
 * 鉴权与同步接口共用同一 session token；会话不存在时视为认证失效，
 * 由上层等待下一次「会话就绪」再注册，而不是盲目重试。
 */
class RemotePushDataSource(
    private val api: SyncApi,
    private val tokenStore: TokenStore,
) : PushRemote {

    override suspend fun register(
        token: String,
        appVersion: String,
        deviceModel: String,
    ): PushRegisteredDevice {
        val authorization = bearerOrThrow()
        try {
            val response = api.registerPushDevice(
                authorization = authorization,
                body = PushDeviceRegistrationRequest(
                    platform = "android",
                    token = token,
                    appVersion = appVersion.takeIf { it.isNotBlank() },
                    deviceModel = deviceModel.takeIf { it.isNotBlank() },
                ),
            )
            val deviceId = response.id?.takeIf { it.isNotBlank() }
                ?: throw PushRemoteException.Retryable("推送注册响应缺少 deviceId")
            return PushRegisteredDevice(deviceId, response.status.orEmpty().ifBlank { "active" })
        } catch (e: HttpException) {
            throw e.toPushException(operation = "注册")
        } catch (e: IOException) {
            throw PushRemoteException.Retryable("网络不可用，推送注册稍后重试", e)
        }
    }

    override suspend fun unregister(remoteDeviceId: String) {
        // 会话已清空时没有鉴权能力，服务端注销只能尽力而为；本地仍会清理。
        val token = tokenStore.token()?.takeIf { it.isNotBlank() } ?: return
        try {
            api.deactivatePushDevice(
                authorization = "Bearer $token",
                deviceId = remoteDeviceId,
            )
        } catch (e: HttpException) {
            if (e.code() == 404) return
            throw e.toPushException(operation = "注销")
        } catch (e: IOException) {
            throw PushRemoteException.Retryable("网络不可用，推送注销稍后重试", e)
        }
    }

    private fun bearerOrThrow(): String {
        val token = tokenStore.token()?.takeIf { it.isNotBlank() }
            ?: throw PushRemoteException.AuthExpired("尚未登录，暂不注册推送")
        return "Bearer $token"
    }

    private fun HttpException.toPushException(operation: String): PushRemoteException = when (code()) {
        // 参数错误是终态：不重试，保留可读原因。
        400 -> PushRemoteException.Rejected(
            "${operation}推送设备被拒绝：${errorCodeOrMessage()}（HTTP 400）",
        )
        401, 403 -> PushRemoteException.AuthExpired("登录已失效，等待重新登录后${operation}推送")
        404 -> PushRemoteException.Rejected("推送设备不存在（HTTP 404）")
        in 500..599 -> PushRemoteException.Retryable(
            "服务端暂时不可用（HTTP ${code()}），稍后重试",
            this,
        )
        else -> PushRemoteException.Rejected("${operation}推送设备失败（HTTP ${code()}）")
    }

    /** 后端错误体形如 `{"code":"PUSH_DEVICE_INVALID","message":"..."}`。 */
    private fun HttpException.errorCodeOrMessage(): String {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull().orEmpty()
        val code = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
        val message = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
        return when {
            !code.isNullOrBlank() && !message.isNullOrBlank() -> "$code：$message"
            !code.isNullOrBlank() -> code
            !message.isNullOrBlank() -> message
            else -> "HTTP ${code()}"
        }
    }
}
