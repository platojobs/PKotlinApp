package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.domain.model.HourCount

/**
 * 今日读数按小时分布的峰值小时。
 *
 * 数据库按 hourIndex 升序返回；若直接取首个最大值，并列时会把 0 点误报成峰值
 * （均匀造数下每个小时条数相同）。这里在并列时取最后出现的小时，并保持对
 * 未排序输入同样取“最晚”的并列项。
 */
fun List<HourCount>.peakHour(): HourCount? {
    var best: HourCount? = null
    for (hour in this) {
        val current = best
        if (current == null || hour.count > current.count ||
            (hour.count == current.count && hour.hourIndex > current.hourIndex)
        ) {
            best = hour
        }
    }
    return best
}
