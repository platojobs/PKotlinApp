package com.platojobs.pkotlinapp.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `GET /catalog/device-types` 契约（openapi 0.14.0）：
 * 字段名、类型与后端 JSON 对齐；未知未来字段被忽略而不是导致解析失败。
 */
class DeviceCatalogDtoContractTest {

    private val adapter = ApiClient.moshi().adapter(DeviceTypeCatalogDto::class.java)

    @Test
    fun `parses the published catalog response`() {
        val json = """
            {
              "version": 12,
              "generatedAt": "2026-10-01T08:00:00Z",
              "items": [
                {
                  "type": "environmental_sensor",
                  "displayName": "环境传感器",
                  "description": "温湿度采集",
                  "gattServices": [
                    { "uuid": "0000181a-0000-1000-8000-00805f9b34fb", "name": "Environmental Sensing" }
                  ],
                  "metrics": [
                    {
                      "key": "temperature_celsius",
                      "unit": "°C",
                      "characteristicUuid": "00002a6e-0000-1000-8000-00805f9b34fb"
                    }
                  ],
                  "limitations": ["需保持干燥"],
                  "compatibility": {
                    "platforms": ["android"],
                    "minAppVersion": "0.20.0",
                    "protocol": "BLE",
                    "features": ["notify"]
                  },
                  "updatedAt": "2026-09-30T00:00:00Z",
                  "futureField": { "ignored": true }
                }
              ],
              "futureTopLevel": "ignored"
            }
        """.trimIndent()

        val catalog = requireNotNull(adapter.fromJson(json))

        assertEquals(12L, catalog.version)
        assertEquals("2026-10-01T08:00:00Z", catalog.generatedAt)
        val item = requireNotNull(catalog.items).single()
        assertEquals("environmental_sensor", item.type)
        assertEquals("环境传感器", item.displayName)
        assertEquals("温湿度采集", item.description)
        assertEquals("2026-09-30T00:00:00Z", item.updatedAt)
        assertEquals("需保持干燥", item.limitations?.single())
        val service = requireNotNull(item.gattServices).single()
        assertEquals("0000181a-0000-1000-8000-00805f9b34fb", service.uuid)
        assertEquals("Environmental Sensing", service.name)
        val metric = requireNotNull(item.metrics).single()
        assertEquals("temperature_celsius", metric.key)
        assertEquals("°C", metric.unit)
        assertEquals("00002a6e-0000-1000-8000-00805f9b34fb", metric.characteristicUuid)
        val compatibility = requireNotNull(item.compatibility)
        assertEquals(listOf("android"), compatibility.platforms)
        assertEquals("0.20.0", compatibility.minAppVersion)
        assertEquals("BLE", compatibility.protocol)
        assertEquals(listOf("notify"), compatibility.features)
    }

    @Test
    fun `minimal item and empty catalog are valid`() {
        val json = """
            { "version": 1, "generatedAt": "2026-10-01T08:00:00Z", "items": [ { "type": "nordic_uart" } ] }
        """.trimIndent()

        val catalog = requireNotNull(adapter.fromJson(json))
        val item = requireNotNull(catalog.items).single()
        assertEquals("nordic_uart", item.type)
        assertNull(item.displayName)
        assertNull(item.metrics)
        assertNull(item.compatibility)

        val empty = requireNotNull(adapter.fromJson("""{"version":2,"items":[]}"""))
        assertEquals(0, empty.items?.size)
    }
}
