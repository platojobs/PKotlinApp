package com.platojobs.pkotlinapp.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** 网络可用时批量推送；统一使用唯一 work，避免重复排队。 */
class SyncScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun schedule() {
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            buildRequest(),
        )
    }

    fun scheduleNow() {
        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            buildRequest(),
        )
    }

    private fun buildRequest() =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    private companion object {
        const val WORK_NAME = "device-sync"
    }
}
