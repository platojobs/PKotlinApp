package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.model.DeviceLinkStatus
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 锁住四档 presence 文案与 `lastSeenAt` 时间显示。
 *
 * 首页与房间详情必须使用同一套四档口径；超时档必须带出最近一次上报时间，
 * 服务端接收时间要以 `MM-dd HH:mm` 具体时刻展示，避免只剩「在线/离线」的模糊判断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN")
class DevicePresenceCopyTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun fixLocaleAndZone() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @Test
    fun `home status copy tells the four tiers apart`() {
        assertEquals(
            "本机已连接",
            homeDeviceStatusText(context, DeviceLinkStatus.LOCAL_CONNECTED, null),
        )
        assertEquals(
            "云端 15 分钟内有上报",
            homeDeviceStatusText(context, DeviceLinkStatus.CLOUD_RECENT, WINDOW_EDGE),
        )
        assertEquals(
            "云端从未收到上报",
            homeDeviceStatusText(context, DeviceLinkStatus.CLOUD_UNKNOWN, null),
        )

        // 超时档不能只写「离线」：必须带出最近一次上报的相对时间。
        val staleAt = System.currentTimeMillis() - 21 * 60_000L
        val stale = homeDeviceStatusText(context, DeviceLinkStatus.CLOUD_STALE, staleAt)
        assertTrue(stale, stale.startsWith("云端最近上报 "))
        assertTrue(stale, stale.contains("21 分钟前"))
    }

    @Test
    fun `room detail tiers reuse the same four-state copy`() {
        assertEquals(
            "本机已连接",
            context.getString(roomDeviceStatusTextRes(DeviceLinkStatus.LOCAL_CONNECTED)),
        )
        assertEquals(
            "云端 15 分钟内有上报",
            context.getString(roomDeviceStatusTextRes(DeviceLinkStatus.CLOUD_RECENT)),
        )
        assertEquals(
            "云端上报已超时",
            context.getString(roomDeviceStatusTextRes(DeviceLinkStatus.CLOUD_STALE)),
        )
        assertEquals(
            "云端从未收到上报",
            context.getString(roomDeviceStatusTextRes(DeviceLinkStatus.CLOUD_UNKNOWN)),
        )
    }

    @Test
    fun `last seen line shows relative time and absolute server receive time`() {
        val at = System.currentTimeMillis() - 21 * 60_000L
        val absolute = formatLastSeenTimestamp(context, at)
        val expected = SimpleDateFormat("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(at))
        assertEquals(expected, absolute)

        val line = context.getString(
            R.string.room_detail_last_seen,
            TimeFormat.relative(context, at),
            absolute,
        )
        assertTrue(line, line.contains("21 分钟前"))
        assertTrue(line, line.contains(absolute))
        assertTrue(line, line.contains("服务端接收时间"))
    }

    private companion object {
        /** 900 秒窗口的右端点：仍应算 online。 */
        const val WINDOW_EDGE = 1_762_000_000_000L
    }
}
