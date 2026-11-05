package com.platojobs.pkotlinapp.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `告警开关与节流窗口可持久化`() {
        val settings = AppSettings(context)
        assertTrue(settings.alertsEnabled)
        assertEquals(AppSettings.DEFAULT_ALERT_THROTTLE_MINUTES, settings.alertThrottleMinutes)

        settings.alertsEnabled = false
        settings.alertThrottleMinutes = 30

        val reopened = AppSettings(context)
        assertFalse(reopened.alertsEnabled)
        assertEquals(30, reopened.alertThrottleMinutes)
    }

    @Test
    fun `非法节流值回落到默认五分钟`() {
        val settings = AppSettings(context)
        settings.alertThrottleMinutes = 7
        assertEquals(AppSettings.DEFAULT_ALERT_THROTTLE_MINUTES, settings.alertThrottleMinutes)
        assertEquals(listOf(1, 5, 30), AppSettings.ALERT_THROTTLE_OPTIONS)
    }

    @Test
    fun `设备排序方式持久化且默认最近出现`() {
        val settings = AppSettings(context)
        assertEquals(DeviceSortMode.RECENT, settings.deviceSortMode)
        settings.deviceSortMode = DeviceSortMode.RSSI

        val reopened = AppSettings(context)
        assertEquals(DeviceSortMode.RSSI, reopened.deviceSortMode)
    }

    @Test
    fun `首启引导默认未完成且完成状态可持久化`() {
        val settings = AppSettings(context)
        assertFalse(settings.onboardingCompleted)

        settings.onboardingCompleted = true
        assertTrue(AppSettings(context).onboardingCompleted)

        settings.onboardingCompleted = false
        assertFalse(AppSettings(context).onboardingCompleted)
    }

    @Test
    fun `后台采集开关默认关闭且可持久化`() {
        val settings = AppSettings(context)
        assertFalse(settings.backgroundCollectionEnabled)

        settings.backgroundCollectionEnabled = true

        val reopened = AppSettings(context)
        assertTrue(reopened.backgroundCollectionEnabled)

        reopened.backgroundCollectionEnabled = false
        assertFalse(AppSettings(context).backgroundCollectionEnabled)
    }
}
