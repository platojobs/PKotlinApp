package com.platojobs.pkotlinapp.domain.push

/**
 * 推送令牌来源抽象。
 *
 * 真实实现（Firebase）应放在 `data/push`，接入步骤：
 * 1. 工程放入 `google-services.json`，根/模块 Gradle 启用 `com.google.gms.google-services`；
 * 2. 依赖 `com.google.firebase:firebase-messaging-ktx`；
 * 3. 新增 `FirebasePushTokenProvider`，用 `FirebaseMessaging.getInstance().token.await()`
 *    返回 [PushTokenResult.Available]，并把 FCM 的 token 刷新回调接到
 *    `PushRegistrationRepository.onTokenChanged()`；
 * 4. 在 `AppContainer.createPushTokenProvider()` 中替换默认的
 *    [com.platojobs.pkotlinapp.data.push.UnavailablePushTokenProvider]。
 *
 * 未完成以上配置前必须返回 [PushTokenResult.NotConfigured]，不允许用占位字符串冒充 token。
 */
interface PushTokenProvider {
    suspend fun fetchToken(): PushTokenResult
}

sealed interface PushTokenResult {
    /** 拿到真实可注册的令牌。 */
    data class Available(val token: String) : PushTokenResult

    /** 未配置推送服务（缺 google-services.json / 未接入 FCM）。 */
    data object NotConfigured : PushTokenResult

    /** 取令牌失败（可重试），reason 为可读原因。 */
    data class Failed(val reason: String) : PushTokenResult
}
