package com.platojobs.pkotlinapp.domain.scene

import org.junit.Assert.assertEquals
import org.junit.Test

/** notify 模板占位符：约定 {device}/{metric}/{value}，未知占位符原样保留。 */
class ScenePlaceholdersTest {

    private val context = SceneTriggerContext(
        deviceMac = "AA:BB:CC:DD:EE:01",
        deviceName = "客厅温湿度计",
        metric = "temperature",
        value = 31.25,
        unit = "℃",
    )

    @Test
    fun `renders device metric and value`() {
        assertEquals(
            "客厅温湿度计 temperature=31.3",
            ScenePlaceholders.render("{device} {metric}=31.3", context),
        )
    }

    @Test
    fun `integer value has no decimal part`() {
        assertEquals(
            "客厅温湿度计 28",
            ScenePlaceholders.render("{device} {value}", context.copy(value = 28.0)),
        )
    }

    @Test
    fun `empty context falls back to mac and empty tokens`() {
        val empty = context.copy(deviceName = "", metric = null, value = null)
        assertEquals("AA:BB:CC:DD:EE:01", ScenePlaceholders.render("{device}", empty))
        assertEquals("", ScenePlaceholders.render("{metric}", empty))
        assertEquals("", ScenePlaceholders.render("{value}", empty))
        assertEquals("区间 ", ScenePlaceholders.render("区间 {metric}", empty))
    }

    @Test
    fun `unknown placeholder is preserved`() {
        assertEquals("Hi {name}", ScenePlaceholders.render("Hi {name}", context))
        assertEquals("", ScenePlaceholders.render(null, context))
    }
}
