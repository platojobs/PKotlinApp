package com.platojobs.pkotlinapp.domain.scene

/** 场景执行所需的最小运行时能力；真实实现接 BLE / 前台服务 / 偏好设置 / 同步。 */
interface SceneRuntime {
    suspend fun connectDevice(mac: String): Boolean

    /** 仅当当前连接的正是该设备时返回 true，否则视为「设备未连接」。 */
    suspend fun disconnectDevice(mac: String): Boolean

    suspend fun connectedMac(): String?

    fun collectionActive(): Boolean

    fun activeCollectionMac(): String?

    suspend fun startCollection(mac: String): Boolean

    fun stopCollection(): Boolean

    fun muteAlerts(minutes: Int)

    fun syncNow()

    /** 投递本地通知；title/body 已由执行器完成占位符渲染。 */
    suspend fun notify(title: String, body: String): Boolean

    /** 把设备写入 markedAt；设备不存在返回 false。 */
    suspend fun markDevice(mac: String): Boolean
}

/**
 * 场景执行器：按顺序逐个执行，任一失败不阻断后续，最后汇总。
 *
 * 权限判定由调用方注入（[canConnect]），便于 JVM 单测覆盖「权限缺失」路径。
 */
class SceneExecutor(
    private val runtime: SceneRuntime,
    private val canConnect: () -> Boolean,
) {

    /**
     * @param onActionStart (当前序号, 总数, 动作)，用于界面展示真实进度；
     *        在动作真正开始前调用一次。
     */
    suspend fun run(
        scene: Scene,
        context: SceneTriggerContext = SceneTriggerContext(),
        onActionStart: (index: Int, total: Int, action: SceneAction) -> Unit = { _, _, _ -> },
    ): SceneRunResult {
        val outcomes = mutableListOf<SceneActionOutcome>()
        val actions = scene.actions
        actions.forEachIndexed { index, action ->
            onActionStart(index, actions.size, action)
            outcomes += execute(action, scene.name, context)
        }
        return SceneRunResult(sceneId = scene.id, outcomes = outcomes)
    }

    private suspend fun execute(
        action: SceneAction,
        sceneName: String,
        context: SceneTriggerContext,
    ): SceneActionOutcome {
        val failure = action.invalidReason()
        if (failure != null) {
            return SceneActionOutcome(action, false, failure)
        }
        return try {
            when (action.type) {
                SceneActionType.CONNECT_DEVICE -> {
                    val mac = action.deviceMac.orEmpty()
                    when {
                        !canConnect() -> fail(action, "缺少蓝牙连接权限")
                        runtime.connectDevice(mac) -> ok(action, "已连接 $mac")
                        else -> fail(action, "连接失败，请确认设备在线且蓝牙已开启")
                    }
                }

                SceneActionType.DISCONNECT_DEVICE -> {
                    val mac = action.deviceMac.orEmpty()
                    if (runtime.disconnectDevice(mac)) {
                        ok(action, "已断开 $mac")
                    } else {
                        fail(action, "设备未连接，无需断开")
                    }
                }

                SceneActionType.START_COLLECTION -> {
                    val mac = runtime.connectedMac()
                    when {
                        mac == null -> fail(action, "请先连接设备再开始后台采集")
                        runtime.collectionActive() -> fail(action, "已在后台采集中，未重复执行")
                        runtime.startCollection(mac) -> ok(action, "已在后台采集 $mac")
                        else -> fail(action, "启动采集失败，请检查通知/前台服务权限")
                    }
                }

                SceneActionType.STOP_COLLECTION -> {
                    if (runtime.collectionActive() && runtime.stopCollection()) {
                        ok(action, "已停止后台采集")
                    } else {
                        fail(action, "当前未在后台采集")
                    }
                }

                SceneActionType.MUTE_ALERTS -> {
                    val minutes = action.muteMinutes ?: 0
                    runtime.muteAlerts(minutes)
                    ok(action, "告警已静音 $minutes 分钟")
                }

                SceneActionType.SYNC_NOW -> {
                    runtime.syncNow()
                    ok(action, "已提交同步")
                }

                SceneActionType.NOTIFY -> {
                    // 服务端模板优先；旧本机动作没有模板时保持原有默认文案。
                    val title = ScenePlaceholders.render(action.title, context)
                        .ifBlank { "场景「$sceneName」" }
                    val body = ScenePlaceholders.render(action.body, context)
                        .ifBlank { "场景「$sceneName」已触发" }
                    if (runtime.notify(title, body)) {
                        ok(action, "已发送本地通知：$title")
                    } else {
                        fail(action, "通知未发送：请检查通知权限")
                    }
                }

                SceneActionType.RECORD_LOG -> {
                    val message = ScenePlaceholders.render(action.message, context)
                    val level = action.level?.takeIf { it.equals("warn", true) } ?: "info"
                    ok(action, if (message.isBlank()) "已记录本次触发" else "[$level] $message")
                }

                SceneActionType.MARK_DEVICE -> {
                    val mac = action.deviceMac.orEmpty()
                    if (runtime.markDevice(mac)) {
                        ok(action, "已标记设备 $mac")
                    } else {
                        fail(action, "标记失败：设备不存在")
                    }
                }
            }
        } catch (e: Exception) {
            fail(action, e.message?.takeIf { it.isNotBlank() } ?: "执行异常")
        }
    }

    private fun ok(action: SceneAction, message: String) =
        SceneActionOutcome(action, true, message)

    private fun fail(action: SceneAction, message: String) =
        SceneActionOutcome(action, false, message)
}
