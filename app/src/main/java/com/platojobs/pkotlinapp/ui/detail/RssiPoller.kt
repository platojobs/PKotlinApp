package com.platojobs.pkotlinapp.ui.detail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 详情页 RSSI 轮询开关。
 *
 * 只有「页面可见且已连接」时才跑定时器；[sync] 传 false 立即停表，
 * 停表只取消采集，不影响已读到的值与时间。
 */
internal class RssiPoller(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val read: () -> Unit,
) {

    private var job: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    fun sync(active: Boolean) {
        if (!active) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                read()
                delay(intervalMs)
            }
        }
    }
}
