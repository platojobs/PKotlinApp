package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.domain.model.DeviceLinkStatus
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import com.platojobs.pkotlinapp.ui.common.deviceLinkStatusText
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
 * 首页卡片、房间设备行、设备详情共用 [deviceLinkStatusText]，超时档必须带出最近一次
 * 上报时间；非 Android 平台还要追加「本机不可连接」，作为四档之外的附加维度。
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
    fun `four tiers share one mapping and tell stale from recent`() {
        assertEquals(
            "本机已连接",
            deviceLinkStatusText(context, DeviceLinkStatus.LOCAL_CONNECTED, null),
        )
        assertEquals(
            "云端 15 分钟内有上报",
            deviceLinkStatusText(context, DeviceLinkStatus.CLOUD_RECENT, WINDOW_EDGE),
        )
        assertEquals(
            "云端从未收到上报",
            deviceLinkStatusText(context, DeviceLinkStatus.CLOUD_UNKNOWN, null),
        )

        // 超时档不能只写「云端最近上报」：必须明确已超时，并带出最近一次上报时间。
        val staleAt = System.currentTimeMillis() - 21 * 60_000L
        val stale = deviceLinkStatusText(context, DeviceLinkStatus.CLOUD_STALE, staleAt)
        assertTrue(stale, stale.startsWith("云端上报已超时 · 最近上报 "))
        assertTrue(stale, stale.contains("21 分钟前"))
    }

    @Test
    fun `non android platform adds the local-connection note`() {
        val android = deviceLinkStatusText(
            context,
            DeviceLinkStatus.CLOUD_RECENT,
            WINDOW_EDGE,
            "android",
        )
        assertEquals("云端 15 分钟内有上报", android)

        val ios = deviceLinkStatusText(
            context,
            DeviceLinkStatus.CLOUD_RECENT,
            WINDOW_EDGE,
            "ios",
        )
        assertEquals("云端 15 分钟内有上报 · 本机不可连接（来自 iOS）", ios)

        // 平台说明只是附加维度，不能改变四档本身的文案。
        assertTrue(ios.startsWith(android))
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
        /** 窗口内的一个固定时刻，仅用于文案分支，不参与时间差计算。 */
        const val WINDOW_EDGE = 1_762_000_000_000L
    }
}
