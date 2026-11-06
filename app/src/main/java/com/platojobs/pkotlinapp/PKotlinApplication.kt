package com.platojobs.pkotlinapp

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.scene.SceneScheduleWorker
import com.platojobs.pkotlinapp.diagnostics.StartupDiagnostics
import com.platojobs.pkotlinapp.diagnostics.StartupStage
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
        // 诊断必须先于任何业务初始化安装，StrictMode 才能覆盖到后面的代码。
        StartupDiagnostics.install(this)
        super.onCreate()
        // onCreate 只做轻量的容器装配：主题偏好、Room 开户都在 IO 读，
        // 主线程只负责切换主题模式，避免首帧前的同步磁盘 IO。
        container = AppContainer(this)
        appScope.launch {
            // 与首帧的布局工作并行：首页第一条数据不必再等 Room 开户和迁移。
            runCatching { container.preWarmDatabase() }
            val mode = container.settings.themeMode
            Handler(Looper.getMainLooper()).post { AppSettings.applyThemeMode(mode) }
        }
        StartupDiagnostics.mark(StartupStage.APPLICATION_CREATE)
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
        appScope.launch {
            runCatching {
                container.ruleEngine.refresh()
                container.ruleEngine.onScheduleTick()
            }
        }
        appScope.launch { runCatching { SceneScheduleWorker.schedule(this@PKotlinApplication) } }
    }
}
