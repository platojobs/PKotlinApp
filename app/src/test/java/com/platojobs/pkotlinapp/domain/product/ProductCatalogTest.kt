package com.platojobs.pkotlinapp.domain.product

import org.junit.Assert.assertEquals
import org.junit.Test

/** 分类是本地导航分组，只有已知 deviceType 键才映射，未知键如实落到 OTHER。 */
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
