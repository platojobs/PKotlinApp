package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.RuleEntity
import com.platojobs.pkotlinapp.data.local.SceneActionEntity
import com.platojobs.pkotlinapp.data.local.SceneDao
import com.platojobs.pkotlinapp.data.local.SceneEntity
import com.platojobs.pkotlinapp.data.local.SceneWithActions
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 场景持久化：动作顺序、编辑替换、删除，以及未知动作类型的安全过滤。 */
class SceneRepositoryTest {

    @Test
    fun `create persists name and ordered actions`() = runBlocking {
        val dao = FakeSceneDao()
        val repository = SceneRepositoryImpl(dao)

        val id = repository.saveScene(
            id = null,
            name = "回家",
            actions = listOf(
                SceneAction(SceneActionType.CONNECT_DEVICE, deviceMac = "AA:01"),
                SceneAction(SceneActionType.MUTE_ALERTS, muteMinutes = 30),
                SceneAction(SceneActionType.SYNC_NOW),
            ),
        )

        assertEquals(1L, id)
        val saved = repository.getScenes().single()
        assertEquals("回家", saved.name)
        assertEquals(
            listOf(
                SceneActionType.CONNECT_DEVICE,
                SceneActionType.MUTE_ALERTS,
                SceneActionType.SYNC_NOW,
            ),
            saved.actions.map { it.type },
        )
        assertEquals("AA:01", saved.actions.first().deviceMac)
        assertEquals(30, saved.actions[1].muteMinutes)
    }

    @Test
    fun `update renames and replaces actions in order`() = runBlocking {
        val dao = FakeSceneDao()
        val repository = SceneRepositoryImpl(dao)
        repository.saveScene(
            id = null,
            name = "回家",
            actions = listOf(SceneAction(SceneActionType.CONNECT_DEVICE, deviceMac = "AA:01")),
        )

        repository.saveScene(
            id = 1L,
            name = "离家",
            actions = listOf(
                SceneAction(SceneActionType.SYNC_NOW),
                SceneAction(SceneActionType.DISCONNECT_DEVICE, deviceMac = "AA:01"),
            ),
        )

        val scene = repository.getScenes().single()
        assertEquals("离家", scene.name)
        assertEquals(
            listOf(SceneActionType.SYNC_NOW, SceneActionType.DISCONNECT_DEVICE),
            scene.actions.map { it.type },
        )
    }

    @Test
    fun `trigger rule is persisted for the engine and follows scene enable state`() = runBlocking {
        val dao = FakeSceneDao()
        val repository = SceneRepositoryImpl(dao)
        val trigger = SceneTrigger.MetricThreshold(
            metric = "temperature",
            comparator = RuleComparator.GREATER,
            thresholdValue = 30.0,
            consecutive = 1,
        )

        val id = repository.saveScene(
            id = null,
            name = "温度越界",
            actions = listOf(SceneAction(SceneActionType.NOTIFY)),
            trigger = trigger,
            enabled = true,
        )

        // 落库的是一条真正的 rules 行，引擎可直接消费，不是平行配置。
        val rule = dao.findRuleForScene(id)!!
        assertEquals(RuleType.METRIC_THRESHOLD.name, rule.type)
        assertEquals("temperature", rule.metric)
        assertEquals(30.0, rule.thresholdValue, 0.0)
        assertEquals(RuleComparator.GREATER.name, rule.comparator)
        assertEquals(id, rule.sceneId)
        assertTrue(rule.enabled)

        assertEquals(trigger, repository.getScene(id)!!.trigger)

        repository.setEnabled(id, false)
        assertFalse(repository.getScene(id)!!.enabled)
        assertFalse(dao.findRuleForScene(id)!!.enabled)
    }

    @Test
    fun `delete removes scene and its actions`() = runBlocking {
        val dao = FakeSceneDao()
        val repository = SceneRepositoryImpl(dao)
        repository.saveScene(
            id = null,
            name = "回家",
            actions = listOf(SceneAction(SceneActionType.SYNC_NOW)),
        )

        repository.deleteScene(1L)

        assertTrue(repository.getScenes().isEmpty())
        assertTrue(dao.actions.isEmpty())
    }

    @Test
    fun `unknown stored action type is filtered instead of crashing`() = runBlocking {
        val dao = FakeSceneDao().apply {
            scenes[1L] = SceneEntity(id = 1, name = "旧场景", createdAt = 1)
            actions += SceneActionEntity(
                id = 1,
                sceneId = 1,
                position = 0,
                type = "PLAY_MUSIC",
            )
            actions += SceneActionEntity(
                id = 2,
                sceneId = 1,
                position = 1,
                type = SceneActionType.SYNC_NOW.name,
            )
        }
        val repository = SceneRepositoryImpl(dao)

        val scene = repository.getScenes().single()

        assertEquals(listOf(SceneActionType.SYNC_NOW), scene.actions.map { it.type })
    }

    @Test
    fun `invalid scene objects are rejected before saving`() {
        val noName = Scene(0, " ", listOf(SceneAction(SceneActionType.SYNC_NOW)))
        assertEquals("场景名称不能为空", noName.invalidReason())

        val noAction = Scene(0, "回家", emptyList())
        assertEquals("至少添加一个动作", noAction.invalidReason())

        val badMute = Scene(
            0,
            "回家",
            listOf(SceneAction(SceneActionType.MUTE_ALERTS, muteMinutes = 45)),
        )
        assertEquals("第 1 个动作：静音时长只支持 10/30/60 分钟", badMute.invalidReason())
    }

    private class FakeSceneDao : SceneDao {
        val scenes = LinkedHashMap<Long, SceneEntity>()
        val actions = mutableListOf<SceneActionEntity>()
        val rules = mutableListOf<RuleEntity>()
        private var nextSceneId = 1L
        private var nextActionId = 1L
        private var nextRuleId = 1L

        override suspend fun markSyncStateKeepingRetry(
            id: Long,
            syncState: String,
            reason: String?,
        ) {
            scenes[id]?.let { scenes[id] = it.copy(syncState = syncState, syncError = reason) }
        }

        override suspend fun markRejected(id: Long, code: String?) {
            scenes[id]?.let { scenes[id] = it.copy(syncState = "rejected", syncError = code) }
        }

        override suspend fun markRetry(id: Long, reason: String?, retryAt: Long?) {
            scenes[id]?.let {
                scenes[id] = it.copy(syncState = "pending", syncError = reason, retryAfterAt = retryAt)
            }
        }

        override suspend fun updateDeviceMissing(id: Long, missing: Boolean) {
            scenes[id]?.let { scenes[id] = it.copy(deviceMissing = missing) }
        }

        override fun observeAll(): Flow<List<SceneWithActions>> = flowOf(snapshot())

        override suspend fun getAll(): List<SceneWithActions> = snapshot()

        override suspend fun find(id: Long): SceneEntity? = scenes[id]

        override suspend fun insertScene(scene: SceneEntity): Long {
            val id = nextSceneId++
            scenes[id] = scene.copy(id = id)
            return id
        }

        override suspend fun insertActions(actions: List<SceneActionEntity>) {
            this.actions += actions.map { it.copy(id = nextActionId++) }
        }

        override suspend fun rename(id: Long, name: String) {
            scenes[id]?.let { scenes[id] = it.copy(name = name) }
        }

        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            scenes[id]?.let { scenes[id] = it.copy(enabled = enabled) }
        }

        override suspend fun deleteActions(sceneId: Long) {
            actions.removeAll { it.sceneId == sceneId }
        }

        override suspend fun delete(id: Long) {
            scenes.remove(id)
            deleteActions(id)
        }

        override suspend fun insertRule(rule: RuleEntity): Long {
            rules += rule.copy(id = nextRuleId++)
            return nextRuleId - 1
        }

        override suspend fun findRuleForScene(sceneId: Long): RuleEntity? =
            rules.firstOrNull { it.sceneId == sceneId }

        override suspend fun updateRuleForScene(
            sceneId: Long,
            type: String,
            threshold: Int,
            metric: String?,
            comparator: String,
            thresholdValue: Double,
            consecutive: Int,
            intervalMinutes: Int?,
            enabled: Boolean,
        ) {
            val index = rules.indexOfFirst { it.sceneId == sceneId }
            if (index >= 0) {
                rules[index] = rules[index].copy(
                    type = type,
                    threshold = threshold,
                    metric = metric,
                    comparator = comparator,
                    thresholdValue = thresholdValue,
                    consecutive = consecutive,
                    intervalMinutes = intervalMinutes,
                    enabled = enabled,
                )
            }
        }

        override suspend fun setRulesEnabled(sceneId: Long, enabled: Boolean) {
            rules.replaceAll {
                if (it.sceneId == sceneId) it.copy(enabled = enabled) else it
            }
        }

        override suspend fun deleteRules(sceneId: Long) {
            rules.removeAll { it.sceneId == sceneId }
        }

        override suspend fun getAllIncludingDeleted(): List<SceneWithActions> = snapshot()

        override suspend fun findRow(id: Long): SceneWithActions? = snapshot().firstOrNull { it.scene.id == id }

        override suspend fun findByRemoteId(remoteId: String): SceneEntity? =
            scenes.values.firstOrNull { it.remoteId == remoteId }

        override suspend fun linkRemote(
            id: Long,
            remoteId: String,
            version: Long,
            serverUpdatedAt: Long,
        ) {
            scenes[id]?.let {
                scenes[id] = it.copy(
                    remoteId = remoteId,
                    version = version,
                    syncState = "pending",
                    syncError = null,
                    serverUpdatedAt = serverUpdatedAt,
                )
            }
        }

        override suspend fun pendingUploads(): List<SceneEntity> =
            scenes.values.filter { it.syncState == "pending" && it.remoteId == null }

        override suspend fun pendingUpdates(): List<SceneEntity> =
            scenes.values.filter { it.syncState == "pending" && it.remoteId != null }

        override suspend fun pendingDeletes(): List<SceneEntity> =
            scenes.values.filter { it.syncState == "pending" && it.deletedAt != null }

        override suspend fun markUploaded(
            id: Long,
            remoteId: String,
            version: Long,
            serverUpdatedAt: Long,
        ) {
            scenes[id]?.let {
                scenes[id] = it.copy(
                    remoteId = remoteId,
                    version = version,
                    syncState = "synced",
                    serverUpdatedAt = serverUpdatedAt,
                    nameUpdatedAt = serverUpdatedAt,
                    enabledUpdatedAt = serverUpdatedAt,
                    cooldownUpdatedAt = serverUpdatedAt,
                    triggerUpdatedAt = serverUpdatedAt,
                    actionsUpdatedAt = serverUpdatedAt,
                )
            }
        }

        override suspend fun markSyncState(id: Long, syncState: String, reason: String?) {
            scenes[id]?.let { scenes[id] = it.copy(syncState = syncState, syncError = reason) }
        }

        override suspend fun mergeServerSnapshot(
            id: Long,
            keepName: Boolean,
            keepEnabled: Boolean,
            keepCooldown: Boolean,
            keepTrigger: Boolean,
            keepActions: Boolean,
            name: String,
            enabled: Boolean,
            cooldown: Int,
            triggerJson: String,
            actionsJson: String,
            version: Long,
            serverUpdatedAt: Long,
        ) {
            scenes[id]?.let {
                scenes[id] = it.copy(
                    name = if (keepName) it.name else name,
                    enabled = if (keepEnabled) it.enabled else enabled,
                    cooldownSeconds = if (keepCooldown) it.cooldownSeconds else cooldown,
                    triggerJson = if (keepTrigger) it.triggerJson else triggerJson,
                    actionsJson = if (keepActions) it.actionsJson else actionsJson,
                    version = version,
                    serverUpdatedAt = serverUpdatedAt,
                )
            }
        }

        override suspend fun finishMerge(id: Long, serverUpdatedAt: Long) {
            scenes[id]?.let { scenes[id] = it.copy(syncState = "synced", serverUpdatedAt = serverUpdatedAt) }
        }

        override suspend fun tombstone(id: Long, timestamp: Long) {
            scenes[id]?.let { scenes[id] = it.copy(deletedAt = timestamp, syncState = "pending") }
        }

        override suspend fun markServerDeleted(id: Long, timestamp: Long) {
            scenes[id]?.let { scenes[id] = it.copy(deletedAt = timestamp, syncState = "synced") }
        }

        override suspend fun reviveFromServer(id: Long) {
            scenes[id]?.let { scenes[id] = it.copy(deletedAt = null) }
        }

        override suspend fun updateNameForSync(id: Long, name: String, updatedAt: Long) {
            scenes[id]?.let { scenes[id] = it.copy(name = name, nameUpdatedAt = updatedAt, syncState = "pending") }
        }

        override suspend fun updateEnabledForSync(id: Long, enabled: Boolean, updatedAt: Long) {
            scenes[id]?.let { scenes[id] = it.copy(enabled = enabled, enabledUpdatedAt = updatedAt, syncState = "pending") }
        }

        override suspend fun updateCooldownForSync(id: Long, cooldown: Int, updatedAt: Long) {
            scenes[id]?.let { scenes[id] = it.copy(cooldownSeconds = cooldown, cooldownUpdatedAt = updatedAt, syncState = "pending") }
        }

        override suspend fun updateTriggerForSync(id: Long, json: String, updatedAt: Long) {
            scenes[id]?.let { scenes[id] = it.copy(triggerJson = json, triggerUpdatedAt = updatedAt, syncState = "pending") }
        }

        override suspend fun updateActionsForSync(id: Long, json: String, updatedAt: Long) {
            scenes[id]?.let { scenes[id] = it.copy(actionsJson = json, actionsUpdatedAt = updatedAt, syncState = "pending") }
        }

        private fun snapshot(): List<SceneWithActions> = scenes.values
            .sortedBy { it.createdAt }
            .map { scene ->
                SceneWithActions(
                    scene = scene,
                    actions = actions.filter { it.sceneId == scene.id },
                    rules = rules.filter { it.sceneId == scene.id },
                )
            }
    }
}
