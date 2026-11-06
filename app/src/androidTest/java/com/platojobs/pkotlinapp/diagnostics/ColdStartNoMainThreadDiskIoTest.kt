package com.platojobs.pkotlinapp.diagnostics

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.platojobs.pkotlinapp.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备侧断言：冷启动路径（Application.onCreate → MainActivity 首帧 → 首页数据）不产生主线程磁盘 IO。
 *
 * Robolectric 的 StrictMode 只提供空壳，不检测真实的文件读写，所以这条断言必须跑在真机/模拟器上。
 * [StartupDiagnostics] 在 debug 构建里安装 `detectAll()` 的 ThreadPolicy，
 * 用直接执行的监听器把违规连同触发线程名一起记下来。
 */
@RunWith(AndroidJUnit4::class)
class ColdStartNoMainThreadDiskIoTest {

    @Test
    fun coldStartPathDoesNotReadOrWriteDiskOnMainThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        assertTrue("StrictMode 诊断只在 debug 构建启用", StartupDiagnostics.isEnabled())

        // Application.onCreate 已经执行完；从这里继续覆盖 Activity 创建、首帧与首页数据。
        StartupDiagnostics.resetViolations()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { /* 确认 Activity 已进入 RESUMED */ }
            // 首页数据来自 Room/IO 协程，回流到主线程需要一点时间。
            Thread.sleep(1_500)
            instrumentation.waitForIdleSync()
        }
        instrumentation.waitForIdleSync()

        val mainThreadDiskIo = StartupDiagnostics.violations().filter { violation ->
            violation.threadName == "main" &&
                (violation.kind == "DiskReadViolation" || violation.kind == "DiskWriteViolation")
        }
        val reported = mainThreadDiskIo.joinToString("\n") { "${it.kind} ${it.detail}" }
        assertTrue("冷启动路径出现主线程磁盘 IO：\n$reported", mainThreadDiskIo.isEmpty())

        val stages = StartupDiagnostics.snapshot().map { it.stage }
        assertTrue("缺少首帧打点：$stages", StartupStage.FIRST_FRAME in stages)
    }
}
