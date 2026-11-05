package com.platojobs.pkotlinapp

import android.app.Application
import com.platojobs.pkotlinapp.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PKotlinApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backgroundWorkStarted = false

    override fun onCreate() {
        super.onCreate()
        // onCreate 只做轻量的容器装配与主题恢复，不打开数据库、不发网络请求。
        container = AppContainer(this)
        container.settings.applyTheme()
    }

    /**
     * 同步、保留清理与周期调度都延后到首帧之后，由 MainActivity 调用；
     * 全部运行在 IO 线程，不阻塞首帧，也不与布局渲染抢主线程。
     */
    fun startBackgroundWork() {
        if (backgroundWorkStarted) return
        backgroundWorkStarted = true
        appScope.launch { runCatching { container.readingRepository.cleanup() } }
        appScope.launch { runCatching { container.syncManager.coldStartPull() } }
        appScope.launch { runCatching { container.syncScheduler.schedule() } }
    }
}
