package com.platojobs.pkotlinapp.data.scene

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.platojobs.pkotlinapp.PKotlinApplication
import java.util.concurrent.TimeUnit

/** 定时场景的最小后台驱动：每 15 分钟让引擎判定一次定时规则。 */
class SceneScheduleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PKotlinApplication ?: return Result.failure()
        return runCatching {
            // 规则可能刚被编辑，先刷新缓存再判定。
            app.container.ruleEngine.refresh()
            app.container.ruleEngine.onScheduleTick()
            // 服务端 device_offline 条件：用设备最近出现时间做定时离线判定。
            val devices = app.container.deviceRepository.getDevices()
            app.container.ruleEngine.onOfflineCheck(
                lastSeenByMac = devices.associate { it.mac.uppercase() to it.lastSeenTimestamp },
                displayNameOf = { mac -> devices.firstOrNull { it.mac.equals(mac, true) }?.name.orEmpty() },
            )
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "scene-schedule"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SceneScheduleWorker>(
                15,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
