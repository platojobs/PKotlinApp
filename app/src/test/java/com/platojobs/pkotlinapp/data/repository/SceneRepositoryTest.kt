package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.SceneActionEntity
import com.platojobs.pkotlinapp.data.local.SceneDao
import com.platojobs.pkotlinapp.data.local.SceneEntity
import com.platojobs.pkotlinapp.data.local.SceneWithActions
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        private var nextSceneId = 1L
        private var nextActionId = 1L

        override fun observeAll(): Flow<List<SceneWithActions>> = flowOf(snapshot())

        override suspend fun getAll(): List<SceneWithActions> = snapshot()

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

        override suspend fun deleteActions(sceneId: Long) {
            actions.removeAll { it.sceneId == sceneId }
        }

        override suspend fun delete(id: Long) {
            scenes.remove(id)
            deleteActions(id)
        }

        private fun snapshot(): List<SceneWithActions> = scenes.values
            .sortedBy { it.createdAt }
            .map { scene ->
                SceneWithActions(scene, actions.filter { it.sceneId == scene.id })
            }
    }
}
