package com.platojobs.pkotlinapp.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.repository.ProductRepository
import com.platojobs.pkotlinapp.ui.common.RemoteUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProductsViewModel(
    private val repository: ProductRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RemoteUiState<ProductModel>>(RemoteUiState.Loading)
    val state: StateFlow<RemoteUiState<ProductModel>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = RemoteUiState.Loading
            _state.value = runCatching { repository.fetchProducts() }
                .fold(
                    onSuccess = { RemoteUiState.Success(it) },
                    onFailure = { RemoteUiState.Error(it.message) },
                )
        }
    }

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer { ProductsViewModel(app.container.productRepository) }
        }
    }
}
