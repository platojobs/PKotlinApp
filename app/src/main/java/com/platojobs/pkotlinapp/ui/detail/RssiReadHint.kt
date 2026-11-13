package com.platojobs.pkotlinapp.ui.detail

import android.content.Context
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.RssiReadState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 详情页 RSSI 读数时间的展示文案；返回 null 表示该行不占位。
 *
 * - 正常：[读数时间 时间]
 * - 有旧值但刷新失败：[刷新失败，显示上次读数 · 时间]
 * - 从未成功过且失败：[读取失败，暂无读数]
 */
internal fun rssiUpdatedAtText(
    context: Context,
    timestamp: Long?,
    state: RssiReadState,
): String? {
    if (timestamp == null || timestamp <= 0L) {
        return if (state == RssiReadState.STALE) {
            context.getString(R.string.device_rssi_read_failed)
        } else {
            null
        }
    }
    val time = SimpleDateFormat(
        context.getString(R.string.time_format_time),
        Locale.getDefault(),
    ).format(Date(timestamp))
    return if (state == RssiReadState.STALE) {
        context.getString(R.string.device_rssi_stale_at, time)
    } else {
        context.getString(R.string.device_rssi_read_at, time)
    }
}
