package com.platojobs.pkotlinapp.data.scene

import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务端单行模型 <-> 本地执行模型的映射与可表达性判定。 */
class SceneCanonicalTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    @Test
    fun `device offline maps to canonical json and rule projection`() {
        val trigger = SceneTrigger.DeviceOffline(mac, offlineSeconds = 600)
        val actions = listOf(
            SceneAction(SceneActionType.NOTIFY, title = "{device} 离线", body = "{device} 已离线"),
        )
        val encoded = SceneCanonical.encode(trigger, actions)!!
        val dto = SceneCanonical.triggerDto(encoded.triggerJson)!!

        assertEquals("device_offline", dto.kind)
        assertEquals(mac, dto.device?.externalId)
        assertEquals(600, dto.offlineSeconds)
        assertEquals(mac, SceneCanonical.triggerFrom(dto)?.let {
            (it as SceneTrigger.DeviceOffline).deviceMac
        })

        val rule = SceneCanonical.ruleEntity(trigger, enabled = true)!!
        assertEquals(RuleType.DEVICE_OFFLINE.name, rule.type)
        assertEquals(mac, rule.deviceMac)
        assertEquals(600, rule.offlineSeconds)
        assertTrue(rule.enabled)
    }

    @Test
    fun `metric threshold keeps comparator device type and consecutive`() {
        val trigger = SceneTrigger.MetricThreshold(
            metric = "temperature",
            comparator = RuleComparator.LESS_OR_EQUAL,
            thresholdValue = 5.0,
            consecutive = 12,
            deviceMac = mac,
            deviceType = "thermometer",
        )
        val encoded = SceneCanonical.encode(
            trigger,
            listOf(SceneAction(SceneActionType.RECORD_LOG, message = "cold", level = "warn")),
        )!!
        val dto = SceneCanonical.triggerDto(encoded.triggerJson)!!

        assertEquals("metric_threshold", dto.kind)
        assertEquals("thermometer", dto.deviceType)
        assertEquals("lte", dto.metric?.comparator)
        assertEquals(12, dto.metric?.consecutive)

        val parsed = SceneCanonical.triggerFrom(dto) as SceneTrigger.MetricThreshold
        assertEquals(trigger.metric, parsed.metric)
        assertEquals(RuleComparator.LESS_OR_EQUAL, parsed.comparator)
        assertEquals("thermometer", parsed.deviceType)

        val rule = SceneCanonical.ruleEntity(parsed, true)!!
        assertEquals(12, rule.consecutive)
        assertEquals(RuleComparator.LESS_OR_EQUAL.name, rule.comparator)
    }

    @Test
    fun `schedule keeps timezone and days and project rule gates by local clock`() {
        val trigger = SceneTrigger.Schedule(
            timeOfDay = "08:00",
            timezone = "Asia/Shanghai",
            daysOfWeek = setOf(1, 3, 5),
        )
        val encoded = SceneCanonical.encode(
            trigger,
            listOf(SceneAction(SceneActionType.NOTIFY, title = "t", body = "b")),
        )!!
        assertEquals(encoded.triggerJson, SceneCanonical.encode(trigger, listOf(
            SceneAction(SceneActionType.NOTIFY, title = "t", body = "b"),
        ))!!.triggerJson)

        val rule = SceneCanonical.ruleEntity(trigger, true)!!
        assertEquals("08:00", rule.timeOfDay)
        assertEquals("Asia/Shanghai", rule.timezone)
        assertEquals("1,3,5", rule.daysOfWeek)
        assertNull(rule.intervalMinutes)
    }

    @Test
    fun `mark device action binds the trigger device`() {
        val trigger = SceneTrigger.LowRssi(-80, mac)
        val actionDtos = SceneCanonical.actionDtos(
            SceneCanonical.actionsJson(
                SceneCanonical.encode(
                    trigger,
                    listOf(SceneAction(SceneActionType.MARK_DEVICE, mark = "weak")),
                )!!.actionsJson.let { json ->
                    requireNotNull(SceneCanonical.actionDtos(json))
                },
            )!!,
        )!!
        val actions = SceneCanonical.actionsFrom(actionDtos, trigger)
        assertEquals(SceneActionType.MARK_DEVICE, actions.single().type)
        assertEquals(mac, actions.single().deviceMac)
        assertEquals("weak", actions.single().mark)
    }

    @Test
    fun `local only actions and global triggers are not representable`() {
        assertNull(
            SceneCanonical.encode(
                SceneTrigger.LowRssi(-80, mac),
                listOf(SceneAction(SceneActionType.SYNC_NOW)),
            ),
        )
        assertNull(
            SceneCanonical.encode(
                SceneTrigger.Disconnected,
                listOf(SceneAction(SceneActionType.NOTIFY, title = "t", body = "b")),
            ),
        )
        assertNull(
            SceneCanonical.encode(
                SceneTrigger.LowRssi(-80, null),
                listOf(SceneAction(SceneActionType.NOTIFY, title = "t", body = "b")),
            ),
        )
    }

    @Test
    fun `duplicate server action kinds are rejected`() {
        assertNull(
            SceneCanonical.encode(
                SceneTrigger.DeviceOffline(mac),
                listOf(
                    SceneAction(SceneActionType.NOTIFY, title = "a", body = "b"),
                    SceneAction(SceneActionType.NOTIFY, title = "c", body = "d"),
                ),
            ),
        )
        assertFalse(
            SceneCanonical.serverActionKinds(
                listOf(SceneAction(SceneActionType.NOTIFY), SceneAction(SceneActionType.SYNC_NOW)),
            ) != null,
        )
        assertNotNull(SceneCanonical.triggerFrom(SceneCanonical.triggerDto(
            SceneCanonical.encode(
                SceneTrigger.DeviceOffline(mac),
                listOf(SceneAction(SceneActionType.NOTIFY, title = "a", body = "b")),
            )!!.triggerJson,
        )))
    }
}
