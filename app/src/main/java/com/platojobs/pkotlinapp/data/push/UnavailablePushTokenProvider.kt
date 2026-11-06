package com.platojobs.pkotlinapp.data.push

import com.platojobs.pkotlinapp.domain.push.PushTokenProvider
import com.platojobs.pkotlinapp.domain.push.PushTokenResult

/**
 * 默认实现：本工程没有 `google-services.json`，不接 FCM。
 *
 * 这里刻意不返回任何 token，注册流程会停在「未配置」状态；
 * 放入 google-services.json 并接入 Firebase Messaging 后，在
 * `AppContainer.createPushTokenProvider()` 切换到真实实现即可。
 */
class UnavailablePushTokenProvider : PushTokenProvider {
    override suspend fun fetchToken(): PushTokenResult = PushTokenResult.NotConfigured
}
