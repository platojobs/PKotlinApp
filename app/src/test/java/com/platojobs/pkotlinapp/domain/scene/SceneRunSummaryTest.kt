package com.platojobs.pkotlinapp.domain.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景卡片上的「最近执行」摘要必须来自真实执行记录：
 * 结果由成功/失败计数推导，按场景取时间最新的一条，记录清空后不补造占位。
 */
class SceneRunSummaryTest {

    private fun run(
        id: Long,
        sceneId: Long,
        timestamp: Long,
        success: Int,
        failure: Int,
        reason: String? = null,
    ) = SceneRun(
        id = id,
        sceneId = sceneId,
        sceneName = "场景$sceneId",
        timestamp = timestamp,
        triggerType = "MANUAL",
        triggerValue = "",
        successCount = success,
        failureCount = failure,
        failureReason = reason,
        detail = "",
    )

    @Test
    fun `outcome is derived from success and failure counts`() {
        assertEquals(SceneRunOutcome.SUCCESS, run(1, 1, 10, 3, 0).outcome())
        assertEquals(SceneRunOutcome.FAILURE, run(2, 1, 10, 0, 2, "连接超时").outcome())
        assertEquals(SceneRunOutcome.PARTIAL, run(3, 1, 10, 1, 1, "设备离线").outcome())
    }

    @Test
    fun `latest run per scene keeps the newest record`() {
        val runs = listOf(
            run(10, 1, 300, 1, 0),
            run(9, 2, 200, 0, 1, "设备离线"),
            run(8, 1, 100, 2, 0),
        )

        val latest = latestRunByScene(runs)

        assertEquals(10L, latest.getValue(1).id)
        assertEquals(9L, latest.getValue(2).id)
        assertEquals("设备离线", latest.getValue(2).failureReason)
    }

    @Test
    fun `no runs yields empty map so the card can show not-run state`() {
        assertTrue(latestRunByScene(emptyList()).isEmpty())
    }

    @Test
    fun `duplicate scene ids with equal timestamps keep the first seen record`() {
        val runs = listOf(
            run(2, 7, 100, 1, 0),
            run(1, 7, 100, 0, 1, "失败"),
        )

        val latest = latestRunByScene(runs)

        assertEquals(1, latest.size)
        assertEquals(2L, latest.getValue(7).id)
    }
}
