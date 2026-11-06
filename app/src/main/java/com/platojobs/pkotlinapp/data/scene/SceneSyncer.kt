package com.platojobs.pkotlinapp.data.scene

import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.data.local.SceneActionEntity
import com.platojobs.pkotlinapp.data.local.SceneDao
import com.platojobs.pkotlinapp.data.local.SceneEntity
import com.platojobs.pkotlinapp.data.local.SceneWithActions
import com.platojobs.pkotlinapp.data.remote.SceneActionsFieldDto
import com.platojobs.pkotlinapp.data.remote.SceneBoolFieldDto
import com.platojobs.pkotlinapp.data.remote.SceneInputDto
import com.platojobs.pkotlinapp.data.remote.SceneIntFieldDto
import com.platojobs.pkotlinapp.data.remote.ScenePatchDto
import com.platojobs.pkotlinapp.data.remote.SceneStringFieldDto
import com.platojobs.pkotlinapp.data.remote.SceneTimestamps
import com.platojobs.pkotlinapp.data.remote.SceneTriggerFieldDto
import com.platojobs.pkotlinapp.domain.scene.RemoteScene
import com.platojobs.pkotlinapp.domain.scene.SceneSyncState
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger

/**
 * 场景同步编排：首次上传、基线拉取合并、离线 pending 补传与软删。
 *
 * 关键顺序（[sync]）：
 * 1. `GET /scenes` 作为基线并逐条按字段级 LWW 合并；
 * 2. 处理本地 pending 删除（DELETE 204 / 404 都视为完成）；
 * 3. 本地未绑定 remoteId 的场景先按「名称 + trigger + actions」签名去重，
 *    命中服务端已有场景就只回写 remoteId，否则逐条 POST；
 * 4. 已有 remoteId 的 pending 场景用 PUT 补传（五个字段全部带上各自时间戳，
 *    服务端只接受更新的字段，因此未改字段不会覆盖别人的修改）。
 *
 * 网络失败只记录 `syncError` 并保持 pending，绝不丢场景。
 */
class SceneSyncer(
    private val dao: SceneDao,
    private val deviceDao: DeviceDao,
    private val remote: SceneRemote,
    private val now: () -> Long = System::currentTimeMillis,
    /** 落库后通知规则缓存刷新，让服务端场景立即参与执行。 */
    private val onScenesChanged: suspend () -> Unit = {},
) {

    /** 完整同步：基线 -> 删除 -> 首次上传 -> 增量补传。 */
    suspend fun sync() {
        val baseline = pullBaseline()
        pushDeletes()
        uploadUnlinked(baseline)
        pushPendingUpdates()
        onScenesChanged()
    }

    /** GET /scenes：合并快照，并把「服务端已不存在」的本地场景软删。 */
    suspend fun pullBaseline(): List<RemoteScene> {
        val remoteScenes = remote.list()
        remoteScenes.forEach { applyRemote(it) }
        val remoteIds = remoteScenes.map { it.remoteId }.toSet()
        dao.getAllIncludingDeleted()
            .filter {
                val id = it.scene.remoteId
                id != null && id !in remoteIds && it.scene.deletedAt == null
            }
            .forEach {
                dao.markServerDeleted(it.scene.id, now())
                dao.setRulesEnabled(it.scene.id, false)
            }
        return remoteScenes
    }

    /** change_log 中的 scene 变更：逐条合并后只刷新一次规则缓存。 */
    suspend fun applyChanges(scenes: List<RemoteScene>) {
        scenes.forEach { applyRemote(it) }
        if (scenes.isNotEmpty()) onScenesChanged()
    }

    /** change_log 的 `entity=scene` 变更走同一合并逻辑。 */
    suspend fun applyRemote(scene: RemoteScene) {
        val local = dao.findByRemoteId(scene.remoteId)
        if (scene.deleted) {
            if (local != null) {
                // 本地删除 pending 且更晚：保留本地墓碑，等待 DELETE 补传。
                val keepLocalDelete = local.syncState == SceneSyncState.PENDING.storage &&
                    local.deletedAt != null &&
                    local.deletedAt > scene.updatedAt
                if (!keepLocalDelete) {
                    dao.markServerDeleted(local.id, scene.updatedAt)
                    dao.setRulesEnabled(local.id, false)
                }
            }
            return
        }

        if (local == null) {
            // 基线里已存在内容完全相同的场景：先认领本地待上传场景，
            // 避免「先插一条副本、再上传去重」把 remoteId 写两次撞唯一索引。
            val matched = findUnlinkedMatch(scene)
            if (matched != null) {
                bindExisting(matched.id, scene)
                return
            }
            val trigger = scene.trigger
            val entity = SceneEntity(
                name = scene.name,
                createdAt = scene.createdAt.takeIf { it > 0 } ?: scene.updatedAt,
                enabled = scene.enabled,
                cooldownSeconds = scene.cooldownSeconds,
                remoteId = scene.remoteId,
                version = scene.version,
                syncState = SceneSyncState.SYNCED.storage,
                deletedAt = null,
                serverUpdatedAt = scene.updatedAt,
                nameUpdatedAt = scene.updatedAt,
                enabledUpdatedAt = scene.updatedAt,
                cooldownUpdatedAt = scene.updatedAt,
                triggerUpdatedAt = scene.updatedAt,
                actionsUpdatedAt = scene.updatedAt,
                triggerJson = scene.triggerJson,
                actionsJson = scene.actionsJson,
                syncError = null,
            )
            dao.createSceneWithRule(
                scene = entity,
                rule = trigger?.let { SceneCanonical.ruleEntity(it, scene.enabled) },
                actions = SceneCanonical.actionEntities(scene.actions),
            )
            return
        }

        if (local.deletedAt != null) {
            val keepDelete = local.syncState == SceneSyncState.PENDING.storage &&
                local.deletedAt > scene.updatedAt
            if (keepDelete) return
            dao.reviveFromServer(local.id)
        }
        val refreshed = dao.find(local.id) ?: return
        applySnapshot(refreshed, scene, MergeMode.PULL)
        rebuildProjections(local.id)
    }

    /** 已上传场景的离线编辑：有 remoteId 走 PUT，把五个字段与各自时间戳全部补传。 */
    suspend fun pushPendingUpdates() {
        dao.pendingUpdates().forEach { entity ->
            if (entity.deletedAt != null) return@forEach
            val patch = runCatching { buildPatch(entity) }.getOrElse { error ->
                dao.markSyncState(entity.id, SceneSyncState.PENDING.storage, readable(error))
                return@forEach
            }
            runCatching { remote.update(entity.remoteId.orEmpty(), patch) }
                .onSuccess { response ->
                    val local = dao.find(entity.id) ?: return@onSuccess
                    applySnapshot(local, response, MergeMode.RESPONSE)
                    rebuildProjections(entity.id)
                }
                .onFailure { error ->
                    if (error is SceneGoneException) {
                        dao.markServerDeleted(entity.id, now())
                        dao.setRulesEnabled(entity.id, false)
                    } else {
                        dao.markSyncState(entity.id, SceneSyncState.PENDING.storage, readable(error))
                    }
                }
        }
    }

    suspend fun pushDeletes() {
        dao.pendingDeletes().forEach { entity ->
            val remoteId = entity.remoteId
            if (remoteId == null) {
                dao.deleteSceneWithRule(entity.id)
                return@forEach
            }
            runCatching { remote.delete(remoteId) }
                .onSuccess { dao.deleteSceneWithRule(entity.id) }
                .onFailure { error ->
                    if (error is SceneGoneException) {
                        dao.deleteSceneWithRule(entity.id)
                    } else {
                        dao.markSyncState(entity.id, SceneSyncState.PENDING.storage, readable(error))
                    }
                }
        }
    }

    /**
     * 首次上传：逐条尝试上传本机已有场景。
     * 上传前先与基线做签名去重，命中则只写回 remoteId，避免重复创建。
     * 服务端模型无法表达的旧场景 → 显式 `localOnly` + 原因，不删除。
     */
    suspend fun uploadUnlinked(baseline: List<RemoteScene>? = null) {
        val remoteScenes = baseline ?: remote.list()
        val signatures = remoteScenes.associateBy { signatureOf(it) }.toMutableMap()
        dao.pendingUploads().forEach { entity ->
            val row = dao.findRow(entity.id) ?: return@forEach
            val encoded = encodedSnapshot(row) ?: run {
                dao.markSyncState(
                    entity.id,
                    SceneSyncState.LOCAL_ONLY.storage,
                    unsupportedReason(row),
                )
                return@forEach
            }
            if (entity.triggerJson != encoded.triggerJson) {
                dao.updateTriggerForSync(entity.id, encoded.triggerJson, now())
            }
            if (entity.actionsJson != encoded.actionsJson) {
                dao.updateActionsForSync(entity.id, encoded.actionsJson, now())
            }
            val signature = signature(
                name = entity.name,
                triggerJson = encoded.triggerJson,
                actionsJson = encoded.actionsJson,
            )
            val existing = signatures[signature]
            if (existing != null) {
                bindExisting(entity.id, existing)
                return@forEach
            }
            runCatching {
                remote.create(
                    SceneInputDto(
                        name = entity.name,
                        enabled = entity.enabled,
                        cooldownSeconds = entity.cooldownSeconds,
                        trigger = requireNotNull(SceneCanonical.triggerDto(encoded.triggerJson)),
                        actions = requireNotNull(SceneCanonical.actionDtos(encoded.actionsJson)),
                    ),
                )
            }
                .onSuccess { created ->
                    bindRemote(entity.id, created)
                    signatures[signature] = created
                }
                .onFailure { error ->
                    dao.markSyncState(entity.id, SceneSyncState.PENDING.storage, readable(error))
                }
        }
    }

    /**
     * 认领服务端已有场景：只回写 remoteId/version，再做 PULL 合并，
     * 本地更新更晚的字段保留并继续 pending，不会被服务端旧值覆盖。
     */
    private suspend fun bindExisting(localId: Long, remoteScene: RemoteScene) {
        dao.linkRemote(localId, remoteScene.remoteId, remoteScene.version, remoteScene.updatedAt)
        val local = dao.find(localId) ?: return
        applySnapshot(local, remoteScene, MergeMode.PULL)
        rebuildProjections(localId)
    }

    /** POST 成功后回写服务端快照：五个字段以服务端响应为准。 */
    private suspend fun bindRemote(localId: Long, remoteScene: RemoteScene) {
        dao.markUploaded(
            id = localId,
            remoteId = remoteScene.remoteId,
            version = remoteScene.version,
            serverUpdatedAt = remoteScene.updatedAt,
        )
        val local = dao.find(localId) ?: return
        applySnapshot(local, remoteScene, MergeMode.RESPONSE)
        rebuildProjections(localId)
    }

    private fun buildPatch(entity: SceneEntity): ScenePatchDto {
        val triggerDto = SceneCanonical.triggerDto(entity.triggerJson)
            ?: error("triggerJson 无法解析")
        val actionsDto = SceneCanonical.actionDtos(entity.actionsJson)
            ?: error("actionsJson 无法解析")
        return ScenePatchDto(
            name = SceneStringFieldDto(
                entity.name,
                SceneTimestamps.format(entity.nameUpdatedAt),
            ),
            enabled = SceneBoolFieldDto(
                entity.enabled,
                SceneTimestamps.format(entity.enabledUpdatedAt),
            ),
            cooldownSeconds = SceneIntFieldDto(
                entity.cooldownSeconds,
                SceneTimestamps.format(entity.cooldownUpdatedAt),
            ),
            trigger = SceneTriggerFieldDto(
                triggerDto,
                SceneTimestamps.format(entity.triggerUpdatedAt),
            ),
            actions = SceneActionsFieldDto(
                actionsDto,
                SceneTimestamps.format(entity.actionsUpdatedAt),
            ),
        )
    }

    private enum class MergeMode { PULL, RESPONSE }

    /**
     * 字段级 LWW 的本地侧：
     * - PULL：本地在「上次应用服务端快照之后」改过的字段保留，等待 PUT 补传；
     * - RESPONSE：本地时间戳仍晚于服务端响应时间（请求期间又改了）才保留。
     */
    private suspend fun applySnapshot(
        local: SceneEntity,
        remoteScene: RemoteScene,
        mode: MergeMode,
    ) {
        fun keep(value: Long, serverUpdatedAt: Long): Boolean = when (mode) {
            MergeMode.PULL -> local.syncState == SceneSyncState.PENDING.storage &&
                value > local.serverUpdatedAt
            MergeMode.RESPONSE -> value > serverUpdatedAt
        }

        val keepName = keep(local.nameUpdatedAt, remoteScene.updatedAt)
        val keepEnabled = keep(local.enabledUpdatedAt, remoteScene.updatedAt)
        val keepCooldown = keep(local.cooldownUpdatedAt, remoteScene.updatedAt)
        val keepTrigger = keep(local.triggerUpdatedAt, remoteScene.updatedAt)
        val keepActions = keep(local.actionsUpdatedAt, remoteScene.updatedAt)
        dao.mergeServerSnapshot(
            id = local.id,
            keepName = keepName,
            keepEnabled = keepEnabled,
            keepCooldown = keepCooldown,
            keepTrigger = keepTrigger,
            keepActions = keepActions,
            name = remoteScene.name,
            enabled = remoteScene.enabled,
            cooldown = remoteScene.cooldownSeconds,
            triggerJson = remoteScene.triggerJson,
            actionsJson = remoteScene.actionsJson,
            version = remoteScene.version,
            serverUpdatedAt = remoteScene.updatedAt,
        )
        val anyKeep = keepName || keepEnabled || keepCooldown || keepTrigger || keepActions
        if (anyKeep) {
            dao.markSyncState(local.id, SceneSyncState.PENDING.storage, null)
        } else {
            dao.finishMerge(local.id, remoteScene.updatedAt)
            dao.setRulesEnabled(local.id, remoteScene.enabled)
        }
    }

    /** 用落库后的 canonical JSON 重建 rules/scene_actions 投影；投影永远不是真值。 */
    private suspend fun rebuildProjections(sceneId: Long) {
        val row = dao.findRow(sceneId) ?: return
        val triggerJson = row.scene.triggerJson ?: return
        val actionsJson = row.scene.actionsJson ?: return
        val trigger = SceneCanonical.triggerFrom(SceneCanonical.triggerDto(triggerJson))
            ?: return
        val actions = SceneCanonical.actionsFrom(
            SceneCanonical.actionDtos(actionsJson),
            trigger,
        )
        dao.replaceProjections(
            sceneId = sceneId,
            rule = SceneCanonical.ruleEntity(trigger, row.scene.enabled),
            actions = SceneCanonical.actionEntities(actions),
        )
    }

    /** 旧本机场景（没有 canonical JSON）首次上传时，从投影生成可上传快照。 */
    private suspend fun encodedSnapshot(row: SceneWithActions): SceneCanonical.Encoded? {
        if (row.scene.triggerJson != null && row.scene.actionsJson != null) {
            return SceneCanonical.Encoded(row.scene.triggerJson, row.scene.actionsJson)
        }
        val trigger = row.rules.firstOrNull()?.let(SceneCanonical::triggerFromRule)
            ?: return null
        val prepared = if (trigger is SceneTrigger.MetricThreshold &&
            trigger.deviceType.isNullOrBlank()
        ) {
            trigger.copy(deviceType = trigger.deviceMac?.let { deviceTypeOf(it) })
        } else {
            trigger
        }
        val actions = SceneCanonical.actionsFromEntities(row.actions)
        return SceneCanonical.encode(prepared, actions)
    }

    private suspend fun deviceTypeOf(mac: String): String? =
        deviceDao.find(mac)?.deviceType

    private fun unsupportedReason(row: SceneWithActions): String {
        val actions = row.actions
        return when {
            actions.isEmpty() -> "场景没有动作"
            SceneCanonical.serverActionKinds(SceneCanonical.actionsFromEntities(actions)) == null ->
                "动作包含连接/采集/静音等仅本机能力，服务端无法执行"
            row.rules.firstOrNull() == null ->
                "手动场景无需上传；配置受支持的触发条件后可跨设备使用"
            else -> "触发条件未绑定设备或服务端无法表达"
        }
    }

    /** 内容相同且尚未绑定 remoteId 的本地场景（上传去重的另一半）。 */
    private suspend fun findUnlinkedMatch(scene: RemoteScene): SceneEntity? {
        val target = signatureOf(scene)
        return dao.pendingUploads().firstOrNull { entity ->
            val row = dao.findRow(entity.id) ?: return@firstOrNull false
            val encoded = encodedSnapshot(row) ?: return@firstOrNull false
            signature(entity.name, encoded.triggerJson, encoded.actionsJson) == target
        }
    }

    private fun signatureOf(remoteScene: RemoteScene): String = signature(
        name = remoteScene.name,
        // 不同客户端 JSON 键顺序可能不同：先解析再重新编码，得到稳定签名。
        triggerJson = SceneCanonical.triggerDto(remoteScene.triggerJson)
            ?.let { SceneCanonical.triggerJson(it) } ?: remoteScene.triggerJson,
        actionsJson = SceneCanonical.actionDtos(remoteScene.actionsJson)
            ?.let { SceneCanonical.actionsJson(it) } ?: remoteScene.actionsJson,
    )

    private fun signature(name: String, triggerJson: String, actionsJson: String): String =
        "${name.trim()}\u001F$triggerJson\u001F$actionsJson"

    private fun readable(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "同步失败"
}
