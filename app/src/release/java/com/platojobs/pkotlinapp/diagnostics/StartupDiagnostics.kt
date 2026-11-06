package com.platojobs.pkotlinapp.diagnostics

import android.content.Context

/**
 * release 变体的冷启动诊断：全部为空操作。
 * 真实实现只在 debug 源集，StrictMode、日志文件与诊断标签都不会进入 release APK。
 */
object StartupDiagnostics {

    fun install(context: Context) = Unit

    fun mark(stage: StartupStage) = Unit

    fun snapshot(): List<StartupMark> = emptyList()

    fun violations(): List<StrictModeViolation> = emptyList()

    fun resetViolations() = Unit

    fun isEnabled(): Boolean = false
}
