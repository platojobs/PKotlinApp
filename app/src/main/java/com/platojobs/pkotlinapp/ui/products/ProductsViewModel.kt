package com.platojobs.pkotlinapp.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.catalog.CatalogRefreshResult
import com.platojobs.pkotlinapp.data.catalog.DeviceCatalogStore
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalogItem
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.domain.product.productCategoryOf
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.ui.catalog.CatalogUiState
import com.platojobs.pkotlinapp.ui.catalog.catalogUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 产品目录页状态：目录数据 + 分类筛选 + 我的设备（按 deviceType 归属）。 */
data class ProductsUiState(
    val catalog: CatalogUiState = CatalogUiState.Loading,
    val selectedCategory: ProductCategory? = null,
    val devicesByType: Map<String, List<Device>> = emptyMap(),
) {
    private val items: List<DeviceTypeCatalogItem>
        get() = (catalog as? CatalogUiState.Content)?.snapshot?.items.orEmpty()

    /** 当前分类下的条目；null 表示「全部」。 */
    val visibleProducts: List<DeviceTypeCatalogItem>
        get() = items.filter {
            selectedCategory == null || productCategoryOf(it.type) == selectedCategory
        }

    /** 每个分类的商品数量，用于 Chip 徽标。 */
    val categoryCounts: Map<ProductCategory, Int>
        get() = items.groupingBy { productCategoryOf(it.type) }.eachCount()

    val totalCount: Int get() = items.size
}

class ProductsViewModel(
    private val catalogStore: DeviceCatalogStore,
    deviceRepository: DeviceRepository,
) : ViewModel() {

    private val selectedCategory = MutableStateFlow<ProductCategory?>(null)
    private val refreshFailure = MutableStateFlow<CatalogRefreshResult.Failed?>(null)

    private val devices = deviceRepository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<ProductsUiState> = combine(
        catalogStore.observe(),
        selectedCategory,
        devices,
        refreshFailure,
    ) { snapshot, category, deviceList, failure ->
        ProductsUiState(
            // 缓存先渲染；只有确实没有缓存且请求失败时才显示错误态。
            catalog = catalogUiState(snapshot, failure),
            selectedCategory = category,
            devicesByType = deviceList
                .groupBy { it.deviceType.orEmpty() }
                .filterKeys { it.isNotEmpty() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductsUiState())

    init {
        refresh(force = false)
    }

    fun load() {
        refresh(force = true)
    }

    private fun refresh(force: Boolean) {
        viewModelScope.launch {
            refreshFailure.value = null
            val result = catalogStore.refresh(force)
            if (result is CatalogRefreshResult.Failed) {
                refreshFailure.value = result
            }
        }
    }

    fun selectCategory(category: ProductCategory?) {
        selectedCategory.value = category
    }

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                ProductsViewModel(
                    catalogStore = app.container.deviceCatalogStore,
                    deviceRepository = app.container.deviceRepository,
                )
            }
        }
    }
}
