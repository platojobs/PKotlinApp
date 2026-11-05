package com.platojobs.pkotlinapp.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ProductDetailState {
    data object Loading : ProductDetailState

    data class Content(val product: ProductModel, val devices: List<Device>) : ProductDetailState

    /** catalog 里没有这个 deviceType：展示空态而不是错误。 */
    data object NotFound : ProductDetailState

    data class Failure(val message: String?) : ProductDetailState
}

class ProductDetailViewModel(
    private val deviceTypeKey: String,
    private val productRepository: ProductRepository,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ProductDetailState>(ProductDetailState.Loading)
    val state: StateFlow<ProductDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (deviceTypeKey.isBlank()) {
            _state.value = ProductDetailState.NotFound
            return
        }
        viewModelScope.launch {
            _state.value = ProductDetailState.Loading
            _state.value = runCatching {
                val product = productRepository.fetchProducts()
                    .firstOrNull { it.deviceTypeKey == deviceTypeKey }
                if (product == null) {
                    ProductDetailState.NotFound
                } else {
                    // 只按真实 deviceType 归属匹配，不做名称猜测。
                    val devices = deviceRepository.getDevices()
                        .filter { it.deviceType == deviceTypeKey }
                    ProductDetailState.Content(product, devices)
                }
            }.getOrElse { ProductDetailState.Failure(it.message) }
        }
    }

    companion object {
        fun factory(app: PKotlinApplication, deviceTypeKey: String) = viewModelFactory {
            initializer {
                ProductDetailViewModel(
                    deviceTypeKey = deviceTypeKey,
                    productRepository = app.container.productRepository,
                    deviceRepository = app.container.deviceRepository,
                )
            }
        }
    }
}
