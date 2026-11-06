package com.platojobs.pkotlinapp.domain.scene

/**
 * 从服务端场景快照/change_log 解析出的不可变载荷。
 *
 * 同时保留原始 JSON（PUT 时原样回传字段，避免本地投影丢字段）与解析后的领域对象
 * （字段级 LWW 合并与执行使用）。
 */
data class RemoteScene(
    val remoteId: String,
    val name: String,
    val enabled: Boolean,
    val cooldownSeconds: Int,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val triggerJson: String,
    val actionsJson: String,
    val trigger: SceneTrigger?,
    val actions: List<SceneAction>,
    /** 服务端实时计算：触发条件引用的设备已不存在（不会级联删场景）。 */
    val deviceMissing: Boolean = false,
)
