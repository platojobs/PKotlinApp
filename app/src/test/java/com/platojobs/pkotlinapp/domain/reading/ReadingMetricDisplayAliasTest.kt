package com.platojobs.pkotlinapp.domain.reading

import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 服务端指标 key 的展示别名：已知 key 归一，未知 key 返回 null 由展示层保留原文。 */
class ReadingMetricDisplayAliasTest {

    @Test
    fun mapsServerKeysToCanonicalMetrics() {
        assertEquals(ReadingMetrics.TEMPERATURE, ReadingMetrics.displayAlias("temperature_celsius"))
        assertEquals(ReadingMetrics.HUMIDITY, ReadingMetrics.displayAlias("relative_humidity"))
        assertEquals(ReadingMetrics.PRESSURE, ReadingMetrics.displayAlias("pressure_hpa"))
        assertEquals(ReadingMetrics.HEART_RATE, ReadingMetrics.displayAlias("heart_rate"))
        assertEquals(ReadingMetrics.BATTERY, ReadingMetrics.displayAlias("battery_percent"))
        assertEquals(ReadingMetrics.CO2, ReadingMetrics.displayAlias("co2_ppm"))
    }

    @Test
    fun acceptsCommonVariantsAndTrims() {
        assertEquals(ReadingMetrics.TEMPERATURE, ReadingMetrics.displayAlias(" temp "))
        assertEquals(ReadingMetrics.HEART_RATE, ReadingMetrics.displayAlias("heart-rate"))
        assertEquals(ReadingMetrics.HEART_RATE, ReadingMetrics.displayAlias("heartrate"))
        assertEquals(ReadingMetrics.HUMIDITY, ReadingMetrics.displayAlias("relative humidity"))
    }

    @Test
    fun unknownKeyReturnsNullForCallerFallback() {
        assertNull(ReadingMetrics.displayAlias("weight_kg"))
        assertNull(ReadingMetrics.displayAlias("bmi"))
        assertNull(ReadingMetrics.displayAlias(""))
    }

    @Test
    fun thresholdUnitAlsoResolvesServerAliases() {
        assertEquals("℃", MetricThreshold.displayUnit("temperature_celsius"))
        assertEquals("%", MetricThreshold.displayUnit("relative_humidity"))
        assertEquals("hPa", MetricThreshold.displayUnit("pressure_hpa"))
        // 未知指标沿用上报单位，不吞掉原始单位。
        assertEquals("kg", MetricThreshold.displayUnit("weight_kg", "kg"))
    }
}
