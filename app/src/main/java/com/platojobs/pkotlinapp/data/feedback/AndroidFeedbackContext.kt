package com.platojobs.pkotlinapp.data.feedback

import android.os.Build
import com.platojobs.pkotlinapp.BuildConfig
import com.platojobs.pkotlinapp.domain.feedback.FeedbackContext

/**
 * 提交上下文自动填充，禁止硬编码：
 * - appVersion ← BuildConfig.VERSION_NAME
 * - buildType ← BuildConfig.BUILD_TYPE
 * - osVersion ← Android 系统版本
 * - deviceModel ← Build.MODEL
 */
fun androidFeedbackContext(): FeedbackContext = FeedbackContext(
    platform = "android",
    appVersion = BuildConfig.VERSION_NAME,
    buildType = BuildConfig.BUILD_TYPE,
    osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    deviceModel = Build.MODEL?.takeIf { it.isNotBlank() } ?: "unknown",
)
