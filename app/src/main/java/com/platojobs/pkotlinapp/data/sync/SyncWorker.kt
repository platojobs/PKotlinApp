package com.platojobs.pkotlinapp.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.platojobs.pkotlinapp.PKotlinApplication

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PKotlinApplication ?: return Result.failure()
        return try {
            app.container.syncManager.syncNow()
            Result.success()
        } catch (_: Exception) {
            // 连续失败达到阈值后进入纯本地模式，停止无意义的重试；
            // 用户可在「我的」页手动同步恢复。
            if (app.container.syncManager.isLocalOnly()) {
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}
