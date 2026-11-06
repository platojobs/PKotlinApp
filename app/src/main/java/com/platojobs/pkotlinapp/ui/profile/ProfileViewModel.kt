package com.platojobs.pkotlinapp.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.account.AccountService
import com.platojobs.pkotlinapp.data.account.ExportStage
import com.platojobs.pkotlinapp.data.collection.CollectionLauncher
import com.platojobs.pkotlinapp.data.collection.CollectionSession
import com.platojobs.pkotlinapp.data.collection.CollectionStatus
import com.platojobs.pkotlinapp.data.feedback.FeedbackStore
import com.platojobs.pkotlinapp.data.household.HouseholdRepository
import com.platojobs.pkotlinapp.data.prefs.AppSettings
import com.platojobs.pkotlinapp.data.prefs.ThemeMode
import com.platojobs.pkotlinapp.data.push.PushRegistrationRepository
import com.platojobs.pkotlinapp.data.sync.SyncErrorKind
import com.platojobs.pkotlinapp.data.sync.SyncManager
import com.platojobs.pkotlinapp.data.sync.TokenStore
import com.platojobs.pkotlinapp.data.sync.classifySyncError
import com.platojobs.pkotlinapp.domain.account.AccountDeletionBlockedException
import com.platojobs.pkotlinapp.domain.account.AccountSession
import com.platojobs.pkotlinapp.domain.account.DeletionTokenExpiredException
import com.platojobs.pkotlinapp.domain.account.DeletionTokenInvalidException
import com.platojobs.pkotlinapp.domain.account.PairingCode
import com.platojobs.pkotlinapp.domain.account.PairingCodeFormat
import com.platojobs.pkotlinapp.domain.account.PairingCodeInvalidException
import com.platojobs.pkotlinapp.domain.account.PairingRateLimitedException
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDraft
import com.platojobs.pkotlinapp.domain.feedback.FeedbackErrorCodes
import com.platojobs.pkotlinapp.domain.feedback.FeedbackKind
import com.platojobs.pkotlinapp.domain.feedback.FeedbackSubmission
import com.platojobs.pkotlinapp.domain.household.Household
import com.platojobs.pkotlinapp.domain.household.HouseholdErrorKind
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitation
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitationInfo
import com.platojobs.pkotlinapp.domain.household.HouseholdMember
import com.platojobs.pkotlinapp.domain.household.HouseholdRole
import com.platojobs.pkotlinapp.domain.household.HouseholdSyncStatus
import com.platojobs.pkotlinapp.domain.household.OwnershipTransferUnsupportedException
import com.platojobs.pkotlinapp.domain.household.classifyHouseholdError
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.push.PushRegistrationState
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

/** 邀请码展示态：8 位码、服务端过期时间与真实倒计时。 */
data class InviteState(
    val code: String? = null,
    val expiresAt: Long = 0L,
    val remainingSeconds: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val expired: Boolean get() = code != null && remainingSeconds <= 0
}

/** 账号数据导出：服务端异步任务，状态依次为请求中 → 生成中 → 下载中 → 可分享。 */
sealed interface AccountExportState {
    data object Idle : AccountExportState
    data object Requesting : AccountExportState
    data object Pending : AccountExportState
    data object Downloading : AccountExportState
    data class Ready(val file: File) : AccountExportState
    data class Error(val message: String) : AccountExportState
}

class ProfileViewModel(
    private val appContext: Context,
    private val scanLogRepository: ScanLogRepository,
    private val settings: AppSettings,
    private val syncManager: SyncManager,
    private val accountService: AccountService,
    private val tokenStore: TokenStore,
    private val householdRepository: HouseholdRepository,
    private val feedbackStore: FeedbackStore,
    collectionSession: CollectionSession,
    private val pushRegistrationRepository: PushRegistrationRepository,
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

    /** 409：owner 的家庭还有成员；Fragment 收到后滚动到家庭区块并给出可读提示。 */
    private val _deletionBlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deletionBlocked: SharedFlow<Unit> = _deletionBlocked.asSharedFlow()

    private val _accountMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val accountMessage: SharedFlow<String> = _accountMessage.asSharedFlow()

    /** 后台采集实时状态（是否在采集、设备名、已采集条数）。 */
    val collectionStatus: StateFlow<CollectionStatus> = collectionSession.status

    /** 服务端推送注册状态；与系统通知权限解耦展示。 */
    val pushState: StateFlow<PushRegistrationState> = pushRegistrationRepository.state

    init {
        viewModelScope.launch { pushRegistrationRepository.refresh() }
    }

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
                SyncErrorKind.RESYNC -> R.string.profile_sync_error_resync
                SyncErrorKind.BUSY -> R.string.profile_sync_error_busy
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

    // ---- 意见反馈（真实发送队列） ----

    /** 待发队列：pending / sending / failed；每条都带真实重试次数与上次错误。 */
    val pendingFeedback: StateFlow<List<FeedbackDraft>> = feedbackStore.observeUnsent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 提交反馈：先落本地草稿，再按真实响应更新状态；失败时草稿永久保留。 */
    fun submitFeedback(kind: FeedbackKind, description: String, contact: String?) {
        if (description.isBlank()) {
            viewModelScope.launch {
                _accountMessage.emit(appContext.getString(R.string.feedback_description_required))
            }
            return
        }
        viewModelScope.launch {
            val result = feedbackStore.submit(kind, description, contact)
            _accountMessage.emit(feedbackResultText(result))
        }
    }

    /** 前台手动重试：立即发送一次，失败原因如实展示。 */
    fun retryFeedback(id: Long) {
        viewModelScope.launch {
            val result = feedbackStore.retry(id)
            _accountMessage.emit(feedbackResultText(result))
        }
    }

    fun discardFeedback(id: Long) {
        viewModelScope.launch { feedbackStore.discard(id) }
    }

    /** 只有 sent 才报成功；429 提示稍后再试；其余失败给服务端可读原因。 */
    private fun feedbackResultText(result: FeedbackSubmission): String = when {
        result.sent -> appContext.getString(R.string.feedback_sent)

        result.errorCode == FeedbackErrorCodes.RATE_LIMITED ->
            result.retryAfterSeconds?.let {
                appContext.getString(R.string.feedback_rate_limited_seconds, it)
            } ?: appContext.getString(R.string.feedback_rate_limited)

        result.errorCode == FeedbackErrorCodes.IDEMPOTENCY_CONFLICT ->
            appContext.getString(R.string.feedback_conflict)

        result.errorCode == FeedbackErrorCodes.NETWORK ->
            appContext.getString(R.string.feedback_network_error)

        result.errorCode == FeedbackErrorCodes.UNAUTHORIZED ->
            appContext.getString(R.string.feedback_unauthorized)

        !result.errorMessage.isNullOrBlank() ->
            appContext.getString(R.string.feedback_failed_reason, result.errorMessage)

        else -> appContext.getString(
            R.string.feedback_queued_retry,
            result.retryCount.coerceAtLeast(1),
        )
    }

    // ---- 家庭 ----

    val households: StateFlow<List<Household>> = householdRepository.households
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentHousehold: StateFlow<Household?> = householdRepository.currentHousehold

    val householdStatus: StateFlow<HouseholdSyncStatus> = householdRepository.status

    private val _members = MutableStateFlow<List<HouseholdMember>>(emptyList())
    val members: StateFlow<List<HouseholdMember>> = _members.asStateFlow()

    private val _invite = MutableStateFlow(InviteState())
    val invite: StateFlow<InviteState> = _invite.asStateFlow()

    /** 邀请码管理列表（仅 owner 可见）：哈希前缀 + 状态 + 过期时间，无明文。 */
    private val _invitations = MutableStateFlow<List<HouseholdInvitationInfo>>(emptyList())
    val invitations: StateFlow<List<HouseholdInvitationInfo>> = _invitations.asStateFlow()

    private var inviteTicker: Job? = null

    private val _householdBusy = MutableStateFlow(false)
    val householdBusy: StateFlow<Boolean> = _householdBusy.asStateFlow()

    init {
        viewModelScope.launch { refreshHousehold() }
        viewModelScope.launch {
            currentHousehold.collect { household ->
                if (household == null) {
                    _members.value = emptyList()
                    _invitations.value = emptyList()
                } else {
                    runCatching { householdRepository.members() }
                        .onSuccess { _members.value = it }
                    if (household.role == HouseholdRole.OWNER) loadInvitations()
                }
            }
        }
    }

    /** 拉家庭列表与成员；离线时保留缓存并标记未同步。 */
    fun refreshHousehold() {
        viewModelScope.launch {
            householdRepository.refreshHouseholds()
            householdRepository.currentHousehold.value?.let {
                runCatching { householdRepository.members() }.onSuccess { list ->
                    _members.value = list
                }
                if (it.role == HouseholdRole.OWNER) loadInvitations()
            }
        }
    }

    fun createHousehold(name: String) {
        if (name.isBlank() || _householdBusy.value) return
        viewModelScope.launch {
            _householdBusy.value = true
            try {
                val created = householdRepository.createHousehold(name)
                _accountMessage.emit(appContext.getString(R.string.household_created, created.name))
            } catch (e: Exception) {
                _accountMessage.emit(householdErrorText(e))
            } finally {
                _householdBusy.value = false
            }
        }
    }

    /** owner 改家庭名；member 入口在 UI 层隐藏，服务端同样 403。 */
    fun renameHousehold(name: String) {
        if (name.isBlank() || _householdBusy.value) return
        viewModelScope.launch {
            _householdBusy.value = true
            try {
                val updated = householdRepository.renameHousehold(name)
                _accountMessage.emit(appContext.getString(R.string.household_renamed, updated.name))
            } catch (e: Exception) {
                _accountMessage.emit(householdErrorText(e))
            } finally {
                _householdBusy.value = false
            }
        }
    }

    /** 生成邀请码：服务端签发 8 位一次性明文码；旧码仍按各自有效期有效。 */
    fun generateInvitation() {
        if (_invite.value.loading) return
        _invite.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val invitation = householdRepository.createInvitation()
                _invite.value = InviteState(
                    code = invitation.code,
                    expiresAt = invitation.expiresAt,
                    remainingSeconds = remainingSeconds(invitation),
                    loading = false,
                )
                startInviteCountdown(invitation)
                loadInvitations()
            } catch (e: Exception) {
                _invite.update { it.copy(loading = false, error = householdErrorText(e)) }
            }
        }
    }

    fun loadInvitations() {
        viewModelScope.launch {
            runCatching { householdRepository.invitations() }
                .onSuccess { _invitations.value = it }
        }
    }

    fun revokeInvitation(invitationId: String) {
        if (_householdBusy.value) return
        viewModelScope.launch {
            _householdBusy.value = true
            try {
                householdRepository.revokeInvitation(invitationId)
                loadInvitations()
                _accountMessage.emit(appContext.getString(R.string.invite_revoked))
            } catch (e: Exception) {
                _accountMessage.emit(householdErrorText(e))
            } finally {
                _householdBusy.value = false
            }
        }
    }

    /** owner 移除成员：对方设备会回到其个人家庭（文案在确认弹窗说明）。 */
    fun removeMember(userId: String) {
        if (_householdBusy.value) return
        viewModelScope.launch {
            _householdBusy.value = true
            try {
                householdRepository.removeMember(userId)
                _members.value = runCatching { householdRepository.members() }
                    .getOrDefault(_members.value)
                loadInvitations()
                _accountMessage.emit(appContext.getString(R.string.household_member_removed))
            } catch (e: Exception) {
                _accountMessage.emit(householdErrorText(e))
            } finally {
                _householdBusy.value = false
            }
        }
    }

    /**
     * owner 转让所有权：成功后自己降为 member，立即刷新成员与能力矩阵。
     * 403/404/409 都给可读提示并重新拉取家庭详情；老后端无该路由则静默隐藏入口。
     */
    fun transferOwnership(userId: String) {
        if (_householdBusy.value) return
        viewModelScope.launch {
            _householdBusy.value = true
            try {
                householdRepository.transferOwnership(userId)
                _members.value = runCatching { householdRepository.members() }
                    .getOrDefault(_members.value)
                _invitations.value = emptyList()
                _accountMessage.emit(appContext.getString(R.string.household_transferred))
            } catch (e: OwnershipTransferUnsupportedException) {
                // 老后端：入口随能力矩阵消失，不弹错误。
                runCatching { householdRepository.refreshHouseholds() }
                _members.value = runCatching { householdRepository.members() }
                    .getOrDefault(_members.value)
            } catch (e: Exception) {
                refreshHousehold()
                _accountMessage.emit(householdErrorText(e))
            } finally {
                _householdBusy.value = false
            }
        }
    }

    /** 服务端是否支持转让所有权：老后端为 false，UI 隐藏入口。 */
    fun supportsOwnershipTransfer(): Boolean = householdRepository.supportsOwnershipTransfer()

    /** 退出当前家庭：owner 还有其他成员时服务端 409，文案可读。 */
    fun leaveHousehold() {
        if (_householdBusy.value) return
        viewModelScope.launch {
            _householdBusy.value = true
            try {
                val remaining = householdRepository.leaveHousehold()
                _invite.value = InviteState()
                _members.value = emptyList()
                _invitations.value = emptyList()
                _accountMessage.emit(
                    appContext.getString(
                        R.string.household_left,
                        remaining?.name ?: appContext.getString(R.string.household_none),
                    ),
                )
            } catch (e: Exception) {
                _accountMessage.emit(
                    if (classifyHouseholdError(e) == HouseholdErrorKind.CONFLICT) {
                        appContext.getString(R.string.household_leave_blocked)
                    } else {
                        householdErrorText(e)
                    },
                )
            } finally {
                _householdBusy.value = false
            }
        }
    }

    private fun startInviteCountdown(invitation: HouseholdInvitation) {
        inviteTicker?.cancel()
        inviteTicker = viewModelScope.launch {
            while (isActive) {
                val remaining = remainingSeconds(invitation)
                _invite.update { it.copy(remainingSeconds = remaining) }
                if (remaining <= 0) break
                delay(1000)
            }
        }
    }

    private fun remainingSeconds(invitation: HouseholdInvitation): Int {
        if (invitation.expiresAt <= 0L) return 0
        return ((invitation.expiresAt - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0)
            .toInt()
    }

    /** 加入家庭：输入规范化复用配对码规则；404/429 使用可读文案。 */
    fun claimInvitation(raw: String) {
        if (_householdBusy.value) return
        val code = PairingCodeFormat.sanitize(raw)
        if (!PairingCodeFormat.isValid(code)) {
            viewModelScope.launch {
                _accountMessage.emit(appContext.getString(R.string.invite_code_format_error))
            }
            return
        }
        viewModelScope.launch {
            _householdBusy.value = true
            try {
                val household = householdRepository.claimInvitation(code)
                _accountMessage.emit(
                    appContext.getString(R.string.household_joined, household.name),
                )
                _invite.value = InviteState()
            } catch (e: Exception) {
                _accountMessage.emit(householdErrorText(e))
            } finally {
                _householdBusy.value = false
            }
        }
    }

    private fun householdErrorText(error: Throwable): String =
        appContext.getString(
            when (classifyHouseholdError(error)) {
                HouseholdErrorKind.PERMISSION_DENIED -> R.string.household_permission_denied
                HouseholdErrorKind.NOT_FOUND -> R.string.household_not_found
                HouseholdErrorKind.INVALID_CODE -> R.string.invite_code_invalid
                HouseholdErrorKind.RATE_LIMITED -> R.string.invite_rate_limited
                HouseholdErrorKind.CONFLICT -> R.string.household_conflict
                HouseholdErrorKind.OFFLINE -> R.string.household_offline_hint
                HouseholdErrorKind.SERVER_ERROR -> R.string.household_server_error
            },
        )

    // ---- 导出 / 注销 ----

    /** 创建服务端异步导出任务并轮询到可下载；下载链接过期时由服务侧重签。 */
    fun exportAccountData() {
        val state = _accountExport.value
        if (state is AccountExportState.Requesting ||
            state is AccountExportState.Pending ||
            state is AccountExportState.Downloading
        ) {
            return
        }
        viewModelScope.launch {
            _accountExport.value = AccountExportState.Requesting
            try {
                val file = withContext(Dispatchers.IO) {
                    accountService.exportData { stage ->
                        _accountExport.value = when (stage) {
                            ExportStage.PENDING -> AccountExportState.Pending
                            ExportStage.READY -> AccountExportState.Downloading
                        }
                    }
                }
                _accountExport.value = AccountExportState.Ready(file.file)
            } catch (e: Exception) {
                _accountExport.value = AccountExportState.Error(
                    appContext.getString(R.string.account_export_failed, failureText(e)),
                )
            }
        }
    }

    fun consumeAccountExport() {
        if (_accountExport.value is AccountExportState.Idle ||
            _accountExport.value is AccountExportState.Error
        ) {
            _accountExport.value = AccountExportState.Idle
        }
    }

    /**
     * 注销：服务端先签发一次性令牌再删除；成功后清 token、游标、同步状态与 Room
     * 全部业务表。409（家庭还有成员）单独提示并引导到家庭设置。
     */
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
            } catch (e: AccountDeletionBlockedException) {
                _deletionBlocked.emit(Unit)
            } catch (e: Exception) {
                _accountMessage.emit(
                    appContext.getString(R.string.account_delete_failure, deletionErrorText(e)),
                )
            } finally {
                _deleting.value = false
            }
        }
    }

    private fun deletionErrorText(error: Throwable): String = when (error) {
        is DeletionTokenExpiredException -> appContext.getString(R.string.account_delete_token_expired)
        is DeletionTokenInvalidException -> appContext.getString(R.string.account_delete_token_invalid)
        else -> failureText(error)
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

    /** 服务端签发的会话到期时间；只读展示，不提供续期入口。 */
    fun sessionExpiresAt(): Long? = tokenStore.sessionExpiresAt()

    // ---- 设备会话 / 退出当前会话 ----

    /** 读取设备会话列表；失败以异常上抛，由界面给可读提示并保留旧列表。 */
    suspend fun fetchSessions(): List<AccountSession> = accountService.sessions()

    /** 吊销指定会话（当前会话由服务端标记，界面不提供入口）。 */
    suspend fun revokeSession(id: String) {
        accountService.revokeSession(id)
    }

    /**
     * 退出当前会话：远端尽量吊销，本地立即清理 token 并进入重新登录态；
     * 本地设备、读数与场景全部保留。
     */
    suspend fun logout(): Boolean {
        val revoked = accountService.logout()
        syncManager.markReloginRequired()
        return revoked
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
                    tokenStore = app.container.tokenStore,
                    householdRepository = app.container.householdRepository,
                    feedbackStore = app.container.feedbackStore,
                    collectionSession = app.container.collectionSession,
                    pushRegistrationRepository = app.container.pushRegistrationRepository,
                )
            }
        }
    }
}
