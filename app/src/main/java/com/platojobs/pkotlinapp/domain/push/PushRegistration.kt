package com.platojobs.pkotlinapp.domain.push

/** `push_devices` 行状态；同时用于服务端 `active/inactive` 的本地映射。 */
enum class PushDeviceStatus(val stored: String) {
    /** 未配置推送服务，仅用于如实记录当前环境，不包含 token。 */
    UNCONFIGURED("unconfigured"),

    /** 已拿到 token 但尚未注册成功（等待重试）。 */
    PENDING("pending"),

    /** 服务端已注册（active）。 */
    REGISTERED("registered"),

    /** 服务端明确拒绝（400 等参数错误），不自动重试。 */
    REJECTED("rejected"),

    /** 已注销或 token 被轮换，仅保留历史映射。 */
    INACTIVE("inactive");

    companion object {
        fun fromStored(value: String?): PushDeviceStatus =
            entries.firstOrNull { it.stored == value } ?: PENDING
    }
}

/** 本地 `push_devices` 一行的领域模型。 */
data class PushDeviceRecord(
    /** 真实 FCM/APNs 令牌；未配置状态使用空串且不会发给服务端。 */
    val token: String,
    val remoteDeviceId: String? = null,
    val status: PushDeviceStatus = PushDeviceStatus.PENDING,
    val lastError: String? = null,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** 界面消费的注册状态；注意 [notificationsGranted] 与注册结果相互独立。 */
data class PushRegistrationState(
    val status: PushRegistrationStatus = PushRegistrationStatus.IDLE,
    val remoteDeviceId: String? = null,
    val lastError: String? = null,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    /** 系统通知权限；拒绝只影响通知展示，不影响注册结果。 */
    val notificationsGranted: Boolean = true,
)

enum class PushRegistrationStatus {
    /** 尚未登录或尚未运行注册流程。 */
    IDLE,

    /** 未配置推送服务，不虚构 token。 */
    NOT_CONFIGURED,

    /** 已拿到 token，正在等待重试。 */
    PENDING,

    /** 服务端已注册。 */
    REGISTERED,

    /** 服务端拒绝（参数错误等终态）。 */
    REJECTED,

    /** 已注销（退出登录/删除账号前调用 DELETE）。 */
    INACTIVE,
}

data class PushRegisteredDevice(val id: String, val status: String)

/** 服务端推送设备接口。 */
interface PushRemote {
    /** 注册/幂等更新；返回服务端记录 id。 */
    suspend fun register(token: String, appVersion: String, deviceModel: String): PushRegisteredDevice

    /** 置 inactive；404 视为已不存在，直接忽略。 */
    suspend fun unregister(remoteDeviceId: String)
}

sealed class PushRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 400 等参数错误：终态，不重试。 */
    class Rejected(val reason: String) : PushRemoteException(reason)

    /** 网络/5xx：指数退避后重试。 */
    class Retryable(val reason: String, cause: Throwable? = null) : PushRemoteException(reason, cause)

    /** 401/403：等待重新登录后由会话就绪事件再次注册。 */
    class AuthExpired(val reason: String) : PushRemoteException(reason)
}
