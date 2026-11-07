package com.platojobs.pkotlinapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * openapi 0.21.0 presence 口径：
 * - 服务端窗口固定 900s，`now - lastSeenAt == 900s` 仍算 online；
 * - null 表示从未上报，是独立的 unknown，不并入 offline；
 * - 本机 BLE 连接是最强证据，优先于服务端 presence。
 */
class DevicePresenceTest {

    private val now = 1_762_000_000_000L

    private fun device(mac: String, lastSeenAt: Long?) = Device(
        mac = mac,
        lastRssi = -60,
        lastSeenTimestamp = now,
        lastSeenAt = lastSeenAt,
    )

    @Test
    fun `presence derives three states from lastSeenAt`() {
        assertEquals(DevicePresence.UNKNOWN, presenceOf(null, now))
        assertEquals(DevicePresence.ONLINE, presenceOf(now - 1_000L, now))
        assertEquals(DevicePresence.OFFLINE, presenceOf(now - 901_000L, now))
    }

    @Test
    fun `window boundary is inclusive at exactly 900 seconds`() {
        assertEquals(DevicePresence.ONLINE, presenceOf(now - PRESENCE_WINDOW_MS, now))
        assertEquals(DevicePresence.OFFLINE, presenceOf(now - PRESENCE_WINDOW_MS - 1, now))
    }

    @Test
    fun `unknown is not reported as offline`() {
        val status = deviceLinkStatus(device("AA:BB", lastSeenAt = null), null, now)
        assertEquals(DeviceLinkStatus.CLOUD_UNKNOWN, status)
    }

    @Test
    fun `local connection wins over server presence`() {
        // 服务端已判定超时，但本机蓝牙连着：仍以本机连接为准。
        val stale = device("AA:BB", lastSeenAt = now - 3_600_000L)
        assertEquals(DeviceLinkStatus.LOCAL_CONNECTED, deviceLinkStatus(stale, "AA:BB", now))

        // 服务端 15 分钟内有上报，但本机未连接：不能假装本机在线。
        val recent = device("CC:DD", lastSeenAt = now - 1_000L)
        assertEquals(DeviceLinkStatus.CLOUD_RECENT, deviceLinkStatus(recent, "AA:BB", now))
    }

    @Test
    fun `other device connection does not mark this device as locally connected`() {
        val other = device("EE:FF", lastSeenAt = null)
        assertEquals(DeviceLinkStatus.CLOUD_UNKNOWN, deviceLinkStatus(other, "AA:BB", now))
    }

    @Test
    fun `detail overload follows the same four-state rule`() {
        // 详情页只有 lastSeenAt 与连接状态，必须与列表/房间得到同一档位。
        assertEquals(
            DeviceLinkStatus.CLOUD_RECENT,
            deviceLinkStatus(now - PRESENCE_WINDOW_MS, locallyConnected = false, now = now),
        )
        assertEquals(
            DeviceLinkStatus.CLOUD_STALE,
            deviceLinkStatus(now - PRESENCE_WINDOW_MS - 1, locallyConnected = false, now = now),
        )
        assertEquals(
            DeviceLinkStatus.CLOUD_UNKNOWN,
            deviceLinkStatus(null, locallyConnected = false, now = now),
        )
        assertEquals(
            DeviceLinkStatus.LOCAL_CONNECTED,
            deviceLinkStatus(null, locallyConnected = true, now = now),
        )
    }

    @Test
    fun `connected mac comparison ignores case`() {
        val device = device("aa:bb", lastSeenAt = null)
        assertEquals(DeviceLinkStatus.LOCAL_CONNECTED, deviceLinkStatus(device, "AA:BB", now))
    }
}
