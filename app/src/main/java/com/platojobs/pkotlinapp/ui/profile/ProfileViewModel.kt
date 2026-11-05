package com.platojobs.pkotlinapp.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.account.AccountService
import com.platojobs.pkotlinapp.data.collection.CollectionLauncher
import com.platojobs.pkotlinapp.data.collection.CollectionSession
import com.platojobs.pkotlinapp.data.collection.CollectionStatus
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.prefs.ThemeMode
import com.platojobs.pkotlinapp.data.sync.SyncErrorKind
import com.platojobs.pkotlinapp.data.sync.SyncManager
import com.platojobs.pkotlinapp.data.sync.classifySyncError
import com.platojobs.pkotlinapp.domain.account.PairingCode
import com.platojobs.pkotlinapp.domain.account.PairingCodeFormat
import com.platojobs.pkotlinapp.domain.account.PairingCodeInvalidException
import com.platojobs.pkotlinapp.domain.account.PairingRateLimitedException
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.sync.SyncStatus
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.NotificationPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class PermissionStatus(
    val scanGranted: Boolean,
    val connectGranted: Boolean,
    val locationGranted: Boolean,
    val notificationGranted: Boolean,
)

/** 配对码展示态：code/expiresAt 均来自服务端，remainingSeconds 为真实倒计时。 */
data class PairingState(
    val code: String? = null,
    val expiresAt: Long = 0L,
    val remainingSeconds: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val expired: Boolean get() = code != null && remainingSeconds <= 0
}

/** 账号数据导出：Running 显示真实累计条数与页码。 */
sealed interface AccountExportState {
    data object Idle : AccountExportState
    data class Running(val readings: Int, val page: Int) : AccountExportState
    data class Ready(val file: File, val readings: Int, val pages: Int) : AccountExportState
    data class Error(val message: String) : AccountExportState
}

class ProfileViewModel(
    private val appContext: Context,
    private val scanLogRepository: ScanLogRepository,
    private val settings: AppSettings,
    private val syncManager: SyncManager,
    private val accountService: AccountService,
    collectionSession: CollectionSession,
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

    // ---- 账号迁移 / 导出 / 注销 ----

    private val _pairing = MutableStateFlow(PairingState())
    val pairing: StateFlow<PairingState> = _pairing.asStateFlow()

    private var pairingTicker: Job? = null

    private val _claiming = MutableStateFlow(false)
    val claiming: StateFlow<Boolean> = _claiming.asStateFlow()

    private val _accountExport = MutableStateFlow<AccountExportState>(AccountExportState.Idle)
    val accountExport: StateFlow<AccountExportState> = _accountExport.asStateFlow()

    private val _deleting = MutableStateFlow(false)
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    private val _accountMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val accountMessage: SharedFlow<String> = _accountMessage.asSharedFlow()

    /** 后台采集实时状态（是否在采集、设备名、已采集条数）。 */
    val collectionStatus: StateFlow<CollectionStatus> = collectionSession.status

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

    // ---- 配对码 ----

    /** 生成新码；旧码立即失效，因此重新生成时明确提示。 */
    fun generatePairingCode() {
        if (_pairing.value.loading) return
        val regenerating = _pairing.value.code != null
        _pairing.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val code = accountService.createPairingCode()
                _pairing.value = PairingState(
                    code = code.code,
                    expiresAt = code.expiresAt,
                    remainingSeconds = remainingSeconds(code),
                    loading = false,
                )
                startPairingCountdown(code)
                if (regenerating) {
                    _accountMessage.emit(appContext.getString(R.string.account_pairing_regenerated))
                }
            } catch (e: Exception) {
                _pairing.update { it.copy(loading = false, error = accountErrorText(e)) }
            }
        }
    }

    private fun startPairingCountdown(code: PairingCode) {
        pairingTicker?.cancel()
        pairingTicker = viewModelScope.launch {
            while (isActive) {
                val remaining = remainingSeconds(code)
                _pairing.update { it.copy(remainingSeconds = remaining) }
                if (remaining <= 0) break
                delay(1000)
            }
        }
    }

    private fun remainingSeconds(code: PairingCode): Int {
        if (code.expiresAt <= 0L) return 0
        return ((code.expiresAt - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0)
            .toInt()
    }

    /** 领取配对码：成功后替换 token 并把本机设备/读数重新排队上传。 */
    fun claimPairingCode(raw: String) {
        if (_claiming.value) return
        val code = PairingCodeFormat.sanitize(raw)
        if (!PairingCodeFormat.isValid(code)) {
            viewModelScope.launch {
                _accountMessage.emit(appContext.getString(R.string.account_claim_format_error))
            }
            return
        }
        viewModelScope.launch {
            _claiming.value = true
            try {
                val account = accountService.claim(code)
                _accountMessage.emit(
                    appContext.getString(R.string.account_claim_success, account.userId),
                )
                refreshPermissions()
            } catch (e: Exception) {
                _accountMessage.emit(accountErrorText(e))
            } finally {
                _claiming.value = false
            }
        }
    }

    // ---- 导出 / 注销 ----

    /** 拉全账号数据并写盘；进度是真实页数与条数。 */
    fun exportAccountData() {
        if (_accountExport.value is AccountExportState.Running) return
        viewModelScope.launch {
            _accountExport.value = AccountExportState.Running(readings = 0, page = 0)
            try {
                val file = withContext(Dispatchers.IO) {
                    accountService.exportData { progress ->
                        _accountExport.value = AccountExportState.Running(
                            readings = progress.readings,
                            page = progress.page,
                        )
                    }
                }
                _accountExport.value = AccountExportState.Ready(
                    file = file.file,
                    readings = file.readings,
                    pages = file.pages,
                )
            } catch (e: Exception) {
                _accountExport.value = AccountExportState.Error(
                    appContext.getString(R.string.account_export_failed, failureText(e)),
                )
            }
        }
    }

    fun consumeAccountExport() {
        if (_accountExport.value !is AccountExportState.Running) {
            _accountExport.value = AccountExportState.Idle
        }
    }

    /** 注销：远端成功后清 token、游标、同步状态与 Room 全部业务表。 */
    fun deleteAccount() {
        if (_deleting.value) return
        viewModelScope.launch {
            _deleting.value = true
            try {
                accountService.deleteAccount()
                _pairing.value = PairingState()
                _accountExport.value = AccountExportState.Idle
                _accountMessage.emit(appContext.getString(R.string.account_delete_success))
                refreshPermissions()
            } catch (e: Exception) {
                _accountMessage.emit(
                    appContext.getString(R.string.account_delete_failure, failureText(e)),
                )
            } finally {
                _deleting.value = false
            }
        }
    }

    fun stopCollection() {
        CollectionLauncher.stop(appContext)
    }

    private fun accountErrorText(error: Throwable): String = when {
        error is PairingCodeInvalidException ->
            appContext.getString(R.string.account_claim_invalid)

        error is PairingRateLimitedException ->
            error.retryAfterSeconds?.let {
                appContext.getString(R.string.account_claim_rate_limited_seconds, it)
            } ?: appContext.getString(R.string.account_claim_rate_limited)

        error is IOException ->
            appContext.getString(R.string.account_error_network)

        classifySyncError(error) == SyncErrorKind.AUTH ->
            appContext.getString(R.string.profile_sync_error_auth)

        else -> appContext.getString(R.string.account_error_generic)
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
                    accountService = app.container.accountService,
                    collectionSession = app.container.collectionSession,
                )
            }
        }
    }
}
