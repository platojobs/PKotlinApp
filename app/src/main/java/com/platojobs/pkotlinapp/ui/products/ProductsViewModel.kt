package com.platojobs.pkotlinapp.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.domain.product.productCategoryOf
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.ProductRepository
import com.platojobs.pkotlinapp.ui.common.RemoteUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 产品目录页状态：目录数据 + 分类筛选 + 我的设备（按 deviceType 归属）。 */
data class ProductsUiState(
    val catalog: RemoteUiState<ProductModel> = RemoteUiState.Loading,
    val selectedCategory: ProductCategory? = null,
    val devicesByType: Map<String, List<Device>> = emptyMap(),
) {
    /** 当前分类下的条目；null 表示「全部」。 */
    val visibleProducts: List<ProductModel>
        get() = when (val value = catalog) {
            is RemoteUiState.Success -> value.items.filter {
                selectedCategory == null || it.category == selectedCategory
            }

            else -> emptyList()
        }

    /** 每个分类的商品数量，用于 Chip 徽标。 */
    val categoryCounts: Map<ProductCategory, Int>
        get() = when (val value = catalog) {
            is RemoteUiState.Success -> value.items.groupingBy { it.category }.eachCount()
            else -> emptyMap()
        }
}

class ProductsViewModel(
    private val repository: ProductRepository,
    deviceRepository: DeviceRepository,
) : ViewModel() {

    private val catalog = MutableStateFlow<RemoteUiState<ProductModel>>(RemoteUiState.Loading)
    private val selectedCategory = MutableStateFlow<ProductCategory?>(null)

    private val devices = deviceRepository.observeDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<ProductsUiState> = kotlinx.coroutines.flow.combine(
        catalog,
        selectedCategory,
        devices,
    ) { catalogState, category, deviceList ->
        ProductsUiState(
            catalog = catalogState,
            selectedCategory = category,
            devicesByType = deviceList
                .groupBy { it.deviceType.orEmpty() }
                .filterKeys { it.isNotEmpty() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductsUiState())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            catalog.value = RemoteUiState.Loading
            catalog.value = runCatching { repository.fetchProducts() }
                .fold(
                    onSuccess = {
                        // 分类由 deviceType 键推导，未知类型统一落到「其他」。
                        RemoteUiState.Success(it.map { product -> product.toCategorized() })
                    },
                    onFailure = { RemoteUiState.Error(it.message) },
                )
        }
    }

    fun selectCategory(category: ProductCategory?) {
        selectedCategory.value = category
    }

    private fun ProductModel.toCategorized(): ProductModel =
        if (category == productCategoryOf(deviceTypeKey)) {
            this
        } else {
            copy(category = productCategoryOf(deviceTypeKey))
        }

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                ProductsViewModel(
                    repository = app.container.productRepository,
                    deviceRepository = app.container.deviceRepository,
                )
            }
        }
    }
}
