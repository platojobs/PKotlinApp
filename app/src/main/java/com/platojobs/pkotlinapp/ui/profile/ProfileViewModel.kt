package com.platojobs.pkotlinapp.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.prefs.ThemeMode
import com.platojobs.pkotlinapp.data.sync.SyncErrorKind
import com.platojobs.pkotlinapp.data.sync.SyncManager
import com.platojobs.pkotlinapp.data.sync.classifySyncError
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.sync.SyncStatus
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.NotificationPermission
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
    val notificationGranted: Boolean,
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

    private val _alertsEnabled = MutableStateFlow(settings.alertsEnabled)
    val alertsEnabled: StateFlow<Boolean> = _alertsEnabled.asStateFlow()

    private val _alertThrottleMinutes = MutableStateFlow(settings.alertThrottleMinutes)
    val alertThrottleMinutes: StateFlow<Int> = _alertThrottleMinutes.asStateFlow()

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
                        R.string.profile_sync_success,
                        outcome.pushed,
                        outcome.pulled,
                    ),
                )
            } catch (e: Exception) {
                _syncMessage.emit(failureText(e))
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 用户确认重新登录：换新匿名账号并同步本机数据。
     * 旧账号的数据不会被删除或覆盖。
     */
    fun relogin() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val outcome = syncManager.relogin()
                _syncMessage.emit(
                    appContext.getString(
                        R.string.profile_sync_success,
                        outcome.pushed,
                        outcome.pulled,
                    ),
                )
            } catch (e: Exception) {
                _syncMessage.emit(failureText(e))
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun failureText(error: Throwable): String {
        val reason = appContext.getString(
            when (classifySyncError(error)) {
                SyncErrorKind.NETWORK -> R.string.profile_sync_error_network
                SyncErrorKind.TIMEOUT -> R.string.profile_sync_error_timeout
                SyncErrorKind.AUTH -> R.string.profile_sync_error_auth
                SyncErrorKind.CONFLICT -> R.string.profile_sync_error_conflict
                SyncErrorKind.SERVER -> R.string.profile_sync_error_server
                SyncErrorKind.UNKNOWN -> R.string.profile_sync_error_unknown
            },
        )
        return appContext.getString(R.string.profile_sync_failure, reason)
    }

    fun setTheme(mode: ThemeMode) {
        if (settings.themeMode == mode) return
        settings.themeMode = mode
        settings.applyTheme()
        _themeMode.value = mode
    }

    fun setAlertsEnabled(enabled: Boolean) {
        settings.alertsEnabled = enabled
        _alertsEnabled.value = enabled
    }

    fun setAlertThrottle(minutes: Int) {
        if (minutes !in AppSettings.ALERT_THROTTLE_OPTIONS) return
        settings.alertThrottleMinutes = minutes
        _alertThrottleMinutes.value = minutes
    }

    fun clearLogs() {
        viewModelScope.launch { scanLogRepository.clear() }
    }

    private fun readPermissions() = PermissionStatus(
        scanGranted = BlePermissions.hasScan(appContext),
        connectGranted = BlePermissions.hasConnect(appContext),
        locationGranted = BlePermissions.hasLocation(appContext),
        notificationGranted = NotificationPermission.isGranted(appContext),
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
