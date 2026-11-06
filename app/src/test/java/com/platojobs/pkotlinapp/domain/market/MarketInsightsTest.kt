package com.platojobs.pkotlinapp.domain.market

import com.platojobs.pkotlinapp.domain.catalog.CatalogMetric
import com.platojobs.pkotlinapp.domain.catalog.CatalogService
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兼容性与选购建议必须由服务端目录 + 本机解析器能力推导：
 * 指标、服务、限制全部来自目录；本机不支持的指标不得进入推荐依据。
 */
class MarketInsightsTest {

    @Test
    fun `compatibility marks declared metrics the parser cannot read`() {
        val profiles = compatibilityProfiles(
            listOf(
                heartRateModel(),
                environmentModel(),
                nusModel(),
                weightModel(),
            ),
        )

        val heart = profiles.first { it.deviceTypeKey == "heart-rate-band" }
        assertEquals(listOf("heart_rate"), heart.metrics.map { it.key })
        assertEquals(listOf("heart_rate"), heart.supportedMetrics.map { it.key })
        assertTrue(heart.unsupportedMetrics.isEmpty())
        // 限制文案直接来自服务端目录。
        assertEquals(listOf("需贴合皮肤测量"), heart.limitations)

        val environment = profiles.first { it.deviceTypeKey == "thermo-hygrometer" }
        assertEquals(listOf("temperature_celsius"), environment.supportedMetrics.map { it.key })
        assertEquals(listOf("relative_humidity"), environment.unsupportedMetrics.map { it.key })

        val weight = profiles.first { it.deviceTypeKey == "smart-scale" }
        assertTrue(weight.supportedMetrics.isEmpty())
        assertEquals(listOf("weight_kg"), weight.unsupportedMetrics.map { it.key })
        assertEquals(listOf("本应用当前版本不支持体重解析"), weight.limitations)
    }

    @Test
    fun `scenario recommendations only include types whose metrics can be parsed`() {
        val profiles = compatibilityProfiles(
            listOf(heartRateModel(), environmentModel(), nusModel(), weightModel()),
        )

        val health = scenarioRecommendation(MarketScenario.HEALTH, profiles)
        assertEquals(listOf("heart-rate-band"), health.recommended.map { it.deviceTypeKey })
        assertEquals(listOf("heart_rate"), health.evidence.metrics.map { it.key })

        val environment = scenarioRecommendation(MarketScenario.ENVIRONMENT, profiles)
        assertEquals(listOf("thermo-hygrometer"), environment.recommended.map { it.deviceTypeKey })
        assertEquals(listOf("temperature_celsius"), environment.evidence.metrics.map { it.key })

        val network = scenarioRecommendation(MarketScenario.MULTI_DEVICE, profiles)
        assertEquals(listOf("nus-relay"), network.recommended.map { it.deviceTypeKey })
    }

    @Test
    fun `scenario without matching capability returns no recommendation`() {
        val profiles = compatibilityProfiles(listOf(weightModel()))

        val health = scenarioRecommendation(MarketScenario.HEALTH, profiles)
        assertTrue(health.recommended.isEmpty())
        assertTrue(health.evidence.metrics.isEmpty())
        assertEquals(0, health.evidence.serviceCount)
    }

    private fun model(
        key: String,
        services: List<CatalogService>,
        metrics: List<CatalogMetric>,
        limitations: List<String> = emptyList(),
    ) = DeviceTypeCatalogItem(
        type = key,
        displayName = key,
        description = "",
        services = services,
        metrics = metrics,
        limitations = limitations,
        compatibility = null,
        updatedAt = "",
    )

    private fun heartRateModel() = model(
        key = "heart-rate-band",
        services = listOf(CatalogService(HEART_RATE_SERVICE, "Heart Rate")),
        metrics = listOf(CatalogMetric("heart_rate", "bpm", HEART_RATE_CHARACTERISTIC)),
        limitations = listOf("需贴合皮肤测量"),
    )

    private fun environmentModel() = model(
        key = "thermo-hygrometer",
        services = listOf(CatalogService(TEMPERATURE_SERVICE, "Health Thermometer")),
        metrics = listOf(
            CatalogMetric("temperature_celsius", "°C", TEMPERATURE_CHARACTERISTIC),
            CatalogMetric("relative_humidity", "%", HUMIDITY_CHARACTERISTIC),
        ),
    )

    private fun nusModel() = model(
        key = "nus-relay",
        services = listOf(CatalogService(NUS_SERVICE, "Nordic UART")),
        metrics = emptyList(),
    )

    private fun weightModel() = model(
        key = "smart-scale",
        services = listOf(CatalogService(WEIGHT_SERVICE, "Weight Scale")),
        metrics = listOf(CatalogMetric("weight_kg", "kg", WEIGHT_CHARACTERISTIC)),
        limitations = listOf("本应用当前版本不支持体重解析"),
    )

    private companion object {
        const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
        const val HEART_RATE_CHARACTERISTIC = "00002a37-0000-1000-8000-00805f9b34fb"
        const val TEMPERATURE_SERVICE = "0000181a-0000-1000-8000-00805f9b34fb"
        const val TEMPERATURE_CHARACTERISTIC = "00002a6e-0000-1000-8000-00805f9b34fb"
        const val HUMIDITY_CHARACTERISTIC = "00002a6f-0000-1000-8000-00805f9b34fb"
        const val WEIGHT_SERVICE = "0000181d-0000-1000-8000-00805f9b34fb"
        const val WEIGHT_CHARACTERISTIC = "00002a9d-0000-1000-8000-00805f9b34fb"
        const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    }
}
