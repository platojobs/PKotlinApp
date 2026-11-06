package com.platojobs.pkotlinapp.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 推送 data 解析与点击路由（当前无真实推送，先把结构固定下来）。 */
class PushMessageHandlerTest {

    @Test
    fun `parses alert id kind severity and device fields`() {
        val message = PushMessageHandler.parse(
            mapOf(
                "alertId" to " alert-1 ",
                "kind" to "device_offline",
                "severity" to "warning",
                "mac" to "C8:47:8C:AA:BB:CC",
                "deviceName" to "客厅温度计",
            ),
        )!!

        assertEquals("alert-1", message.alertId)
        assertEquals("device_offline", message.kind)
        assertEquals("warning", message.severity)
    }

    @Test
    fun `data without alert id and kind is not a push message`() {
        assertNull(PushMessageHandler.parse(mapOf("severity" to "warning")))
        assertNull(PushMessageHandler.parse(emptyMap()))
    }

    @Test
    fun `device alert routes to device detail`() {
        val destination = PushMessageHandler.destination(
            PushMessageHandler.parse(
                mapOf("kind" to "device_offline", "mac" to "C8:47:8C:AA:BB:CC"),
            )!!,
        )

        assertEquals(
            PushDestination.DeviceDetail("C8:47:8C:AA:BB:CC", null),
            destination,
        )
    }

    @Test
    fun `rule alert with device name routes to device detail with name`() {
        val destination = PushMessageHandler.destination(
            PushMessageHandler.parse(
                mapOf(
                    "kind" to "rule_alert",
                    "externalId" to "ext-1",
                    "name" to "书房传感器",
                ),
            )!!,
        )

        assertEquals(PushDestination.DeviceDetail("ext-1", "书房传感器"), destination)
    }

    @Test
    fun `scene alert routes to scenes page`() {
        val destination = PushMessageHandler.destination(
            PushMessageHandler.parse(
                mapOf("kind" to "scene_triggered", "mac" to "C8:47:8C:AA:BB:CC"),
            )!!,
        )

        assertEquals(PushDestination.Scenes, destination)
    }

    @Test
    fun `device kind without mac falls back to scenes`() {
        val destination = PushMessageHandler.destination(
            PushMessageHandler.parse(mapOf("kind" to "device_offline"))!!,
        )

        assertEquals(PushDestination.Scenes, destination)
    }

    @Test
    fun `unknown kind with mac still opens device detail`() {
        val destination = PushMessageHandler.destination(
            PushMessageHandler.parse(
                mapOf("kind" to "custom_kind", "deviceMac" to "C8:47:8C:AA:BB:CC"),
            )!!,
        )

        assertEquals(PushDestination.DeviceDetail("C8:47:8C:AA:BB:CC", null), destination)
    }

    @Test
    fun `unknown kind without mac falls back to scenes`() {
        val destination = PushMessageHandler.destination(
            PushMessageHandler.parse(mapOf("alertId" to "alert-2", "kind" to "custom_kind"))!!,
        )

        assertEquals(PushDestination.Scenes, destination)
    }
}
