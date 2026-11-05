package com.platojobs.pkotlinapp.domain.rule

import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模板只由真实出现过的指标生成，且跳过已有同条件规则。 */
class RuleTemplatesTest {

    private fun existing(
        metric: String,
        comparator: RuleComparator,
        threshold: Double,
    ) = DeviceRule(
        id = 1,
        type = RuleType.METRIC_THRESHOLD,
        threshold = 0,
        enabled = true,
        createdAt = 0L,
        lastTriggeredTimestamp = 0L,
        metric = metric,
        comparator = comparator,
        thresholdValue = threshold,
    )

    @Test
    fun `only real metrics produce templates`() {
        val templates = RuleTemplates.generate(
            deviceMac = "AA:BB",
            deviceName = "客厅温度计",
            realMetrics = listOf(ReadingMetrics.TEMPERATURE, ReadingMetrics.BATTERY),
        )

        assertEquals(2, templates.size)
        assertEquals("客厅温度计", templates[0].deviceName)
        assertEquals("AA:BB", templates[0].deviceMac)
        assertEquals(ReadingMetrics.TEMPERATURE, templates[0].metric)
        assertEquals(RuleComparator.GREATER, templates[0].comparator)
        assertEquals(30.0, templates[0].threshold, 0.001)
        assertEquals("℃", templates[0].unit)

        assertEquals(ReadingMetrics.BATTERY, templates[1].metric)
        assertEquals(RuleComparator.LESS, templates[1].comparator)
        assertEquals(20.0, templates[1].threshold, 0.001)
        assertEquals("%", templates[1].unit)
    }

    @Test
    fun `missing metrics never fabricate templates`() {
        val templates = RuleTemplates.generate(
            deviceMac = "AA:BB",
            deviceName = "温度计",
            realMetrics = listOf(ReadingMetrics.TEMPERATURE),
        )
        assertEquals(1, templates.size)
        assertTrue(templates.none { it.metric == ReadingMetrics.HUMIDITY })
    }

    @Test
    fun `unknown metrics are skipped`() {
        val templates = RuleTemplates.generate(
            deviceMac = "AA:BB",
            deviceName = "未知设备",
            realMetrics = listOf(ReadingMetrics.OTHER, "mystery"),
        )
        assertTrue(templates.isEmpty())
    }

    @Test
    fun `empty readings produce no templates`() {
        assertTrue(
            RuleTemplates.generate(
                deviceMac = "AA:BB",
                deviceName = "温度计",
                realMetrics = emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun `existing identical rules are not recommended again`() {
        val templates = RuleTemplates.generate(
            deviceMac = "AA:BB",
            deviceName = "温度计",
            realMetrics = listOf(ReadingMetrics.TEMPERATURE, ReadingMetrics.HEART_RATE),
            existingRules = listOf(
                existing(ReadingMetrics.TEMPERATURE, RuleComparator.GREATER, 30.0),
            ),
        )
        assertEquals(1, templates.size)
        assertEquals(ReadingMetrics.HEART_RATE, templates.single().metric)
        assertEquals(120.0, templates.single().threshold, 0.001)
    }

    @Test
    fun `same metric with different comparator still recommends`() {
        val templates = RuleTemplates.generate(
            deviceMac = "AA:BB",
            deviceName = "温度计",
            realMetrics = listOf(ReadingMetrics.TEMPERATURE),
            existingRules = listOf(
                existing(ReadingMetrics.TEMPERATURE, RuleComparator.LESS, 10.0),
            ),
        )
        assertEquals(1, templates.size)
        assertEquals(RuleComparator.GREATER, templates.single().comparator)
    }
}
