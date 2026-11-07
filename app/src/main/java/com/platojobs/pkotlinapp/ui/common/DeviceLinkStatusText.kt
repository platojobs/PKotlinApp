package com.platojobs.pkotlinapp.ui.common

import android.content.Context
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.model.DeviceLinkStatus
import com.platojobs.pkotlinapp.domain.model.PLATFORM_ANDROID

/**
 * 四档设备状态文案的唯一来源：首页卡片、房间设备行、设备详情都必须调用这里，
 * 不允许各页面再写一套 `when`。超时档必须带出最近一次上报时间，
 * 避免只写「云端最近上报」而看不出已超出 15 分钟窗口。
 */
fun deviceLinkStatusText(
    context: Context,
    status: DeviceLinkStatus,
    lastSeenAt: Long?,
): String = when (status) {
    DeviceLinkStatus.LOCAL_CONNECTED ->
        context.getString(R.string.home_device_connected)

    DeviceLinkStatus.CLOUD_RECENT ->
        context.getString(R.string.home_device_cloud_recent)

    DeviceLinkStatus.CLOUD_STALE -> context.getString(
        R.string.home_device_cloud_stale,
        lastSeenAt?.let { TimeFormat.relative(context, it) }
            ?: context.getString(R.string.home_device_cloud_never),
    )

    DeviceLinkStatus.CLOUD_UNKNOWN ->
        context.getString(R.string.home_device_cloud_unknown)
}

/**
 * 本机不可连接：只有 Android 平台设备能被本机 BLE 直连；iOS 等跨平台设备只能看云端状态。
 *
 * 这是**附加维度**，不进入四档计数。presence 与平台是可正交的两个事实
 * （一台 iOS 设备也可能 online/offline/unknown），单独加第五档会与四档重复计数，
 * 因此首页统计与房间汇总仍保持四个互斥桶，本机不可连接以状态行后缀并列呈现。
 */
fun isLocalConnectUnavailable(platform: String?): Boolean {
    val value = platform?.trim()?.lowercase().orEmpty()
    return value.isNotEmpty() && value != PLATFORM_ANDROID
}

/** 跨平台设备的可读平台名；未知平台原样展示。 */
fun devicePlatformLabel(platform: String?): String = when (platform?.trim()?.lowercase()) {
    "ios" -> "iOS"
    "android" -> "Android"
    else -> platform?.trim().orEmpty()
}

/** 四档文案 + 本机不可连接后缀（仅非 Android 平台追加）。 */
fun deviceLinkStatusText(
    context: Context,
    status: DeviceLinkStatus,
    lastSeenAt: Long?,
    platform: String?,
): String {
    val base = deviceLinkStatusText(context, status, lastSeenAt)
    if (!isLocalConnectUnavailable(platform)) return base
    return context.getString(
        R.string.device_local_unavailable,
        base,
        devicePlatformLabel(platform),
    )
}
