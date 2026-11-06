package com.platojobs.pkotlinapp.diagnostics

/** 冷启动阶段：进程创建 → Application.onCreate → Activity 创建 → 首帧 → 首页数据就绪。 */
enum class StartupStage {
    PROCESS_START,
    APPLICATION_CREATE,
    ACTIVITY_CREATE,
    FIRST_FRAME,
    HOME_DATA_READY,
}

/** 一次阶段打点；[elapsedMillis] 相对进程创建时刻。 */
data class StartupMark(
    val stage: StartupStage,
    val elapsedMillis: Long,
    val threadName: String,
)

/** 一条 StrictMode 违规；[threadName] 是触发违规的线程，[kind] 形如 DiskWriteViolation。 */
data class StrictModeViolation(
    val threadName: String,
    val kind: String,
    val detail: String,
)
