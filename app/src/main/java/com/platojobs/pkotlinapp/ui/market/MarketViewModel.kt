package com.platojobs.pkotlinapp.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.domain.repository.MarketRepository
import com.platojobs.pkotlinapp.ui.common.RemoteUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 市场筛选状态：维度值全部来自接口真实数据，并回显当前选中项。 */
data class MarketFilterState(
    val vendors: List<String> = emptyList(),
    val compatibilities: List<String> = emptyList(),
    val selectedVendor: String? = null,
    val selectedCompatibility: String? = null,
) {
    val hasActiveFilter: Boolean
        get() = selectedVendor != null || selectedCompatibility != null
}

class MarketViewModel(
    private val repository: MarketRepository,
) : ViewModel() {

    private var allItems: List<MarketItem> = emptyList()

    private val _state = MutableStateFlow<RemoteUiState<MarketItem>>(RemoteUiState.Loading)
    val state: StateFlow<RemoteUiState<MarketItem>> = _state.asStateFlow()

    private val _filters = MutableStateFlow(MarketFilterState())
    val filters: StateFlow<MarketFilterState> = _filters.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = RemoteUiState.Loading
            runCatching { repository.fetchMarketItems() }
                .fold(
                    onSuccess = { items ->
                        allItems = items
                        _filters.value = MarketFilterState(
                            vendors = items.mapNotNull { item ->
                                item.vendor.takeIf { vendor -> vendor.isNotBlank() }
                            }
                                .distinct()
                                .sorted(),
                            compatibilities = items.flatMap { it.compatibilityTags }
                                .distinct()
                                .sorted(),
                        )
                        applyFilters()
                    },
                    onFailure = { error ->
                        allItems = emptyList()
                        _filters.value = MarketFilterState()
                        _state.value = RemoteUiState.Error(error.message)
                    },
                )
        }
    }

    fun selectVendor(vendor: String?) {
        _filters.value = _filters.value.copy(selectedVendor = vendor)
        applyFilters()
    }

    fun selectCompatibility(tag: String?) {
        _filters.value = _filters.value.copy(selectedCompatibility = tag)
        applyFilters()
    }

    fun clearFilters() {
        _filters.value = _filters.value.copy(
            selectedVendor = null,
            selectedCompatibility = null,
        )
        applyFilters()
    }

    private fun applyFilters() {
        val filter = _filters.value
        val items = allItems
            .filter { filter.selectedVendor == null || it.vendor == filter.selectedVendor }
            .filter {
                filter.selectedCompatibility == null ||
                    filter.selectedCompatibility in it.compatibilityTags
            }
        _state.value = RemoteUiState.Success(items)
    }

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer { MarketViewModel(app.container.marketRepository) }
        }
    }
}
