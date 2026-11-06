package com.platojobs.pkotlinapp.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 冷启动诊断（debug 源集专有）：
 * - 安装 StrictMode 线程/VM 策略，违规写 Logcat 并保留在内存供设备侧测试断言；
 * - 记录各阶段耗时并异步写入 `files/diagnostics/cold-start.log`。
 *
 * release 变体由同名 no-op 实现替代（`src/release/.../StartupDiagnostics.kt`），
 * 因此诊断代码、日志标签与文件名都不会进入 release APK。
 *
 * 所有文件写入都在单线程守护线程上完成，避免诊断本身制造主线程磁盘 IO。
 */
object StartupDiagnostics {

    private const val TAG = "ColdStart"
    private const val MAX_LOG_BYTES = 128L * 1024L

    @Volatile
    private var enabled = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var processStartElapsed = 0L

    private val marks = CopyOnWriteArrayList<StartupMark>()
    private val recordedStages = HashSet<StartupStage>()
    private val violations = CopyOnWriteArrayList<StrictModeViolation>()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cold-start-log").apply { isDaemon = true }
    }
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 在 Application.onCreate 最早期调用。 */
    fun install(context: Context) {
        val applicationContext = context.applicationContext
        // 单元测试里每个用例都会创建新的 Application；换实例时重置本次打点。
        if (enabled && appContext === applicationContext) return
        appContext = applicationContext
        marks.clear()
        violations.clear()
        synchronized(recordedStages) { recordedStages.clear() }
        processStartElapsed = runCatching { Process.getStartElapsedRealtime() }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        installStrictMode()
        enabled = true
        mark(StartupStage.PROCESS_START)
    }

    private fun recordViolation(violation: Throwable) {
        val threadName = Thread.currentThread().name
        val kind = violation.javaClass.simpleName
        val detail = violation.stackTrace.firstOrNull()?.toString() ?: violation.toString()
        violations += StrictModeViolation(threadName, kind, detail)
        Log.e(TAG, "StrictMode $kind on $threadName: $detail", violation)
    }

    private fun installStrictMode() {
        val builder = StrictMode.ThreadPolicy.Builder()
            .detectAll()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 直接执行的监听器让回调留在触发违规的那条线程上，
            // 这样 StrictModeViolation.threadName 才是真正的违规线程，可在测试里区分主线程/后台线程。
            // penaltyListener 从 API 28 才有；更低版本只保留 logcat 输出。
            builder.penaltyListener(Executor { it.run() }) { violation ->
                recordViolation(violation)
            }
        }
        StrictMode.setThreadPolicy(builder.build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * 诊断日志线程写文件时临时放开自身线程策略：
     * 这条线程的落盘是有意为之的后台 IO，不该混进冷启动违规统计。
     */
    private inline fun onDiagnosticsThread(block: () -> Unit) {
        val previous = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder(previous).permitAll().build(),
        )
        try {
            block()
        } finally {
            StrictMode.setThreadPolicy(previous)
        }
    }

    /**
     * 记录阶段；同一阶段只记第一次，调用方可以放心在多次回调里重复调用。
     * 未安装或重复阶段时立即返回。
     */
    fun mark(stage: StartupStage) {
        if (!enabled) return
        synchronized(recordedStages) {
            if (!recordedStages.add(stage)) return
        } // HashSet.add 返回 false 表示已记录过
        val elapsed = (SystemClock.elapsedRealtime() - processStartElapsed).coerceAtLeast(0L)
        val mark = StartupMark(stage, elapsed, Thread.currentThread().name)
        marks += mark
        Log.i(TAG, "${stage.name} +${elapsed}ms thread=${mark.threadName}")
        val context = appContext ?: return
        writer.execute { onDiagnosticsThread { appendToFile(context, mark) } }
    }

    /** 供单元测试/设备侧断言读取（按实际打点顺序）。 */
    fun snapshot(): List<StartupMark> = marks.toList()

    /** 已捕获的 StrictMode 违规；设备侧测试用它断言冷启动路径无主线程磁盘 IO。 */
    fun violations(): List<StrictModeViolation> = violations.toList()

    /** 清空违规记录，便于在某一阶段（例如 Activity 启动）开始前重新计数。 */
    fun resetViolations() {
        if (!enabled) return
        violations.clear()
    }

    fun isEnabled(): Boolean = enabled

    private fun appendToFile(context: Context, mark: StartupMark) {
        runCatching {
            val dir = File(context.filesDir, "diagnostics")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "cold-start.log")
            if (file.length() > MAX_LOG_BYTES) file.writeText("")
            val wallClock = clockFormat.format(Date())
            file.appendText(
                "$wallClock ${mark.stage.name} +${mark.elapsedMillis}ms thread=${mark.threadName}\n",
            )
        }
    }
}
