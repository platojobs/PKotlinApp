package com.platojobs.pkotlinapp.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.catalog.CatalogRefreshResult
import com.platojobs.pkotlinapp.data.catalog.DeviceCatalogStore
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalog
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalogItem
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ProductDetailState {
    data object Loading : ProductDetailState

    data class Content(
        val product: DeviceTypeCatalogItem,
        val devices: List<Device>,
        val catalog: DeviceTypeCatalog,
        val refreshFailure: CatalogRefreshResult.Failed? = null,
    ) : ProductDetailState

    /** 服务端目录里没有这个 deviceType：展示空态而不是错误。 */
    data object NotFound : ProductDetailState

    data class Failure(val failure: CatalogRefreshResult.Failed?) : ProductDetailState
}

class ProductDetailViewModel(
    private val deviceTypeKey: String,
    private val catalogStore: DeviceCatalogStore,
    deviceRepository: DeviceRepository,
) : ViewModel() {

    private val refreshFailure = MutableStateFlow<CatalogRefreshResult.Failed?>(null)

    val state: StateFlow<ProductDetailState> = combine(
        catalogStore.observe(),
        deviceRepository.observeDevices(),
        refreshFailure,
    ) { snapshot, deviceList, failure ->
        when {
            snapshot.items.any { it.type == deviceTypeKey } -> {
                val item = snapshot.items.first { it.type == deviceTypeKey }
                ProductDetailState.Content(
                    product = item,
                    // 只按真实 deviceType 归属匹配，不做名称猜测。
                    devices = deviceList.filter { it.deviceType == deviceTypeKey },
                    catalog = snapshot,
                    refreshFailure = failure,
                )
            }

            // 有缓存说明服务端目录确实没有这个类型；无缓存且失败才是加载失败。
            snapshot.hasCache -> ProductDetailState.NotFound
            failure != null -> ProductDetailState.Failure(failure)
            else -> ProductDetailState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductDetailState.Loading)

    init {
        if (deviceTypeKey.isNotBlank()) {
            refresh(force = false)
        }
    }

    fun load() {
        if (deviceTypeKey.isBlank()) return
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

    companion object {
        fun factory(app: PKotlinApplication, deviceTypeKey: String) = viewModelFactory {
            initializer {
                ProductDetailViewModel(
                    deviceTypeKey = deviceTypeKey,
                    catalogStore = app.container.deviceCatalogStore,
                    deviceRepository = app.container.deviceRepository,
                )
            }
        }
    }
}
