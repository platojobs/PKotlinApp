package com.platojobs.pkotlinapp.domain.scene

/** 场景动作：只允许真实可执行的集合，拒绝「播放音乐」这类无法落地的假动作。 */
enum class SceneActionType {
    CONNECT_DEVICE,
    DISCONNECT_DEVICE,
    START_COLLECTION,
    STOP_COLLECTION,
    MUTE_ALERTS,
    SYNC_NOW,
}

val SCENE_MUTE_MINUTES = listOf(10, 30, 60)

/** 单个动作；设备类动作必须带 [deviceMac]，静音必须带合法的 [muteMinutes]。 */
data class SceneAction(
    val type: SceneActionType,
    val deviceMac: String? = null,
    val muteMinutes: Int? = null,
) {
    /** 存储/展示前的最终校验；不合法时返回原因，避免把假动作写进 Room。 */
    fun invalidReason(): String? = when (type) {
        SceneActionType.CONNECT_DEVICE,
        SceneActionType.DISCONNECT_DEVICE,
        -> if (deviceMac.isNullOrBlank()) "请选择要操作的设备" else null

        SceneActionType.MUTE_ALERTS ->
            if (muteMinutes in SCENE_MUTE_MINUTES) null else "静音时长只支持 10/30/60 分钟"

        SceneActionType.START_COLLECTION,
        SceneActionType.STOP_COLLECTION,
        SceneActionType.SYNC_NOW,
        -> null
    }
}

data class Scene(
    val id: Long,
    val name: String,
    val actions: List<SceneAction>,
    val createdAt: Long = 0L,
) {
    fun invalidReason(): String? {
        if (name.isBlank()) return "场景名称不能为空"
        if (actions.isEmpty()) return "至少添加一个动作"
        actions.forEachIndexed { index, action ->
            action.invalidReason()?.let { return "第 ${index + 1} 个动作：$it" }
        }
        return null
    }
}

/** 单个动作的执行结果；失败必须带可读原因，且不阻断后续动作。 */
data class SceneActionOutcome(
    val action: SceneAction,
    val success: Boolean,
    val message: String,
)

/** 整场执行汇总：成功/失败计数与逐条结果。 */
data class SceneRunResult(
    val sceneId: Long,
    val outcomes: List<SceneActionOutcome>,
) {
    val successCount: Int get() = outcomes.count { it.success }
    val failureCount: Int get() = outcomes.count { !it.success }
    val allSucceeded: Boolean get() = outcomes.isNotEmpty() && failureCount == 0

    /** toast/汇总文案：全部成功、部分失败、全部失败三种口径。 */
    fun summary(sceneName: String): String = when {
        outcomes.isEmpty() -> "「$sceneName」没有可执行的动作"
        allSucceeded -> "「$sceneName」执行成功（$successCount 个动作）"
        successCount == 0 -> "「$sceneName」执行失败（$failureCount 个动作均失败）"
        else -> "「$sceneName」部分成功：$successCount 成功 / $failureCount 失败"
    }
}
