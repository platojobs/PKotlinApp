package com.platojobs.pkotlinapp.diagnostics

import android.os.Looper
import com.platojobs.pkotlinapp.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 冷启动阶段顺序：进程创建 → Application.onCreate → Activity 创建 → 首帧 → 首页数据就绪。
 * 打点由 debug 构建的 [StartupDiagnostics] 完成；release 下为 no-op，因此断言先确认已启用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ColdStartStageOrderTest {

    @Test
    fun `cold start stages are recorded once and in order`() {
        assertTrue("诊断只在 debug 构建启用", StartupDiagnostics.isEnabled())

        // Application.onCreate 已经执行完毕。
        val beforeActivity = StartupDiagnostics.snapshot().map { it.stage }
        assertTrue(beforeActivity.contains(StartupStage.PROCESS_START))
        assertTrue(beforeActivity.contains(StartupStage.APPLICATION_CREATE))
        assertFalse(beforeActivity.contains(StartupStage.ACTIVITY_CREATE))

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        controller.get()

        val stages = StartupDiagnostics.snapshot().map { it.stage }
        assertEquals("同一阶段只记录一次：$stages", stages.distinct(), stages)
        assertTrue("Activity 创建在 Application 之后", stages.indexOf(StartupStage.APPLICATION_CREATE) < stages.indexOf(StartupStage.ACTIVITY_CREATE))
        assertTrue("首帧在 Activity 创建之后", stages.indexOf(StartupStage.ACTIVITY_CREATE) < stages.indexOf(StartupStage.FIRST_FRAME))
        if (StartupStage.HOME_DATA_READY in stages) {
            assertEquals("首页数据就绪是最后一个阶段", StartupStage.HOME_DATA_READY, stages.last())
        }

        val elapsed = StartupDiagnostics.snapshot().map { it.elapsedMillis }
        assertEquals("阶段耗时单调不减", elapsed.sorted(), elapsed)
        assertTrue("耗时相对进程创建不为负", elapsed.all { it >= 0L })
    }
}
