package com.platojobs.pkotlinapp.domain.scene

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 场景执行语义：顺序执行、失败不阻断、真实调用可断言、汇总口径。 */
class SceneExecutorTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    @Test
    fun `connect action really calls runtime and reports progress`() = runBlocking {
        val runtime = FakeRuntime()
        val progress = mutableListOf<Pair<Int, SceneAction>>()
        val executor = SceneExecutor(runtime, canConnect = { true })
        val scene = scene(
            SceneAction(SceneActionType.CONNECT_DEVICE, deviceMac = mac),
        )

        val result = executor.run(scene) { index, _, action -> progress += index to action }

        assertEquals(listOf("connect:$mac"), runtime.calls)
        assertTrue(result.allSucceeded)
        assertEquals(1, progress.size)
        assertEquals(0, progress.first().first)
        assertEquals(SceneActionType.CONNECT_DEVICE, progress.first().second.type)
    }

    @Test
    fun `missing permission fails connect without touching runtime`() = runBlocking {
        val runtime = FakeRuntime()
        val executor = SceneExecutor(runtime, canConnect = { false })

        val result = executor.run(
            scene(SceneAction(SceneActionType.CONNECT_DEVICE, deviceMac = mac)),
        )

        assertEquals(0, runtime.calls.size)
        assertFalse(result.allSucceeded)
        assertTrue(result.outcomes.single().message.contains("缺少蓝牙连接权限"))
    }

    @Test
    fun `disconnect failure does not block following actions`() = runBlocking {
        val runtime = FakeRuntime().apply { connected = "OTHER" }
        val executor = SceneExecutor(runtime, canConnect = { true })
        val result = executor.run(
            scene(
                SceneAction(SceneActionType.DISCONNECT_DEVICE, deviceMac = mac),
                SceneAction(SceneActionType.MUTE_ALERTS, muteMinutes = 30),
            ),
        )

        assertEquals(1, result.failureCount)
        assertEquals(1, result.successCount)
        assertEquals(listOf("disconnect:$mac", "mute:30"), runtime.calls)
        assertTrue(result.outcomes.first().message.contains("设备未连接"))
        assertEquals("静音告警", result.outcomes[1].action.type.displayLabel())
    }

    @Test
    fun `duplicate collection start is refused and not executed`() = runBlocking {
        val runtime = FakeRuntime().apply {
            connected = mac
            collection = true
        }
        val executor = SceneExecutor(runtime, canConnect = { true })

        val result = executor.run(scene(SceneAction(SceneActionType.START_COLLECTION)))

        assertTrue(runtime.calls.none { it.startsWith("start:") })
        assertTrue(result.outcomes.single().message.contains("已在后台采集"))
    }

    @Test
    fun `start collection requires a connected device`() = runBlocking {
        val runtime = FakeRuntime()
        val executor = SceneExecutor(runtime, canConnect = { true })

        val result = executor.run(scene(SceneAction(SceneActionType.START_COLLECTION)))

        assertFalse(result.allSucceeded)
        assertTrue(result.outcomes.single().message.contains("请先连接设备"))
    }

    @Test
    fun `mute and sync call through to runtime`() = runBlocking {
        val runtime = FakeRuntime()
        val executor = SceneExecutor(runtime, canConnect = { true })

        val result = executor.run(
            scene(
                SceneAction(SceneActionType.MUTE_ALERTS, muteMinutes = 60),
                SceneAction(SceneActionType.SYNC_NOW),
            ),
        )

        assertEquals(listOf("mute:60", "sync"), runtime.calls)
        assertTrue(result.allSucceeded)
    }

    @Test
    fun `invalid action is reported without executing`() = runBlocking {
        val runtime = FakeRuntime()
        val executor = SceneExecutor(runtime, canConnect = { true })

        val result = executor.run(
            scene(SceneAction(SceneActionType.CONNECT_DEVICE, deviceMac = " ")),
        )

        assertEquals(0, runtime.calls.size)
        assertTrue(result.outcomes.single().message.contains("请选择要操作的设备"))
    }

    @Test
    fun `notify and mark device call real runtime effects`() = runBlocking {
        val runtime = FakeRuntime()
        val executor = SceneExecutor(runtime, canConnect = { true })

        val result = executor.run(
            scene(
                SceneAction(SceneActionType.NOTIFY),
                SceneAction(SceneActionType.RECORD_LOG),
                SceneAction(SceneActionType.MARK_DEVICE, deviceMac = mac),
            ),
        )

        assertEquals(3, result.successCount)
        assertEquals(1, runtime.calls.count { it.startsWith("notify:") })
        assertTrue(runtime.calls.first().startsWith("notify:场景「回家」:"))
        assertTrue(runtime.calls.last() == "mark:$mac")
        assertEquals(SceneActionType.NOTIFY, result.outcomes.first().action.type)
        assertTrue(result.allSucceeded)
    }


    @Test
    fun `summary distinguishes all success partial and all failure`() {
        val all = SceneRunResult(
            1L,
            listOf(
                SceneActionOutcome(SceneAction(SceneActionType.SYNC_NOW), true, "ok"),
                SceneActionOutcome(SceneAction(SceneActionType.SYNC_NOW), true, "ok"),
            ),
        )
        assertEquals("「回家」执行成功（2 个动作）", all.summary("回家"))

        val partial = SceneRunResult(
            2L,
            listOf(
                SceneActionOutcome(SceneAction(SceneActionType.SYNC_NOW), true, "ok"),
                SceneActionOutcome(SceneAction(SceneActionType.SYNC_NOW), false, "fail"),
            ),
        )
        assertEquals("「回家」部分成功：1 成功 / 1 失败", partial.summary("回家"))

        val allFailed = SceneRunResult(
            3L,
            listOf(
                SceneActionOutcome(SceneAction(SceneActionType.SYNC_NOW), false, "fail"),
            ),
        )
        assertEquals("「回家」执行失败（1 个动作均失败）", allFailed.summary("回家"))
    }

    private fun scene(vararg actions: SceneAction) = Scene(9L, "回家", actions.toList())

    private class FakeRuntime : SceneRuntime {
        var connected: String? = null
        var collection = false
        val calls = mutableListOf<String>()

        override suspend fun connectDevice(mac: String): Boolean {
            calls += "connect:$mac"
            connected = mac
            return true
        }

        override suspend fun disconnectDevice(mac: String): Boolean {
            calls += "disconnect:$mac"
            if (connected.equals(mac, ignoreCase = true)) {
                connected = null
                return true
            }
            return false
        }

        override suspend fun connectedMac(): String? = connected

        override fun collectionActive(): Boolean = collection

        override fun activeCollectionMac(): String? = connected.takeIf { collection }

        override suspend fun startCollection(mac: String): Boolean {
            calls += "start:$mac"
            collection = true
            return true
        }

        override fun stopCollection(): Boolean {
            calls += "stop"
            val wasActive = collection
            collection = false
            return wasActive
        }

        override fun muteAlerts(minutes: Int) {
            calls += "mute:$minutes"
        }

        override fun syncNow() {
            calls += "sync"
        }

        override suspend fun notify(title: String, body: String): Boolean {
            calls += "notify:$title:$body"
            return true
        }

        override suspend fun markDevice(mac: String): Boolean {
            calls += "mark:$mac"
            return true
        }
    }
}

private fun SceneActionType.displayLabel(): String = when (this) {
    SceneActionType.CONNECT_DEVICE -> "连接设备"
    SceneActionType.DISCONNECT_DEVICE -> "断开设备"
    SceneActionType.START_COLLECTION -> "开始后台采集"
    SceneActionType.STOP_COLLECTION -> "停止后台采集"
    SceneActionType.MUTE_ALERTS -> "静音告警"
    SceneActionType.SYNC_NOW -> "刷新同步"
    SceneActionType.NOTIFY -> "本地通知"
    SceneActionType.RECORD_LOG -> "记录日志"
    SceneActionType.MARK_DEVICE -> "标记设备"
}
