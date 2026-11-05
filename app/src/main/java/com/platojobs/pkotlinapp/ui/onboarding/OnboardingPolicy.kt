package com.platojobs.pkotlinapp.ui.onboarding

/** 权限快照：只关心“能不能扫描 / 能不能连接 / 能不能发通知”。 */
data class PermissionStatus(
    val canScan: Boolean,
    val canConnect: Boolean,
    val canNotify: Boolean,
)

enum class CapabilityIssue {
    BLUETOOTH_SCAN,
    BLUETOOTH_CONNECT,
    NOTIFICATIONS,
}

/**
 * 首启权限结果的纯逻辑：把系统权限映射成能力边界，便于单元测试与页面文案复用。
 */
object OnboardingPolicy {

    fun issues(status: PermissionStatus, notificationsRuntime: Boolean): List<CapabilityIssue> = buildList {
        if (!status.canScan) add(CapabilityIssue.BLUETOOTH_SCAN)
        if (!status.canConnect) add(CapabilityIssue.BLUETOOTH_CONNECT)
        if (notificationsRuntime && !status.canNotify) add(CapabilityIssue.NOTIFICATIONS)
    }

    /** 缺少扫描或连接权限时，App 只能查看本机已有数据。 */
    fun isLocalOnly(status: PermissionStatus): Boolean = !status.canScan || !status.canConnect

    fun allGranted(status: PermissionStatus, notificationsRuntime: Boolean): Boolean =
        issues(status, notificationsRuntime).isEmpty()
}
