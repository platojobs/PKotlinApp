package com.platojobs.pkotlinapp.ui.common

sealed interface RemoteUiState<out T> {
    data object Loading : RemoteUiState<Nothing>

    data class Success<T>(val items: List<T>) : RemoteUiState<T>

    data class Error(val message: String?) : RemoteUiState<Nothing>
}
