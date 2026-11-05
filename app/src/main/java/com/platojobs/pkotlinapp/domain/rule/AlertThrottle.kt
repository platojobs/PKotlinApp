package com.platojobs.pkotlinapp.domain.rule

/** 告警状态持久化接口；生产实现落 SharedPreferences，单测用内存实现。 */
interface AlertStateStore {
    fun lastNotifiedAt(key: String): Long

    fun putLastNotifiedAt(key: String, timestamp: Long)

    fun isPendingRecovery(key: String): Boolean

    fun putPendingRecovery(key: String, pending: Boolean)
}

/**
 * 告警节流与恢复判定（纯 Kotlin，可单测）。
 *
 * - 同一设备 + 同一规则在窗口内只允许投递一次通知；
 * - 只有真正投递过告警的键才有「恢复待发」状态，回落后只发一条已恢复；
 * - 窗口与时钟均可注入，测试不依赖系统时间。
 */
class AlertThrottle(
    private val store: AlertStateStore,
    private val windowMillis: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun allowTrigger(key: String): Boolean {
        val now = clock()
        val last = store.lastNotifiedAt(key)
        if (last > 0L && now - last < windowMillis()) return false
        store.putLastNotifiedAt(key, now)
        store.putPendingRecovery(key, true)
        return true
    }

    fun allowRecovery(key: String): Boolean {
        if (!store.isPendingRecovery(key)) return false
        store.putPendingRecovery(key, false)
        // 清掉时间戳，下一次触发无需等窗口结束即可再次通知。
        store.putLastNotifiedAt(key, 0L)
        return true
    }
}
