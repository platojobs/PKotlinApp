package com.platojobs.pkotlinapp.domain.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 服务端 schedule 条件的本地调度：
 * - 春季拨快导致 02:30 不存在的日子按 Calendar 顺延到 03:30；
 * - 秋季拨慢导致 01:30 重复的日子同一天只触发一次；
 * - 星期过滤与下次触发的先后顺序。
 */
class SceneScheduleTest {

    private val losAngeles = "America/Los_Angeles"

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: String = losAngeles,
    ): Long = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
        clear()
        set(year, month, day, hour, minute, 0)
    }.timeInMillis

    private fun localParts(millis: Long, zone: String = losAngeles): Triple<Int, Int, Int> =
        Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { timeInMillis = millis }.let {
            Triple(it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
        }

    @Test
    fun `spring forward gap shifts 02-30 to 03-30`() {
        // 2026-03-08 02:00 被拨快，02:30 不存在。
        val now = at(2026, Calendar.MARCH, 7, 12, 0)
        val next = SceneSchedule.nextOccurrence(
            now, "02:30", losAngeles, emptySet(),
        )!!

        val (day, hour, minute) = localParts(next)
        assertEquals(8, day)
        assertEquals(3, hour)
        assertEquals(30, minute)
        assertTrue(next > now)
    }

    @Test
    fun `fall back repeated hour triggers once per day`() {
        // 2026-11-01 01:30 出现两次；同一天只保留一个触发实例。
        val now = at(2026, Calendar.OCTOBER, 31, 12, 0)
        val first = SceneSchedule.nextOccurrence(now, "01:30", losAngeles, emptySet())!!
        val second = SceneSchedule.nextOccurrence(first + 60_000L, "01:30", losAngeles, emptySet())!!

        val (firstDay, firstHour, firstMinute) = localParts(first)
        val (secondDay, secondHour, secondMinute) = localParts(second)
        assertEquals(1, firstDay)
        assertEquals(1, firstHour)
        assertEquals(30, firstMinute)
        // 同一天第二次解析被去重，下一次直接落到 11-02。
        assertEquals(2, secondDay)
        assertEquals(1, secondHour)
        assertEquals(30, secondMinute)
    }

    @Test
    fun `days of week filter skips non selected days`() {
        // 2026-03-02 是周一；只选周三（3），应跳到 03-04。
        val mondayNoon = at(2026, Calendar.MARCH, 2, 12, 0, "UTC")
        val next = SceneSchedule.nextOccurrence(
            mondayNoon, "08:00", "UTC", setOf(3),
        )!!
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = next
        }
        assertEquals(4, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, calendar.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `latest occurrence gates the daily trigger`() {
        val morning = at(2026, Calendar.MARCH, 2, 8, 30, "UTC")
        val latest = SceneSchedule.latestOccurrence(morning, "08:00", "UTC", emptySet())!!
        val sameDay = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = latest
        }
        assertEquals(2, sameDay.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, sameDay.get(Calendar.HOUR_OF_DAY))
        assertTrue(latest < morning)

        val before = at(2026, Calendar.MARCH, 2, 7, 59, "UTC")
        assertEquals(
            at(2026, Calendar.MARCH, 1, 8, 0, "UTC"),
            SceneSchedule.latestOccurrence(before, "08:00", "UTC", emptySet()),
        )
    }

    @Test
    fun `invalid timezone or time is rejected`() {
        assertTrue(SceneSchedule.isValid("08:00", "UTC", emptySet()))
        assertTrue(!SceneSchedule.isValid("8:00", "UTC", emptySet()))
        assertTrue(!SceneSchedule.isValid("25:00", "UTC", emptySet()))
        assertTrue(!SceneSchedule.isValid("08:00", "Mars/Olympus", emptySet()))
        assertTrue(!SceneSchedule.isValid("08:00", "UTC", setOf(0)))
    }
}
