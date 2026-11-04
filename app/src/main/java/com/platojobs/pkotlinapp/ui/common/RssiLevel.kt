package com.platojobs.pkotlinapp.ui.common

import com.platojobs.pkotlinapp.R
import kotlin.math.ceil

/**
 * RSSI → 信号格数的纯函数，便于单元测试与 View 复用。
 */
object RssiLevel {

    fun level(rssi: Int?): Int = when {
        rssi == null -> 0
        rssi >= -55 -> 4
        rssi >= -66 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }

    fun bars(rssi: Int?, barCount: Int): Int {
        if (barCount <= 0) return 0
        val ratio = level(rssi) / 4f
        return ceil(ratio * barCount).toInt().coerceIn(0, barCount)
    }

    fun descriptionRes(rssi: Int?): Int = when (level(rssi)) {
        4 -> R.string.rssi_desc_strong
        3 -> R.string.rssi_desc_good
        2 -> R.string.rssi_desc_fair
        else -> if (rssi == null) R.string.rssi_desc_unknown else R.string.rssi_desc_weak
    }
}
