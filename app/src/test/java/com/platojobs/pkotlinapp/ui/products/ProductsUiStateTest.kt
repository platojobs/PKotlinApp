package com.platojobs.pkotlinapp.ui.products

import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalog
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalogItem
import com.platojobs.pkotlinapp.domain.model.Compatibility
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.ui.catalog.CatalogUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 产品页的筛选与徽标计数必须基于服务端目录缓存，不出现虚构条目。 */
class ProductsUiStateTest {

    private fun item(key: String) = DeviceTypeCatalogItem(
        type = key,
        displayName = key,
        description = "",
        services = emptyList(),
        metrics = emptyList(),
        limitations = emptyList(),
        compatibility = Compatibility(platforms = listOf("android")),
        updatedAt = "",
    )

    private val catalog = DeviceTypeCatalog(
        items = listOf(
            item("heart_rate_monitor"),
            item("environmental_sensor"),
            item("nordic_uart"),
            item("smart_scale"),
        ),
        version = 12,
        generatedAt = "2026-10-01T08:00:00Z",
        fetchedAt = 1_760_000_000_000,
        lastUsedAt = 1_760_000_000_000,
    )

    @Test
    fun `counts come from cached catalog entries`() {
        val state = ProductsUiState(catalog = CatalogUiState.Content(catalog))

        assertEquals(2, state.categoryCounts[ProductCategory.HEALTH])
        assertEquals(1, state.categoryCounts[ProductCategory.ENVIRONMENT])
        assertEquals(1, state.categoryCounts[ProductCategory.GATEWAY])
        assertEquals(0, state.categoryCounts[ProductCategory.OTHER] ?: 0)
        assertEquals(4, state.visibleProducts.size)
        assertEquals(4, state.totalCount)
    }

    @Test
    fun `selected category filters visible products`() {
        val state = ProductsUiState(
            catalog = CatalogUiState.Content(catalog),
            selectedCategory = ProductCategory.HEALTH,
        )

        assertEquals(
            listOf("heart_rate_monitor", "smart_scale"),
            state.visibleProducts.map { it.type },
        )
    }

    @Test
    fun `loading and error states expose no products`() {
        assertTrue(ProductsUiState().visibleProducts.isEmpty())
        assertTrue(
            ProductsUiState(catalog = CatalogUiState.Error(null)).visibleProducts.isEmpty(),
        )
        assertTrue(ProductsUiState(catalog = CatalogUiState.Error(null)).categoryCounts.isEmpty())
    }

    @Test
    fun `devices are grouped by real deviceType`() {
        val state = ProductsUiState(
            catalog = CatalogUiState.Content(catalog),
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
