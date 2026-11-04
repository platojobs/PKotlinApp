package com.platojobs.pkotlinapp.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.prefs.ThemeMode
import com.platojobs.pkotlinapp.data.sync.SyncManager
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.sync.SyncStatus
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PermissionStatus(
    val scanGranted: Boolean,
    val connectGranted: Boolean,
    val locationGranted: Boolean,
)

class ProfileViewModel(
    private val appContext: Context,
    private val scanLogRepository: ScanLogRepository,
    private val settings: AppSettings,
    private val syncManager: SyncManager,
) : ViewModel() {

    private val _permissions = MutableStateFlow(readPermissions())
    val permissions: StateFlow<PermissionStatus> = _permissions.asStateFlow()

    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    val scanLogs: StateFlow<List<ScanLog>> = scanLogRepository.observeRecent(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncStatus: StateFlow<SyncStatus> = syncManager.status

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val syncMessage: SharedFlow<String> = _syncMessage.asSharedFlow()

    fun refreshPermissions() {
        _permissions.value = readPermissions()
    }

    fun manualSync() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val outcome = syncManager.manualSync()
                _syncMessage.emit(
                    appContext.getString(
                        com.platojobs.pkotlinapp.R.string.profile_sync_success,
                        outcome.pushed,
                        outcome.pulled,
                    ),
                )
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _syncMessage.emit(
                    appContext.getString(
                        com.platojobs.pkotlinapp.R.string.profile_sync_failure,
                        message,
                    ),
                )
            } finally {
                _syncing.value = false
            }
        }
    }

    fun setTheme(mode: ThemeMode) {
        if (settings.themeMode == mode) return
        settings.themeMode = mode
        settings.applyTheme()
        _themeMode.value = mode
    }

    fun clearLogs() {
        viewModelScope.launch { scanLogRepository.clear() }
    }

    private fun readPermissions() = PermissionStatus(
        scanGranted = BlePermissions.hasScan(appContext),
        connectGranted = BlePermissions.hasConnect(appContext),
        locationGranted = BlePermissions.hasLocation(appContext),
    )

    companion object {
        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                ProfileViewModel(
                    appContext = app.applicationContext,
                    scanLogRepository = app.container.scanLogRepository,
                    settings = app.container.settings,
                    syncManager = app.container.syncManager,
                )
            }
        }
    }
}
