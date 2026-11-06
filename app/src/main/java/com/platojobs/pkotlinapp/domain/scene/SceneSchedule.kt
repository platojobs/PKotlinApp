package com.platojobs.pkotlinapp.domain.scene

import java.util.Calendar
import java.util.TimeZone

/**
 * 服务端 `schedule` 条件的本地调度：HH:MM + IANA 时区 + ISO 星期（1=周一）。
 *
 * 不使用 java.time（minSdk 24 上不可用），改用 [Calendar]：
 * - 夏令时「跳过的时刻」（春季拨快）按 Calendar 的宽容解析顺延到有效时刻（如 02:30 → 03:30）；
 * - 「重复的时刻」（秋季拨慢）取 Calendar 解析出的标准时实例，同一天只触发一次。
 * 服务端只存配置，真正判定在客户端，避免服务端 cron 与时区/DST 语义漂移。
 */
object SceneSchedule {

    private val timeOfDayRegex = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

    fun isValid(timeOfDay: String, timezone: String, daysOfWeek: Set<Int>): Boolean {
        if (!timeOfDayRegex.matches(timeOfDay)) return false
        if (daysOfWeek.any { it !in 1..7 }) return false
        return isKnownTimeZone(timezone)
    }

    fun isKnownTimeZone(timezone: String): Boolean =
        timezone.isNotBlank() &&
            TimeZone.getTimeZone(timezone).id == timezone &&
            timezone in TimeZone.getAvailableIDs()

    /** 最近一次已到达的触发时刻；用于定时 Worker 判断「今天/本周这一次是否已执行」。 */
    fun latestOccurrence(
        nowMillis: Long,
        timeOfDay: String,
        timezone: String,
        daysOfWeek: Set<Int>,
    ): Long? = occurrences(nowMillis, timeOfDay, timezone, daysOfWeek)
        .lastOrNull { it <= nowMillis }

    /** 下一次尚未到达的触发时刻；界面与测试使用。 */
    fun nextOccurrence(
        nowMillis: Long,
        timeOfDay: String,
        timezone: String,
        daysOfWeek: Set<Int>,
    ): Long? = occurrences(nowMillis, timeOfDay, timezone, daysOfWeek)
        .firstOrNull { it > nowMillis }

    /**
     * 候选时刻：从 [nowMillis] 所在本地日期往前 7 天到往后 8 天
     * （覆盖「今天已过」与「每周一次」的最近一次/下一次），共 16 天。
     * 同一天只生成一个实例，避免 DST 重叠时重复触发。
     */
    private fun occurrences(
        nowMillis: Long,
        timeOfDay: String,
        timezone: String,
        daysOfWeek: Set<Int>,
    ): List<Long> {
        if (!isValid(timeOfDay, timezone, daysOfWeek)) return emptyList()
        val hour = timeOfDay.substringBefore(':').toInt()
        val minute = timeOfDay.substringAfter(':').toInt()
        val zone = TimeZone.getTimeZone(timezone)
        val days = if (daysOfWeek.isEmpty()) (1..7).toSet() else daysOfWeek

        val calendar = Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        val result = mutableListOf<Long>()
        for (offset in -7..8) {
            val candidate = Calendar.getInstance(zone).apply {
                clear()
                set(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH) + offset,
                    hour,
                    minute,
                    0,
                )
            }
            val isoDay = ((candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            if (isoDay in days) result += candidate.timeInMillis
        }
        return result.distinct().sorted()
    }
}
