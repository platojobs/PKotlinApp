package com.platojobs.pkotlinapp.ui.products

import com.platojobs.pkotlinapp.domain.model.Compatibility
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.ui.common.RemoteUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 产品页的筛选与徽标计数必须基于真实 catalog，不出现虚构条目。 */
class ProductsUiStateTest {

    private fun product(key: String, category: ProductCategory) = ProductModel(
        id = key,
        vendor = "vendor",
        model = key,
        firmwareVersion = "1.0",
        note = "",
        services = emptyList(),
        deviceTypeKey = key,
        compatibility = Compatibility(platforms = listOf("android")),
        category = category,
    )

    private val catalog = listOf(
        product("heart_rate_monitor", ProductCategory.HEALTH),
        product("environmental_sensor", ProductCategory.ENVIRONMENT),
        product("nordic_uart", ProductCategory.GATEWAY),
        product("smart_scale", ProductCategory.HEALTH),
    )

    @Test
    fun `counts come from catalog entries`() {
        val state = ProductsUiState(catalog = RemoteUiState.Success(catalog))

        assertEquals(2, state.categoryCounts[ProductCategory.HEALTH])
        assertEquals(1, state.categoryCounts[ProductCategory.ENVIRONMENT])
        assertEquals(1, state.categoryCounts[ProductCategory.GATEWAY])
        assertEquals(0, state.categoryCounts[ProductCategory.OTHER] ?: 0)
        assertEquals(4, state.visibleProducts.size)
    }

    @Test
    fun `selected category filters visible products`() {
        val state = ProductsUiState(
            catalog = RemoteUiState.Success(catalog),
            selectedCategory = ProductCategory.HEALTH,
        )

        assertEquals(
            listOf("heart_rate_monitor", "smart_scale"),
            state.visibleProducts.map { it.deviceTypeKey },
        )
    }

    @Test
    fun `loading and error states expose no products`() {
        assertTrue(ProductsUiState().visibleProducts.isEmpty())
        assertTrue(
            ProductsUiState(catalog = RemoteUiState.Error("boom")).visibleProducts.isEmpty(),
        )
        assertTrue(ProductsUiState(catalog = RemoteUiState.Error("boom")).categoryCounts.isEmpty())
    }

    @Test
    fun `devices are grouped by real deviceType`() {
        val state = ProductsUiState(
            catalog = RemoteUiState.Success(catalog),
            devicesByType = mapOf(
                "smart_scale" to listOf(
                    Device(
                        mac = "AA:BB:CC:00:00:01",
                        lastRssi = -50,
                        lastSeenTimestamp = 1762300000000,
                        deviceType = "smart_scale",
                    ),
                ),
            ),
        )

        assertEquals(1, state.devicesByType.getValue("smart_scale").size)
        assertEquals(2, state.categoryCounts.getValue(ProductCategory.HEALTH))
    }
}
