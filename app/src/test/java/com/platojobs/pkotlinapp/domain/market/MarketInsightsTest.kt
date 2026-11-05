package com.platojobs.pkotlinapp.domain.market

import com.platojobs.pkotlinapp.domain.model.GattServiceSpec
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.product.SupportedMetric
import com.platojobs.pkotlinapp.domain.reading.ReadingUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兼容性与选购建议必须由 catalog 服务 UUID + ReadingUuids 推导：
 * 指标不为空才推荐；NUS 的已知限制必须显式出现；目录没登记的服务不能编造。
 */
class MarketInsightsTest {

    @Test
    fun `compatibility derives parsable metrics from reading uuids`() {
        val profiles = compatibilityProfiles(
            listOf(
                heartRateModel(),
                environmentModel(),
                nusModel(),
                unparsableModel(),
            ),
        )

        val heart = profiles.first { it.deviceTypeKey == "heart-rate-band" }
        assertEquals(listOf(SupportedMetric.HEART_RATE), heart.metrics)
        assertTrue(heart.limitations.contains(CompatibilityLimitation.FIRMWARE_UNKNOWN))

        val environment = profiles.first { it.deviceTypeKey == "thermo-hygrometer" }
        assertTrue(SupportedMetric.TEMPERATURE in environment.metrics)
        assertFalse(environment.limitations.contains(CompatibilityLimitation.NO_PARSABLE_METRIC))

        val nus = profiles.first { it.deviceTypeKey == "nus-relay" }
        assertTrue(nus.limitations.contains(CompatibilityLimitation.NUS_LINE_BASED))

        val unparsable = profiles.first { it.deviceTypeKey == "vendor-private" }
        assertTrue(unparsable.metrics.isEmpty())
        assertTrue(unparsable.limitations.contains(CompatibilityLimitation.NO_PARSABLE_METRIC))
    }

    @Test
    fun `scenario recommendations only include types that can actually parse`() {
        val profiles = compatibilityProfiles(
            listOf(heartRateModel(), environmentModel(), nusModel(), unparsableModel()),
        )

        val health = scenarioRecommendation(MarketScenario.HEALTH, profiles)
        assertEquals(
            listOf("heart-rate-band"),
            health.recommended.map { it.deviceTypeKey },
        )
        assertEquals(listOf(SupportedMetric.HEART_RATE), health.evidence.metrics)

        val environment = scenarioRecommendation(MarketScenario.ENVIRONMENT, profiles)
        assertEquals(listOf("thermo-hygrometer"), environment.recommended.map { it.deviceTypeKey })

        val network = scenarioRecommendation(MarketScenario.MULTI_DEVICE, profiles)
        assertEquals(listOf("nus-relay"), network.recommended.map { it.deviceTypeKey })
    }

    @Test
    fun `scenario without matching capability returns no recommendation`() {
        val profiles = compatibilityProfiles(listOf(unparsableModel()))

        val health = scenarioRecommendation(MarketScenario.HEALTH, profiles)
        assertTrue(health.recommended.isEmpty())
        assertTrue(health.evidence.metrics.isEmpty())
    }

    private fun model(
        id: String,
        key: String,
        services: List<GattServiceSpec>,
        firmware: String = "1.2.0",
    ) = ProductModel(
        id = id,
        vendor = "Vendor",
        model = id,
        firmwareVersion = firmware,
        note = "",
        services = services,
        deviceTypeKey = key,
    )

    private fun heartRateModel() = model(
        id = "心率手环",
        key = "heart-rate-band",
        services = listOf(
            GattServiceSpec(
                uuid = HEART_RATE_SERVICE,
                name = "Heart Rate",
                characteristics = listOf(ReadingUuids.HEART_RATE),
            ),
        ),
        firmware = "",
    )

    private fun environmentModel() = model(
        id = "温湿度计",
        key = "thermo-hygrometer",
        services = listOf(
            GattServiceSpec(
                uuid = TEMPERATURE_SERVICE,
                name = "Health Thermometer",
                characteristics = listOf(ReadingUuids.TEMPERATURE),
            ),
        ),
    )

    private fun nusModel() = model(
        id = "NUS 中继",
        key = "nus-relay",
        services = listOf(
            GattServiceSpec(
                uuid = NUS_SERVICE,
                name = "Nordic UART",
                characteristics = listOf(ReadingUuids.NUS_TX),
            ),
        ),
    )

    private fun unparsableModel() = model(
        id = "私有协议设备",
        key = "vendor-private",
        services = listOf(
            GattServiceSpec(
                uuid = "0000fff0-0000-1000-8000-00805f9b34fb",
                name = "Vendor Private",
                characteristics = listOf("0000fff1-0000-1000-8000-00805f9b34fb"),
            ),
        ),
        firmware = "",
    )

    private companion object {
        const val HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
        const val TEMPERATURE_SERVICE = "0000181a-0000-1000-8000-00805f9b34fb"
        const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    }
}
