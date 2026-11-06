package com.platojobs.pkotlinapp.data.scene

import androidx.room.Room
import com.platojobs.pkotlinapp.data.local.AppDatabase
import com.platojobs.pkotlinapp.data.local.DeviceDao
import com.platojobs.pkotlinapp.data.local.DeviceEntity
import com.platojobs.pkotlinapp.data.local.RuleEntity
import com.platojobs.pkotlinapp.data.local.SceneActionEntity
import com.platojobs.pkotlinapp.data.local.SceneDao
import com.platojobs.pkotlinapp.data.local.SceneEntity
import com.platojobs.pkotlinapp.data.remote.SceneActionDto
import com.platojobs.pkotlinapp.data.remote.SceneDeviceRefDto
import com.platojobs.pkotlinapp.data.remote.SceneInputDto
import com.platojobs.pkotlinapp.data.remote.ScenePatchDto
import com.platojobs.pkotlinapp.data.remote.SceneTimestamps
import com.platojobs.pkotlinapp.data.remote.SceneTriggerDto
import com.platojobs.pkotlinapp.data.repository.RoomSceneDeviceLookup
import com.platojobs.pkotlinapp.data.repository.RuleEventRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.RuleRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.SceneRepositoryImpl
import com.platojobs.pkotlinapp.data.repository.SceneRunRepositoryImpl
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import com.platojobs.pkotlinapp.domain.rule.RuleTextProvider
import com.platojobs.pkotlinapp.domain.scene.RemoteScene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.domain.scene.SceneExecutor
import com.platojobs.pkotlinapp.domain.scene.SceneRejectedException
import com.platojobs.pkotlinapp.domain.scene.SceneRetryableException
import com.platojobs.pkotlinapp.domain.scene.SceneRuntime
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 场景同步真机路径（真实 Room + 内存假远端）：
 * - 首次上传逐条 POST 并写回 remoteId/version，不静默丢弃旧场景；
 * - 基线签名去重，命中时只回写 remoteId；
 * - 字段级 LWW：两端改不同字段都保留；
 * - 断网 pending 保留，恢复后 POST/PUT 补传；
 * - 本地/服务端软删；
 * - deviceMissing 列表标注 + 执行跳过并记录失败日志；
 * - change_log 场景合并后由 RuleEngine 真正执行（含占位符渲染）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SceneSyncerTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SceneDao
    private lateinit var deviceDao: DeviceDao
    private lateinit var repo: SceneRepositoryImpl
    private lateinit var remote: FakeSceneRemote
    private lateinit var syncer: SceneSyncer

    private var clock = 1_000L
    private var scenesChanged = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            org.robolectric.RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.sceneDao()
        deviceDao = db.deviceDao()
        remote = FakeSceneRemote(now = { clock })
        repo = SceneRepositoryImpl(
            dao = dao,
            deviceLookup = RoomSceneDeviceLookup(deviceDao),
            now = { clock },
        )
        syncer = SceneSyncer(
            dao = dao,
            deviceDao = deviceDao,
            remote = remote,
            now = { clock },
            onScenesChanged = { scenesChanged++ },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `first upload posts local scene and writes back remoteId and version`() = runBlocking {
        val id = saveLocal("回家提醒", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())
        assertEquals("pending", dao.find(id)!!.syncState)

        syncer.sync()

        val created = remote.scenes.values.single()
        assertEquals(1, remote.createCalls)
        assertEquals(1, scenesChanged)
        val entity = dao.find(id)!!
        assertEquals(created.remoteId, entity.remoteId)
        assertEquals(created.version, entity.version)
        assertEquals("synced", entity.syncState)
        assertNull(entity.syncError)
        assertEquals(created.updatedAt, entity.serverUpdatedAt)

        // 投影由服务端 JSON 重建，RuleEngine 可直接消费。
        val rule = dao.findRuleForScene(id)!!
        assertEquals(RuleType.DEVICE_OFFLINE.name, rule.type)
        assertEquals(MAC, rule.deviceMac)
        assertEquals(600, rule.offlineSeconds)
        assertTrue(rule.enabled)
    }

    @Test
    fun `first upload dedupes by content signature instead of creating duplicate`() = runBlocking {
        remote.seed(
            id = "shared",
            name = "回家提醒",
            dto = offlineTriggerDto(MAC, 600),
            actions = listOf(notifyActionDto()),
            at = 1_000,
        )
        val id = saveLocal("回家提醒", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())

        syncer.sync()

        assertEquals(0, remote.createCalls)
        assertEquals("shared", dao.find(id)!!.remoteId)
        assertEquals("synced", dao.find(id)!!.syncState)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `lww keeps local name while applying server enabled`() = runBlocking {
        val actions = listOf(SceneAction(SceneActionType.NOTIFY, title = "回家", body = "已到家"))
        val id = saveLocal("原名称", SceneTrigger.DeviceOffline(MAC, 600), actions)
        dao.markUploaded(id, "rid-1", 1, 1_000)
        remote.seed("rid-1", "原名称", offlineTriggerDto(MAC, 600), listOf(notifyActionDto()), at = 1_000)
        // 另一端只改了 enabled。
        remote.change("rid-1", at = 1_500, enabled = false)

        clock = 2_000
        repo.saveScene(id, "本地改名", actions, SceneTrigger.DeviceOffline(MAC, 600), true)
        assertEquals("pending", dao.find(id)!!.syncState)

        syncer.sync()

        val local = dao.find(id)!!
        // 两端改的是不同字段：本地改名保留，服务端停用也生效。
        assertEquals("本地改名", local.name)
        assertFalse(local.enabled)
        assertEquals("synced", local.syncState)
        val server = remote.scenes.getValue("rid-1")
        assertEquals("本地改名", server.name)
        assertFalse(server.enabled)

        // PUT 逐字段带各自时间戳，服务端才能按字段判定新旧。
        val patch = remote.lastPatch!!
        assertEquals(2_000L, SceneTimestamps.parse(patch.name!!.updatedAt))
        assertEquals(1_500L, SceneTimestamps.parse(patch.enabled!!.updatedAt))
        assertEquals(1_500L, SceneTimestamps.parse(patch.cooldownSeconds!!.updatedAt))
    }

    @Test
    fun `offline create stays pending and uploads when network returns`() = runBlocking {
        remote.failCreate = true
        val id = saveLocal("离线新建", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())

        syncer.sync()

        val offline = dao.find(id)!!
        assertNull(offline.remoteId)
        assertEquals("pending", offline.syncState)
        assertNotNull(offline.syncError)
        assertEquals(1, dao.getAll().size)

        remote.failCreate = false
        clock = 2_000
        syncer.sync()

        val synced = dao.find(id)!!
        assertNotNull(synced.remoteId)
        assertEquals("synced", synced.syncState)
        assertNull(synced.syncError)
        assertEquals(1, remote.createCalls)
    }

    @Test
    fun `offline rename stays pending then PUTs with remoteId`() = runBlocking {
        val id = saveLocal("原始名称", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())
        syncer.sync()
        val remoteId = dao.find(id)!!.remoteId!!

        remote.failUpdate = true
        clock = 2_000
        repo.saveScene(id, "离线改名", notifyAction(), SceneTrigger.DeviceOffline(MAC, 600), true)
        syncer.sync()

        assertEquals("pending", dao.find(id)!!.syncState)
        assertNotNull(dao.find(id)!!.syncError)
        assertEquals("原始名称", remote.scenes.getValue(remoteId).name)

        remote.failUpdate = false
        clock = 3_000
        syncer.sync()

        assertEquals("离线改名", dao.find(id)!!.name)
        assertEquals("synced", dao.find(id)!!.syncState)
        assertEquals("离线改名", remote.scenes.getValue(remoteId).name)
        assertEquals(1, remote.updateCalls)
    }

    @Test
    fun `local delete is sent as DELETE and removed after ack`() = runBlocking {
        val id = saveLocal("待删除", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())
        syncer.sync()

        clock = 2_000
        repo.deleteScene(id)
        assertEquals("pending", dao.find(id)!!.syncState)
        assertEquals(0, dao.getAll().size)

        syncer.sync()

        assertEquals(1, remote.deleteCalls)
        assertTrue(dao.getAllIncludingDeleted().isEmpty())
    }

    @Test
    fun `server soft delete marks tombstone without wiping history`() = runBlocking {
        val id = saveLocal("被删场景", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())
        syncer.sync()
        val remoteId = dao.find(id)!!.remoteId!!
        remote.scenes.remove(remoteId)

        clock = 2_000
        syncer.sync()

        assertTrue(dao.getAll().isEmpty())
        val row = dao.getAllIncludingDeleted().single()
        assertNotNull(row.scene.deletedAt)
        assertEquals("synced", row.scene.syncState)
        assertFalse(dao.findRuleForScene(id)!!.enabled)
    }

    @Test
    fun `change log scene delete marks local tombstone`() = runBlocking {
        val id = saveLocal("被删场景", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())
        syncer.sync()
        val snapshot = remote.scenes.values.single()

        clock = 2_000
        syncer.applyChanges(listOf(snapshot.copy(deleted = true, updatedAt = 2_000)))

        assertTrue(dao.getAll().isEmpty())
        assertEquals("synced", dao.find(id)!!.syncState)
        assertNotNull(dao.find(id)!!.deletedAt)
        assertFalse(dao.findRuleForScene(id)!!.enabled)
    }

    @Test
    fun `change log scene upsert creates executable local scene`() = runBlocking {
        val remoteScene = remote.seed(
            id = "rid-cl",
            name = "云端场景",
            dto = offlineTriggerDto(MAC, 600),
            actions = listOf(notifyActionDto()),
            at = 1_000,
        )

        syncer.applyChanges(listOf(remoteScene))

        assertEquals(1, scenesChanged)
        val row = dao.getAll().single()
        assertEquals("rid-cl", row.scene.remoteId)
        assertEquals("云端场景", row.scene.name)
        assertEquals("synced", row.scene.syncState)
        assertEquals(RuleType.DEVICE_OFFLINE.name, row.rules.single().type)
        assertTrue(row.rules.single().enabled)
    }

    @Test
    fun `device missing scene is flagged skipped and logged`() = runBlocking {
        val unknownMac = "FF:EE:DD:CC:BB:AA"
        val id = saveLocal("设备已移除", SceneTrigger.DeviceOffline(unknownMac, 600), notifyAction())
        val scene = repo.getScene(id)!!
        assertTrue(scene.deviceMissing)
        assertTrue(repo.observeScenes().first().single().deviceMissing)

        val runtime = FakeRuntime()
        val runRepo = SceneRunRepositoryImpl(db.sceneRunDao())
        val runner = SceneTriggerRunner(
            sceneRepository = repo,
            sceneRunRepository = runRepo,
            executor = SceneExecutor(runtime, canConnect = { true }),
            now = { clock },
        )
        val result = runner.runManually(scene)

        assertEquals(1, result.failureCount)
        assertTrue(runtime.calls.isEmpty())
        assertTrue(result.outcomes.single().message.contains("设备已移除"))

        val run = runRepo.getRecent(1).single()
        assertEquals(1, run.failureCount)
        assertEquals("设备已移除", run.sceneName)
        assertNotNull(run.failureReason)
        assertTrue(run.detail.contains("设备已移除"))

        // 设备重新出现在本地目录后，标注实时消失。
        deviceDao.insertOrUpdate(device(unknownMac))
        assertFalse(repo.getScene(id)!!.deviceMissing)
    }

    @Test
    fun `legacy local scene is uploaded with device type resolved`() = runBlocking {
        deviceDao.insertOrUpdate(device(MAC, deviceType = "TH_SENSOR"))
        val id = dao.createSceneWithRule(
            scene = legacyScene("高温提醒"),
            rule = RuleEntity(
                type = RuleType.METRIC_THRESHOLD.name,
                threshold = 0,
                enabled = true,
                createdAt = 900,
                lastTriggeredTimestamp = 0,
                metric = "temperature",
                comparator = RuleComparator.GREATER.name,
                thresholdValue = 30.0,
                consecutive = 2,
                deviceMac = MAC,
            ),
            actions = listOf(
                SceneActionEntity(
                    sceneId = 0,
                    position = 0,
                    type = SceneActionType.NOTIFY.name,
                    title = "高温",
                    body = "{device} {metric} 超过阈值",
                ),
            ),
        )

        syncer.sync()

        assertEquals(1, remote.createCalls)
        val created = remote.scenes.values.single()
        val trigger = created.trigger as SceneTrigger.MetricThreshold
        assertEquals("TH_SENSOR", trigger.deviceType)
        assertEquals("temperature", trigger.metric)
        assertEquals(2, trigger.consecutive)
        val entity = dao.find(id)!!
        assertEquals(created.remoteId, entity.remoteId)
        assertNotNull(entity.triggerJson)
        assertEquals("synced", entity.syncState)
    }

    @Test
    fun `unsupported legacy scene is marked local only and never dropped`() = runBlocking {
        val id = dao.createSceneWithRule(
            scene = legacyScene("本机连接场景"),
            rule = RuleEntity(
                type = RuleType.DEVICE_OFFLINE.name,
                threshold = 0,
                enabled = true,
                createdAt = 900,
                lastTriggeredTimestamp = 0,
                deviceMac = MAC,
                offlineSeconds = 600,
            ),
            actions = listOf(
                SceneActionEntity(
                    sceneId = 0,
                    position = 0,
                    type = SceneActionType.CONNECT_DEVICE.name,
                    deviceMac = MAC,
                ),
            ),
        )

        syncer.sync()

        assertEquals(0, remote.createCalls)
        val entity = dao.find(id)!!
        assertEquals("local_only", entity.syncState)
        assertNotNull(entity.syncError)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `server scene created by change log is executed by RuleEngine`() = runBlocking {
        val remoteScene = remote.seed(
            id = "rid-engine",
            name = "云端离线场景",
            dto = offlineTriggerDto(MAC, 600),
            actions = listOf(
                SceneActionDto(
                    kind = "notify",
                    title = "{device} 离线",
                    body = "{device} 已离线",
                ),
            ),
            at = 1_000,
        )
        syncer.applyChanges(listOf(remoteScene))
        deviceDao.insertOrUpdate(device(MAC, lastSeen = 1_000))

        val runtime = FakeRuntime()
        val runRepo = SceneRunRepositoryImpl(db.sceneRunDao())
        val runner = SceneTriggerRunner(
            sceneRepository = repo,
            sceneRunRepository = runRepo,
            executor = SceneExecutor(runtime, canConnect = { true }),
            now = { clock },
        )
        clock = 1_000 + 600_000
        val engine = RuleEngine(
            ruleRepository = RuleRepositoryImpl(db.ruleDao()),
            eventRepository = RuleEventRepositoryImpl(db.ruleEventDao()),
            textProvider = FakeTextProvider(),
            triggerObserver = runner,
            now = { clock },
        )

        val events = engine.onOfflineCheck(
            lastSeenByMac = mapOf(MAC to 1_000L),
            displayNameOf = { "客厅传感器" },
        )

        assertEquals(1, events.size)
        assertEquals(RuleType.DEVICE_OFFLINE, events.single().ruleType)
        // 场景动作真实执行，notify 模板按触发上下文渲染。
        assertTrue(runtime.calls.single().contains("客厅传感器 离线"))
        val run = runRepo.getRecent(1).single()
        assertEquals(1, run.successCount)
        assertTrue(run.detail.contains("客厅传感器 离线"))
        // body 同样按占位符渲染后交给通知运行时。
        assertTrue(runtime.calls.single().contains("客厅传感器 已离线"))

        // 设备在阈值内重新出现时恢复，不重复触发。
        clock = 1_000 + 600_000 + 1_000
        assertTrue(
            engine.onOfflineCheck(
                lastSeenByMac = mapOf(MAC to clock - 1_000),
                displayNameOf = { "客厅传感器" },
            ).isEmpty(),
        )
    }

    // ---- helpers ----

    private suspend fun saveLocal(
        name: String,
        trigger: SceneTrigger,
        actions: List<SceneAction>,
    ): Long = repo.saveScene(
        id = null,
        name = name,
        actions = actions,
        trigger = trigger,
        enabled = true,
    )

    private fun notifyAction(): List<SceneAction> =
        listOf(SceneAction(SceneActionType.NOTIFY, title = "场景提醒", body = "场景已触发"))

    private fun legacyScene(name: String) = SceneEntity(
        name = name,
        createdAt = 900,
        syncState = "pending",
        nameUpdatedAt = 900,
        enabledUpdatedAt = 900,
        cooldownUpdatedAt = 900,
        triggerUpdatedAt = 900,
        actionsUpdatedAt = 900,
    )

    private fun device(
        mac: String,
        deviceType: String? = null,
        lastSeen: Long = clock,
    ) = DeviceEntity(
        mac = mac,
        name = mac,
        lastRssi = -60,
        lastSeenTimestamp = lastSeen,
        id = mac,
        platform = "android",
        externalId = mac,
        updatedAt = lastSeen,
        deletedAt = null,
        syncState = "synced",
        deviceType = deviceType,
    )

    private class FakeTextProvider : RuleTextProvider {
        override fun lowRssi(deviceName: String, rssi: Int, threshold: Int): String =
            "$deviceName 信号 $rssi 低于 $threshold"

        override fun disconnected(deviceName: String): String = "$deviceName 已离线"

        override fun bondLost(deviceName: String): String = "$deviceName 绑定丢失"

        override fun scheduled(intervalMinutes: Int): String = "定时 $intervalMinutes 分钟"

        override fun metricThreshold(
            deviceName: String,
            metric: String,
            value: Double,
            unit: String,
            comparator: RuleComparator,
            threshold: Double,
        ): String = "$deviceName $metric $comparator $threshold$unit"
    }

    /** 4xx 终态：落 rejected 且不再自动重试；用户改内容后才重新入队。 */
    @Test
    fun `4xx rejection is terminal until user edits`() = runBlocking {
        val id = saveLocal("回家", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())
        syncer.sync()
        assertEquals("synced", dao.find(id)!!.syncState)

        dao.updateNameForSync(id, "新名字", 2_000)
        remote.failure = SceneRejectedException("SCENE_ACTION_INVALID")
        syncer.sync()

        val rejected = dao.find(id)!!
        assertEquals("rejected", rejected.syncState)
        assertEquals("SCENE_ACTION_INVALID", rejected.syncError)
        val attempts = remote.updateAttempts

        clock += 10_000
        syncer.sync()
        assertEquals("终态不应自动重试", attempts, remote.updateAttempts)

        dao.updateNameForSync(id, "再改一次", 3_000)
        remote.failure = null
        syncer.sync()
        assertEquals("修改内容后应重新提交", attempts + 1, remote.updateAttempts)
        assertEquals("synced", dao.find(id)!!.syncState)
    }

    /** 429：记录 Retry-After，到点前跳过，到点后继续补传。 */
    @Test
    fun `429 retry-after defers next attempt`() = runBlocking {
        val id = saveLocal("回家", SceneTrigger.DeviceOffline(MAC, 600), notifyAction())
        syncer.sync()

        dao.updateNameForSync(id, "新名字", 2_000)
        remote.failure = SceneRetryableException(retryAfterSeconds = 60)
        syncer.sync()

        val deferred = dao.find(id)!!
        assertEquals("pending", deferred.syncState)
        assertEquals(clock + 60_000, deferred.retryAfterAt)
        val attempts = remote.updateAttempts

        syncer.sync()
        assertEquals("未到 Retry-After 不应重试", attempts, remote.updateAttempts)

        clock += 60_000
        remote.failure = null
        syncer.sync()
        assertEquals(attempts + 1, remote.updateAttempts)
        assertNull(dao.find(id)!!.retryAfterAt)
    }

    private class FakeRuntime : SceneRuntime {
        val calls = mutableListOf<String>()
        private var connected: String? = null
        private var collecting = false

        override suspend fun connectDevice(mac: String): Boolean {
            calls += "connect:$mac"
            connected = mac
            return true
        }

        override suspend fun disconnectDevice(mac: String): Boolean {
            calls += "disconnect:$mac"
            if (connected != mac) return false
            connected = null
            return true
        }

        override suspend fun connectedMac(): String? = connected

        override fun collectionActive(): Boolean = collecting

        override fun activeCollectionMac(): String? = null

        override suspend fun startCollection(mac: String): Boolean {
            calls += "start:$mac"
            collecting = true
            return true
        }

        override fun stopCollection(): Boolean {
            collecting = false
            return true
        }

        override fun muteAlerts(minutes: Int) {
            calls += "mute:$minutes"
        }

        override fun syncNow() {
            calls += "sync"
        }

        override suspend fun notify(title: String, body: String): Boolean {
            calls += "notify:$title|$body"
            return true
        }

        override suspend fun markDevice(mac: String): Boolean {
            calls += "mark:$mac"
            return true
        }
    }

    /**
     * 内存假远端：create/update 都按字段时间戳做 LWW，
     * 与真实服务端语义一致，便于验证「两端改不同字段都保留」。
     */
    private class FakeSceneRemote(private val now: () -> Long) : SceneRemote {

        val scenes = linkedMapOf<String, RemoteScene>()
        private val fieldTimestamps = mutableMapOf<String, MutableMap<String, Long>>()

        var createCalls = 0
            private set
        var updateCalls = 0
            private set
        var deleteCalls = 0
            private set
        var lastPatch: ScenePatchDto? = null
            private set

        var failCreate = false
        var failUpdate = false

        /** 非空时 create/update 直接抛出该异常，用于验证终态/可重试分类。 */
        var failure: Throwable? = null
        var updateAttempts = 0
            private set

        fun seed(
            id: String,
            name: String,
            dto: SceneTriggerDto,
            actions: List<SceneActionDto>,
            enabled: Boolean = true,
            at: Long,
        ): RemoteScene {
            val trigger = SceneCanonical.triggerFrom(dto)
            val scene = RemoteScene(
                remoteId = id,
                name = name,
                enabled = enabled,
                cooldownSeconds = 300,
                version = 1,
                createdAt = at,
                updatedAt = at,
                deleted = false,
                triggerJson = requireNotNull(SceneCanonical.triggerJson(dto)),
                actionsJson = requireNotNull(SceneCanonical.actionsJson(actions)),
                trigger = trigger,
                actions = SceneCanonical.actionsFrom(actions, trigger),
            )
            scenes[id] = scene
            fieldTimestamps[id] = mutableMapOf(
                "name" to at,
                "enabled" to at,
                "cooldownSeconds" to at,
                "trigger" to at,
                "actions" to at,
            )
            return scene
        }

        /** 模拟另一端修改单个字段。 */
        fun change(id: String, at: Long, enabled: Boolean? = null, name: String? = null) {
            val current = scenes.getValue(id)
            val stamps = fieldTimestamps.getValue(id)
            var next = current
            if (name != null) {
                stamps["name"] = at
                next = next.copy(name = name)
            }
            if (enabled != null) {
                stamps["enabled"] = at
                next = next.copy(enabled = enabled)
            }
            scenes[id] = next.copy(version = current.version + 1, updatedAt = at)
        }

        override suspend fun list(): List<RemoteScene> = scenes.values.toList()

        override suspend fun create(input: SceneInputDto): RemoteScene {
            failure?.let { throw it }
            if (failCreate) throw IOException("网络不可用")
            createCalls++
            val id = "remote-$createCalls"
            return seed(
                id = id,
                name = input.name,
                dto = input.trigger,
                actions = input.actions,
                enabled = input.enabled,
                at = now(),
            )
        }

        override suspend fun update(remoteId: String, patch: ScenePatchDto): RemoteScene {
            updateAttempts++
            failure?.let { throw it }
            if (failUpdate) throw IOException("网络不可用")
            updateCalls++
            lastPatch = patch
            val current = scenes[remoteId] ?: throw SceneGoneException()
            val stamps = fieldTimestamps[remoteId] ?: mutableMapOf()
            var changed = false
            var newest = current.updatedAt
            var next = current

            fun accept(field: String, updatedAt: String): Boolean {
                val stamp = SceneTimestamps.parse(updatedAt) ?: return false
                if (stamp <= (stamps[field] ?: Long.MIN_VALUE)) return false
                stamps[field] = stamp
                changed = true
                if (stamp > newest) newest = stamp
                return true
            }

            patch.name?.let { if (accept("name", it.updatedAt)) next = next.copy(name = it.value) }
            patch.enabled?.let { if (accept("enabled", it.updatedAt)) next = next.copy(enabled = it.value) }
            patch.cooldownSeconds?.let {
                if (accept("cooldownSeconds", it.updatedAt)) next = next.copy(cooldownSeconds = it.value)
            }
            patch.trigger?.let {
                if (accept("trigger", it.updatedAt)) {
                    next = next.copy(
                        triggerJson = requireNotNull(SceneCanonical.triggerJson(it.value)),
                        trigger = SceneCanonical.triggerFrom(it.value),
                    )
                }
            }
            patch.actions?.let {
                if (accept("actions", it.updatedAt)) {
                    next = next.copy(
                        actionsJson = requireNotNull(SceneCanonical.actionsJson(it.value)),
                        actions = SceneCanonical.actionsFrom(it.value, next.trigger),
                    )
                }
            }
            scenes[remoteId] = next.copy(
                version = current.version + if (changed) 1 else 0,
                updatedAt = newest,
            )
            return scenes.getValue(remoteId)
        }

        override suspend fun delete(remoteId: String) {
            deleteCalls++
            if (scenes.remove(remoteId) == null) throw SceneGoneException()
        }
    }

    private companion object {
        const val MAC = "AA:BB:CC:DD:EE:01"

        fun offlineTriggerDto(mac: String, seconds: Int) = SceneTriggerDto(
            kind = "device_offline",
            device = SceneDeviceRefDto(platform = "android", externalId = mac),
            offlineSeconds = seconds,
        )

        fun notifyActionDto() = SceneActionDto(kind = "notify", title = "场景提醒", body = "场景已触发")
    }
}
