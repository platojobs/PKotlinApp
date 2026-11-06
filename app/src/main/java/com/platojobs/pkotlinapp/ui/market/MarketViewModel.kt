package com.platojobs.pkotlinapp.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.catalog.CatalogRefreshResult
import com.platojobs.pkotlinapp.data.catalog.DeviceCatalogStore
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalog
import com.platojobs.pkotlinapp.domain.market.CompatibilityProfile
import com.platojobs.pkotlinapp.domain.market.MarketScenario
import com.platojobs.pkotlinapp.domain.market.ScenarioRecommendation
import com.platojobs.pkotlinapp.domain.market.compatibilityProfiles
import com.platojobs.pkotlinapp.domain.market.scenarioRecommendation
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.domain.repository.MarketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 市场页内容：兼容性档案、场景建议、可选商品条目。
 *
 * 兼容性与类型清单直接来自服务端目录缓存；商品条目只是示例并默认隐藏，
 * 不提供价格/库存/销量。
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

    data class Content(
        val data: MarketData,
        val catalog: DeviceTypeCatalog,
        val refreshFailure: CatalogRefreshResult.Failed? = null,
    ) : MarketUiState

    data class Error(val failure: CatalogRefreshResult.Failed?) : MarketUiState
}

private data class AccessoriesState(
    val items: List<MarketItem> = emptyList(),
    val unavailable: Boolean = false,
    val loaded: Boolean = false,
)

class MarketViewModel(
    private val catalogStore: DeviceCatalogStore,
    private val marketRepository: MarketRepository,
    private val settings: AppSettings,
) : ViewModel() {

    private val selectedType = MutableStateFlow<String?>(null)
    private val showAccessories = MutableStateFlow(settings.marketShowSampleProducts)
    private val accessories = MutableStateFlow(AccessoriesState())
    private val refreshFailure = MutableStateFlow<CatalogRefreshResult.Failed?>(null)

    val state: StateFlow<MarketUiState> = combine(
        catalogStore.observe(),
        selectedType,
        accessories,
        showAccessories,
        refreshFailure,
    ) { snapshot, requestedType, accessoryState, show, failure ->
        val profiles = compatibilityProfiles(snapshot.items)
        val selected = requestedType
            ?.takeIf { key -> profiles.any { it.deviceTypeKey == key } }
            ?: profiles.firstOrNull()?.deviceTypeKey
        val data = MarketData(
            profiles = profiles,
            recommendations = MarketScenario.entries.map { scenario ->
                scenarioRecommendation(scenario, profiles)
            },
            accessories = accessoryState.items,
            selectedTypeKey = selected,
            showAccessories = show,
            accessoriesUnavailable = accessoryState.unavailable,
        )
        when {
            // 缓存先渲染；只有无缓存且失败才进入错误态。
            snapshot.hasCache -> MarketUiState.Content(data, snapshot, failure)
            failure != null -> MarketUiState.Error(failure)
            else -> MarketUiState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MarketUiState.Loading)

    init {
        refresh(force = false)
        // 配件默认隐藏；只有用户打开开关才按目录类型拉取，失败仅标记该段不可用。
        viewModelScope.launch {
            combine(catalogStore.observe(), showAccessories) { snapshot, show ->
                if (show && snapshot.hasCache) snapshot.items.map { it.type } else null
            }
                .distinctUntilChanged()
                .collect { types ->
                    if (types != null && !accessories.value.loaded) {
                        loadAccessories(types)
                    }
                }
        }
    }

    fun load() {
        refresh(force = true)
        accessories.value = AccessoriesState()
    }

    fun selectType(deviceTypeKey: String) {
        selectedType.value = deviceTypeKey
    }

    fun setShowAccessories(enabled: Boolean) {
        settings.marketShowSampleProducts = enabled
        showAccessories.value = enabled
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

    private fun loadAccessories(deviceTypes: List<String>) {
        viewModelScope.launch {
            accessories.value = accessories.value.copy(loaded = true)
            accessories.value = runCatching { marketRepository.fetchMarketItems(deviceTypes) }
                .fold(
                    onSuccess = { AccessoriesState(items = it, loaded = true) },
                    onFailure = { AccessoriesState(unavailable = true, loaded = true) },
                )
        }
    }

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                MarketViewModel(
                    catalogStore = app.container.deviceCatalogStore,
                    marketRepository = app.container.marketRepository,
                    settings = app.container.settings,
                )
            }
        }
    }
}
