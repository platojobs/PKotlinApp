package com.platojobs.pkotlinapp.domain.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分类与指标完全来自真实 deviceType / GATT 服务 UUID，不写死假文案。 */
class ProductCatalogTest {

    @Test
    fun `real device type keys map to navigable categories`() {
        assertEquals(ProductCategory.HEALTH, productCategoryOf("heart_rate_monitor"))
        assertEquals(ProductCategory.HEALTH, productCategoryOf("smart_scale"))
        assertEquals(ProductCategory.HEALTH, productCategoryOf("cycling_speed_cadence"))
        assertEquals(ProductCategory.ENVIRONMENT, productCategoryOf("environmental_sensor"))
        assertEquals(ProductCategory.GATEWAY, productCategoryOf("nordic_uart"))
        assertEquals(ProductCategory.OTHER, productCategoryOf(null))
        assertEquals(ProductCategory.OTHER, productCategoryOf("unknown_type"))
        // 大小写与首尾空格不影响归类。
        assertEquals(ProductCategory.GATEWAY, productCategoryOf("  NORDIC_UART "))
    }

    @Test
    fun `metrics are derived from service uuids supported by the parser`() {
        assertEquals(
            listOf(SupportedMetric.HEART_RATE),
            supportedMetricsOf(listOf("0000180D-0000-1000-8000-00805F9B34FB")),
        )
        assertEquals(
            listOf(SupportedMetric.BATTERY, SupportedMetric.TEMPERATURE),
            supportedMetricsOf(
                listOf(
                    "0000180f-0000-1000-8000-00805f9b34fb",
                    "0000181a-0000-1000-8000-00805f9b34fb",
                ),
            ),
        )
        assertEquals(
            listOf(SupportedMetric.NUS),
            supportedMetricsOf(listOf("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")),
        )
    }

    @Test
    fun `unknown or unsupported services yield no metrics`() {
        assertTrue(supportedMetricsOf(emptyList()).isEmpty())
        assertTrue(
            supportedMetricsOf(listOf("0000180a-0000-1000-8000-00805f9b34fb")).isEmpty(),
        )
    }

    @Test
    fun `duplicate services collapse to one metric`() {
        val metric = supportedMetricsOf(
            listOf(
                "0000180d-0000-1000-8000-00805f9b34fb",
                "0000180D-0000-1000-8000-00805F9B34FB",
            ),
        )

        assertEquals(listOf(SupportedMetric.HEART_RATE), metric)
    }

    @Test
    fun `navigable order is stable`() {
        assertEquals(
            listOf(
                ProductCategory.HEALTH,
                ProductCategory.ENVIRONMENT,
                ProductCategory.GATEWAY,
                ProductCategory.OTHER,
            ),
            ProductCategory.navigable,
        )
    }
}
