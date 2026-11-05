package com.platojobs.pkotlinapp.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPolicyTest {

    @Test
    fun `缺少扫描权限时提示扫描问题且仅本机可用`() {
        val status = PermissionStatus(canScan = false, canConnect = true, canNotify = true)
        val issues = OnboardingPolicy.issues(status, notificationsRuntime = true)

        assertEquals(listOf(CapabilityIssue.BLUETOOTH_SCAN), issues)
        assertTrue(OnboardingPolicy.isLocalOnly(status))
        assertFalse(OnboardingPolicy.allGranted(status, notificationsRuntime = true))
    }

    @Test
    fun `仅通知未授权时不算仅本机可用`() {
        val status = PermissionStatus(canScan = true, canConnect = true, canNotify = false)
        val issues = OnboardingPolicy.issues(status, notificationsRuntime = true)

        assertEquals(listOf(CapabilityIssue.NOTIFICATIONS), issues)
        assertFalse(OnboardingPolicy.isLocalOnly(status))
        assertEquals(
            emptyList<CapabilityIssue>(),
            OnboardingPolicy.issues(status, notificationsRuntime = false),
        )
    }

    @Test
    fun `扫描与连接都授权时能力完整`() {
        val status = PermissionStatus(canScan = true, canConnect = true, canNotify = true)

        assertTrue(OnboardingPolicy.allGranted(status, notificationsRuntime = true))
        assertTrue(OnboardingPolicy.issues(status, notificationsRuntime = true).isEmpty())
    }
}
