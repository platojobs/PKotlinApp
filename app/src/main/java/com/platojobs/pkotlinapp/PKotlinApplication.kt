package com.platojobs.pkotlinapp

import android.app.Application
import com.platojobs.pkotlinapp.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PKotlinApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 冷启动增量拉取任务：MainActivity 会短暂等待它完成后再渲染。 */
    lateinit var initialSync: Deferred<Boolean>
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.settings.applyTheme()

        // 7 天保留：启动即清理，避免离线长期累积；同步成功后还会再清理一次。
        appScope.launch { runCatching { container.readingRepository.cleanup() } }
        initialSync = appScope.async { container.syncManager.coldStartPull() }
        appScope.launch { runCatching { container.syncScheduler.schedule() } }
    }

    /**
     * 最多等待 [timeoutMs]；超时或失败都继续渲染本地数据，避免白屏。
     */
    suspend fun awaitInitialSync(timeoutMs: Long = 3_000) {
        withTimeoutOrNull(timeoutMs) { initialSync.await() }
    }
}
