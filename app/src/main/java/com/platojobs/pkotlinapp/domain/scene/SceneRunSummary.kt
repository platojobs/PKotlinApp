package com.platojobs.pkotlinapp.domain.scene

/** 一次执行的整体结果；只由成功/失败计数推导，与执行记录字段一一对应。 */
enum class SceneRunOutcome {
    SUCCESS,
    FAILURE,
    PARTIAL,
}

fun SceneRun.outcome(): SceneRunOutcome = when {
    failureCount == 0 -> SceneRunOutcome.SUCCESS
    successCount == 0 -> SceneRunOutcome.FAILURE
    else -> SceneRunOutcome.PARTIAL
}

/**
 * 每个场景最近一次执行；输入需按时间倒序（仓库查询已保证），首次出现的记录即最新。
 * 执行记录被用户清空后自然为空，不补造占位记录。
 */
fun latestRunByScene(runs: List<SceneRun>): Map<Long, SceneRun> {
    val result = LinkedHashMap<Long, SceneRun>()
    runs.forEach { run -> result.putIfAbsent(run.sceneId, run) }
    return result
}
