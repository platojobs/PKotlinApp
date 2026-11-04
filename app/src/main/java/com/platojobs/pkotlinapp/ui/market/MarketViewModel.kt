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

class MarketViewModel(
    private val repository: MarketRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RemoteUiState<MarketItem>>(RemoteUiState.Loading)
    val state: StateFlow<RemoteUiState<MarketItem>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = RemoteUiState.Loading
            _state.value = runCatching { repository.fetchMarketItems() }
                .fold(
                    onSuccess = { RemoteUiState.Success(it) },
                    onFailure = { RemoteUiState.Error(it.message) },
                )
        }
    }

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer { MarketViewModel(app.container.marketRepository) }
        }
    }
}
