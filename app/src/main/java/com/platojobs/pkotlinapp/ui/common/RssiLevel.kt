package com.platojobs.pkotlinapp.ui.common

import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.model.UNKNOWN_RSSI
import kotlin.math.ceil

/**
 * RSSI → 信号档位的唯一实现。
 *
 * 阈值、边界与展示规则见 `docs/rssi-signal-tiers.md`（跨端规范）；
 * 需要信号档位的任何界面都应调用这里，不要另写一套阈值。
 */
object RssiLevel {

    /** 4 格制下最高档的格数，也是等比换算的基准。 */
    const val MAX_LEVEL = 4

    /** 各档下限（dBm，含）。判定按从高到低依次比较，见 [tier]。 */
    const val STRONG_MIN = -55
    const val GOOD_MIN = -66
    const val FAIR_MIN = -75
    const val WEAK_MIN = -85

    /**
     * 信号档位。`level` 为 0–4 的格数权重；[UNKNOWN] 与 [VERY_WEAK] 都是 0 格，
     * 但文案不同——前者表示没有数据，后者表示有数据但链路已近乎不可用。
     */
    enum class Tier(
        val level: Int,
        val descriptionRes: Int,
    ) {
        UNKNOWN(0, R.string.rssi_desc_unknown),
        VERY_WEAK(0, R.string.rssi_desc_very_weak),
        WEAK(1, R.string.rssi_desc_weak),
        FAIR(2, R.string.rssi_desc_fair),
        GOOD(3, R.string.rssi_desc_good),
        STRONG(4, R.string.rssi_desc_strong),
    }

    /**
     * 合法 RSSI 恒为负 dBm；`0`（云端占位）与 `127`（部分协议的最大值/占位）都不是真实读数。
     * 任何非负值都不参与分档，一律按未知处理，避免显示成满格。
     */
    fun isValid(rssi: Int?): Boolean = rssi != null && rssi != UNKNOWN_RSSI && rssi < 0

    /**
     * 任何 dBm 值都恰好落在一个档位：`null`、[UNKNOWN_RSSI] 与非负值均为未知，
     * 其余按 [STRONG_MIN] / [GOOD_MIN] / [FAIR_MIN] / [WEAK_MIN] 从高到低比较。
     */
    fun tier(rssi: Int?): Tier {
        if (rssi == null || !isValid(rssi)) return Tier.UNKNOWN
        return when {
            rssi >= STRONG_MIN -> Tier.STRONG
            rssi >= GOOD_MIN -> Tier.GOOD
            rssi >= FAIR_MIN -> Tier.FAIR
            rssi >= WEAK_MIN -> Tier.WEAK
            else -> Tier.VERY_WEAK
        }
    }

    fun level(rssi: Int?): Int = tier(rssi).level

    /** 按档位等比映射到任意格数：`ceil(level / 4 * barCount)`，0 档恒为 0 格。 */
    fun bars(tier: Tier, barCount: Int): Int {
        if (barCount <= 0) return 0
        val ratio = tier.level / MAX_LEVEL.toFloat()
        return ceil(ratio * barCount).toInt().coerceIn(0, barCount)
    }

    fun bars(rssi: Int?, barCount: Int): Int = bars(tier(rssi), barCount)

    fun descriptionRes(rssi: Int?): Int = tier(rssi).descriptionRes
}
