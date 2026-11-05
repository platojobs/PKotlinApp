package com.platojobs.pkotlinapp.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.domain.market.CompatibilityProfile
import com.platojobs.pkotlinapp.domain.market.MarketScenario
import com.platojobs.pkotlinapp.domain.market.ScenarioRecommendation
import com.platojobs.pkotlinapp.domain.market.compatibilityProfiles
import com.platojobs.pkotlinapp.domain.market.scenarioRecommendation
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.domain.repository.MarketRepository
import com.platojobs.pkotlinapp.domain.repository.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 市场页内容：兼容性档案、场景建议、可选商品条目。
 *
 * 兼容性数据来自 [ProductRepository.fetchProducts]（catalog 的 GATT 服务 + 解析器能力），
 * 商品条目只是 catalog 示例并默认隐藏，不提供价格/库存/销量。
 */
data class MarketData(
    val profiles: List<CompatibilityProfile>,
    val recommendations: List<ScenarioRecommendation>,
    val accessories: List<MarketItem>,
    val selectedTypeKey: String?,
    val showAccessories: Boolean,
    val accessoriesUnavailable: Boolean,
) {
    val selectedProfile: CompatibilityProfile?
        get() = profiles.firstOrNull { it.deviceTypeKey == selectedTypeKey } ?: profiles.firstOrNull()
}

sealed interface MarketUiState {
    data object Loading : MarketUiState

    data class Success(val data: MarketData) : MarketUiState

    data class Error(val message: String?) : MarketUiState
}

class MarketViewModel(
    private val productRepository: ProductRepository,
    private val marketRepository: MarketRepository,
    private val settings: AppSettings,
) : ViewModel() {

    private val _state = MutableStateFlow<MarketUiState>(MarketUiState.Loading)
    val state: StateFlow<MarketUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = MarketUiState.Loading
            val catalog = runCatching { productRepository.fetchProducts() }
            if (catalog.isFailure) {
                _state.value = MarketUiState.Error(catalog.exceptionOrNull()?.message)
                return@launch
            }
            val profiles = compatibilityProfiles(catalog.getOrThrow())
            // 商品条目失败不影响兼容性/服务入口：只标记该段不可用。
            val accessoriesResult = runCatching { marketRepository.fetchMarketItems() }
            val recommendations = MarketScenario.entries.map { scenario ->
                scenarioRecommendation(scenario, profiles)
            }
            _state.value = MarketUiState.Success(
                MarketData(
                    profiles = profiles,
                    recommendations = recommendations,
                    accessories = accessoriesResult.getOrDefault(emptyList()),
                    selectedTypeKey = profiles.firstOrNull()?.deviceTypeKey,
                    showAccessories = settings.marketShowSampleProducts,
                    accessoriesUnavailable = accessoriesResult.isFailure,
                ),
            )
        }
    }

    fun selectType(deviceTypeKey: String) {
        updateData { it.copy(selectedTypeKey = deviceTypeKey) }
    }

    fun setShowAccessories(enabled: Boolean) {
        settings.marketShowSampleProducts = enabled
        updateData { it.copy(showAccessories = enabled) }
    }

    private fun updateData(transform: (MarketData) -> MarketData) {
        val current = _state.value
        if (current is MarketUiState.Success) {
            _state.value = MarketUiState.Success(transform(current.data))
        }
    }

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                MarketViewModel(
                    productRepository = app.container.productRepository,
                    marketRepository = app.container.marketRepository,
                    settings = app.container.settings,
                )
            }
        }
    }
}
