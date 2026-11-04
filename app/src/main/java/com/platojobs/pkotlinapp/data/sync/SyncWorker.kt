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
            // 登录失效时停止重试，等待用户在「我的」页确认重新登录；
            // 连续失败达到阈值后进入纯本地模式，用户可手动同步恢复。
            if (app.container.syncManager.isLocalOnly() || app.container.syncManager.isAuthExpired()) {
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}
