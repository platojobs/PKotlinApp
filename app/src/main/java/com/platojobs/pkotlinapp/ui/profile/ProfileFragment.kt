package com.platojobs.pkotlinapp.ui.profile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.platojobs.pkotlinapp.BuildConfig
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.collection.CollectionStatus
import com.platojobs.pkotlinapp.data.prefs.ThemeMode
import com.platojobs.pkotlinapp.databinding.FragmentProfileBinding
import com.platojobs.pkotlinapp.domain.account.PairingCodeFormat
import com.platojobs.pkotlinapp.domain.household.Household
import com.platojobs.pkotlinapp.domain.household.HouseholdInvitationInfo
import com.platojobs.pkotlinapp.domain.household.HouseholdMember
import com.platojobs.pkotlinapp.domain.household.HouseholdRole
import com.platojobs.pkotlinapp.domain.household.HouseholdSyncStatus
import com.platojobs.pkotlinapp.domain.household.InvitationStatus
import com.platojobs.pkotlinapp.domain.household.householdCapabilities
import com.platojobs.pkotlinapp.domain.sync.SyncStatus
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.FileSharing
import com.platojobs.pkotlinapp.ui.common.NotificationPermission
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: ProfileViewModel by viewModels {
        ProfileViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private val scanLogAdapter = ScanLogAdapter()

    private var syncingAlertSwitch = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshPermissions()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.logsList.layoutManager = LinearLayoutManager(requireContext())
        binding.logsList.adapter = scanLogAdapter

        binding.versionText.text = getString(
            R.string.profile_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.buildTypeText.text = getString(R.string.profile_build_type, BuildConfig.BUILD_TYPE)

        binding.openSettingsButton.setOnClickListener { openAppSettings() }
        binding.githubButton.setOnClickListener { openRepository() }
        binding.clearLogsButton.setOnClickListener { confirmClearLogs() }
        binding.syncNowButton.setOnClickListener { viewModel.manualSync() }
        binding.reloginButton.setOnClickListener { confirmRelogin() }
        binding.alertPermissionButton.setOnClickListener { openAppSettings() }
        setupAlertControls()
        setupAccountControls()
        setupHouseholdControls()
        setupCollectionControls()

        binding.themeGroup.check(
            when (viewModel.themeMode.value) {
                ThemeMode.SYSTEM -> R.id.themeSystemButton
                ThemeMode.LIGHT -> R.id.themeLightButton
                ThemeMode.DARK -> R.id.themeDarkButton
            },
        )
        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setTheme(
                when (checkedId) {
                    R.id.themeLightButton -> ThemeMode.LIGHT
                    R.id.themeDarkButton -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                },
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.permissions.collect { renderPermissions(it) }
                }
                launch {
                    viewModel.scanLogs.collect { logs ->
                        scanLogAdapter.submitList(logs)
                        binding.logsCount.text = resources.getQuantityString(
                            R.plurals.profile_logs_count,
                            logs.size,
                            logs.size,
                        )
                        binding.logsEmpty.visibility =
                            if (logs.isEmpty()) View.VISIBLE else View.GONE
                        binding.logsList.visibility =
                            if (logs.isEmpty()) View.GONE else View.VISIBLE
                        binding.clearLogsButton.isEnabled = logs.isNotEmpty()
                    }
                }
                launch {
                    viewModel.syncStatus.collect { renderSyncStatus(it) }
                }
                launch {
                    viewModel.syncing.collect { syncing ->
                        binding.syncNowButton.isEnabled = !syncing
                        binding.reloginButton.isEnabled = !syncing
                        binding.syncNowButton.setText(
                            if (syncing) R.string.profile_sync_running else R.string.profile_sync_now,
                        )
                    }
                }
                launch {
                    viewModel.syncMessage.collect { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.pairing.collect { renderPairing(it) }
                }
                launch {
                    viewModel.claiming.collect { claiming ->
                        binding.accountSection.claimButton.isEnabled = !claiming
                        binding.accountSection.claimCodeLayout.isEnabled = !claiming
                    }
                }
                launch {
                    viewModel.accountExport.collect { renderAccountExport(it) }
                }
                launch {
                    viewModel.deleting.collect { deleting ->
                        binding.accountSection.deleteAccountButton.isEnabled = !deleting
                    }
                }
                launch {
                    viewModel.accountMessage.collect { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                }
                launch {
                    viewModel.currentHousehold.collect { renderHousehold(it) }
                }
                launch {
                    viewModel.members.collect { renderMembers(it) }
                }
                launch {
                    viewModel.householdStatus.collect { renderHouseholdStatus(it) }
                }
                launch {
                    viewModel.invite.collect { renderInvite(it) }
                }
                launch {
                    viewModel.invitations.collect { renderInvitations(it) }
                }
                launch {
                    viewModel.householdBusy.collect { busy ->
                        binding.householdSection.householdCreateButton.isEnabled = !busy
                        binding.householdSection.householdJoinButton.isEnabled = !busy
                    }
                }
                launch {
                    viewModel.collectionStatus.collect { renderCollection(it) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onDestroyView() {
        binding.logsList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupAlertControls() {
        binding.throttle1Button.text = getString(R.string.profile_alert_throttle_minutes, 1)
        binding.throttle5Button.text = getString(R.string.profile_alert_throttle_minutes, 5)
        binding.throttle30Button.text = getString(R.string.profile_alert_throttle_minutes, 30)
        binding.alertThrottleGroup.check(
            when (viewModel.alertThrottleMinutes.value) {
                1 -> R.id.throttle1Button
                30 -> R.id.throttle30Button
                else -> R.id.throttle5Button
            },
        )
        binding.alertThrottleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setAlertThrottle(
                when (checkedId) {
                    R.id.throttle1Button -> 1
                    R.id.throttle30Button -> 30
                    else -> 5
                },
            )
        }

        syncingAlertSwitch = true
        binding.alertSwitch.isChecked = viewModel.alertsEnabled.value
        syncingAlertSwitch = false
        binding.alertSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingAlertSwitch) return@setOnCheckedChangeListener
            viewModel.setAlertsEnabled(checked)
            if (checked &&
                NotificationPermission.requiresRuntimePermission() &&
                !NotificationPermission.isGranted(requireContext())
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupAccountControls() {
        binding.accountSection.pairingGenerateButton.setOnClickListener { viewModel.generatePairingCode() }
        binding.accountSection.pairingRegenerateButton.setOnClickListener { confirmRegeneratePairing() }
        binding.accountSection.pairingCopyButton.setOnClickListener { copyPairingCode() }
        binding.accountSection.claimButton.setOnClickListener {
            viewModel.claimPairingCode(binding.accountSection.claimCodeInput.text?.toString().orEmpty())
        }
        binding.accountSection.claimCodeInput.filters = arrayOf(
            InputFilter.AllCaps(),
            InputFilter.LengthFilter(PairingCodeFormat.LENGTH),
        )
        binding.accountSection.claimCodeInput.doAfterTextChanged { editable ->
            val raw = editable?.toString().orEmpty()
            val sanitized = PairingCodeFormat.sanitize(raw)
            if (sanitized != raw) {
                binding.accountSection.claimCodeInput.setText(sanitized)
                binding.accountSection.claimCodeInput.setSelection(sanitized.length)
            }
        }
        binding.accountSection.exportAccountButton.setOnClickListener { viewModel.exportAccountData() }
        binding.accountSection.exportShareButton.setOnClickListener { shareAccountExport() }
        binding.accountSection.deleteAccountButton.setOnClickListener { confirmDeleteAccount() }
    }

    private fun setupCollectionControls() {
        binding.collectionStopButton.setOnClickListener { viewModel.stopCollection() }
    }

    // ---- 家庭与邀请码 ----

    private fun setupHouseholdControls() {
        binding.householdSection.householdCreateButton.setOnClickListener {
            showCreateHouseholdDialog()
        }
        binding.householdSection.householdRenameButton.setOnClickListener {
            showRenameHouseholdDialog()
        }
        binding.householdSection.householdLeaveButton.setOnClickListener {
            confirmLeaveHousehold()
        }
        binding.householdSection.householdInviteButton.setOnClickListener {
            viewModel.generateInvitation()
        }
        binding.householdSection.householdInviteRegenerateButton.setOnClickListener {
            viewModel.generateInvitation()
        }
        binding.householdSection.householdInviteCopyButton.setOnClickListener {
            copyInviteCode()
        }
        binding.householdSection.householdJoinButton.setOnClickListener {
            viewModel.claimInvitation(
                binding.householdSection.householdJoinCodeInput.text?.toString().orEmpty(),
            )
        }
        binding.householdSection.householdJoinCodeInput.filters = arrayOf(
            InputFilter.AllCaps(),
            InputFilter.LengthFilter(PairingCodeFormat.LENGTH),
        )
        binding.householdSection.householdJoinCodeInput.doAfterTextChanged { editable ->
            val raw = editable?.toString().orEmpty()
            val sanitized = PairingCodeFormat.sanitize(raw)
            if (sanitized != raw) {
                binding.householdSection.householdJoinCodeInput.setText(sanitized)
                binding.householdSection.householdJoinCodeInput.setSelection(sanitized.length)
            }
        }
    }

    private fun showCreateHouseholdDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.household_name_hint)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.household_create)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                viewModel.createHousehold(input.text?.toString().orEmpty())
            }
            .show()
    }

    private fun showRenameHouseholdDialog() {
        val current = viewModel.currentHousehold.value ?: return
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.household_name_hint)
            setText(current.name)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.household_rename)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                viewModel.renameHousehold(input.text?.toString().orEmpty())
            }
            .show()
    }

    private fun confirmLeaveHousehold() {
        val household = viewModel.currentHousehold.value ?: return
        if (householdCapabilities(household).leaveBlockedByMembers) {
            Snackbar.make(binding.root, R.string.household_leave_blocked, Snackbar.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.household_leave_title)
            .setMessage(R.string.household_leave_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.leaveHousehold() }
            .show()
    }

    private fun confirmRemoveMember(member: HouseholdMember) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.household_member_remove_title)
            .setMessage(R.string.household_member_remove_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.household_member_remove_action) { _, _ ->
                viewModel.removeMember(member.userId)
            }
            .show()
    }

    private fun copyInviteCode() {
        val code = viewModel.invite.value.code ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.household_invite_title), code))
        Snackbar.make(binding.root, R.string.household_invite_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun renderHousehold(household: Household?) {
        val section = binding.householdSection
        section.householdCurrent.text = if (household == null) {
            getString(R.string.household_none)
        } else {
            getString(
                R.string.household_current,
                household.name,
                getString(
                    if (household.isOwner) R.string.household_role_owner else R.string.household_role_member,
                ),
            )
        }
        // 计数来自服务端 GET /households，不用本地缓存自己数。
        section.householdCounts.visibility = if (household == null) View.GONE else View.VISIBLE
        section.householdCounts.text = household?.let {
            getString(R.string.household_counts, it.memberCount, it.deviceCount, it.roomCount)
        }.orEmpty()

        // owner 才能改家庭名、签发/撤销邀请码；member 一律隐藏这些入口。
        val capabilities = householdCapabilities(household)
        val owner = capabilities.canManage
        section.householdRenameButton.visibility = if (owner) View.VISIBLE else View.GONE
        section.householdInviteTitle.visibility = if (owner) View.VISIBLE else View.GONE
        section.householdInviteButton.visibility =
            if (owner && viewModel.invite.value.code == null) View.VISIBLE else View.GONE
        section.householdInvitationsSection.visibility = if (owner) View.VISIBLE else View.GONE

        section.householdLeaveButton.visibility =
            if (household != null && !household.isPersonal) View.VISIBLE else View.GONE
        section.householdLeaveHint.visibility =
            if (capabilities.leaveBlockedByMembers) View.VISIBLE else View.GONE
        section.householdLeaveHint.text = getString(R.string.household_leave_blocked)
    }

    private fun renderMembers(members: List<HouseholdMember>) {
        val section = binding.householdSection
        val owner = householdCapabilities(viewModel.currentHousehold.value).canRemoveMembers
        section.householdMembersList.removeAllViews()
        if (members.isEmpty()) {
            section.householdMembersList.addView(memberRow(getString(R.string.household_members_empty)))
            return
        }
        members.forEach { member ->
            val isOwnerRow = member.role == HouseholdRole.OWNER
            val role = getString(
                if (isOwnerRow) R.string.household_role_owner else R.string.household_role_member,
            )
            val row = memberRow(
                getString(R.string.household_member_row, shortUserId(member.userId), role),
            )
            // owner 可长按移除其他成员；owner 自己与 member 都没有这个入口。
            if (owner && !isOwnerRow) {
                row.isLongClickable = true
                row.setOnLongClickListener {
                    confirmRemoveMember(member)
                    true
                }
            }
            section.householdMembersList.addView(row)
        }
    }

    private fun renderInvitations(list: List<HouseholdInvitationInfo>) {
        val section = binding.householdSection
        section.householdInvitationsList.removeAllViews()
        section.householdInvitationsEmpty.visibility =
            if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { invitation ->
            val status = getString(
                when (invitation.status) {
                    InvitationStatus.ACTIVE -> R.string.invite_status_active
                    InvitationStatus.EXPIRED -> R.string.invite_status_expired
                    InvitationStatus.LOCKED -> R.string.invite_status_locked
                },
            )
            val expiry = if (invitation.expiresAt > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(invitation.expiresAt))
            } else {
                getString(R.string.household_invite_unknown_expiry)
            }
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            val label = android.widget.TextView(requireContext()).apply {
                text = getString(
                    R.string.household_invitation_row,
                    invitation.codeHashPrefix,
                    status,
                    expiry,
                )
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }
            val revoke = android.widget.TextView(requireContext()).apply {
                text = getString(R.string.household_invitation_revoke)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(),
                        R.color.danger,
                    ),
                )
                setPadding(24, 12, 24, 12)
                isClickable = true
                contentDescription = getString(R.string.household_invitation_revoke)
                setOnClickListener { viewModel.revokeInvitation(invitation.invitationId) }
            }
            row.addView(label)
            row.addView(revoke)
            section.householdInvitationsList.addView(row)
        }
    }

    private fun memberRow(text: String): View = android.widget.TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setPadding(0, 4, 0, 4)
    }

    private fun shortUserId(userId: String): String =
        if (userId.length <= 10) userId else "${userId.take(6)}…${userId.takeLast(4)}"

    private fun renderHouseholdStatus(status: HouseholdSyncStatus) {
        val section = binding.householdSection
        val unsynced = !status.synced
        section.householdSyncState.visibility = if (unsynced) View.VISIBLE else View.GONE
        section.householdSyncState.text = if (unsynced) {
            getString(R.string.household_unsynced)
        } else {
            ""
        }
    }

    private fun renderInvite(state: InviteState) {
        val section = binding.householdSection
        val hasCode = state.code != null
        section.householdInviteButton.visibility = if (hasCode) View.GONE else View.VISIBLE
        section.householdInviteButton.isEnabled = !state.loading
        section.householdInviteCode.visibility = if (hasCode) View.VISIBLE else View.GONE
        section.householdInviteCountdown.visibility = if (hasCode) View.VISIBLE else View.GONE
        section.householdInviteActions.visibility = if (hasCode) View.VISIBLE else View.GONE

        if (hasCode) {
            section.householdInviteCode.text = state.code
            section.householdInviteCountdown.text = when {
                state.expiresAt <= 0L -> getString(R.string.household_invite_unknown_expiry)
                state.expired -> getString(R.string.household_invite_expired)
                else -> getString(
                    R.string.household_invite_expires_in,
                    state.remainingSeconds / 60,
                    state.remainingSeconds % 60,
                )
            }
            section.householdInviteCopyButton.isEnabled = !state.expired
        }
        section.householdInviteError.visibility =
            if (state.error != null) View.VISIBLE else View.GONE
        section.householdInviteError.text = state.error
    }

    private fun copyPairingCode() {
        val code = viewModel.pairing.value.code ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.account_migration_title), code))
        Snackbar.make(binding.root, R.string.account_pairing_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmRegeneratePairing() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_pairing_regenerate_title)
            .setMessage(R.string.account_pairing_regenerate_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.generatePairingCode() }
            .show()
    }

    private fun renderPairing(state: PairingState) {
        val hasCode = state.code != null
        binding.accountSection.pairingGenerateButton.isEnabled = !state.loading
        binding.accountSection.pairingGenerateButton.visibility = if (hasCode) View.GONE else View.VISIBLE
        binding.accountSection.pairingCodeText.visibility = if (hasCode) View.VISIBLE else View.GONE
        binding.accountSection.pairingCountdown.visibility = if (hasCode) View.VISIBLE else View.GONE
        binding.accountSection.pairingActionsRow.visibility = if (hasCode) View.VISIBLE else View.GONE

        if (hasCode) {
            binding.accountSection.pairingCodeText.text = state.code
            binding.accountSection.pairingCountdown.text = when {
                state.expiresAt <= 0L -> getString(R.string.account_pairing_unknown_expiry)
                state.expired -> getString(R.string.account_pairing_expired)
                else -> getString(
                    R.string.account_pairing_expires_in,
                    state.remainingSeconds / 60,
                    state.remainingSeconds % 60,
                )
            }
            binding.accountSection.pairingCopyButton.isEnabled = !state.expired
            binding.accountSection.pairingRegenerateButton.isEnabled = !state.loading
        }

        binding.accountSection.pairingErrorText.visibility = if (state.error != null) View.VISIBLE else View.GONE
        binding.accountSection.pairingErrorText.text = state.error?.let {
            getString(R.string.account_pairing_generate_failed, it)
        }
    }

    private fun renderAccountExport(state: AccountExportState) {
        when (state) {
            AccountExportState.Idle -> {
                binding.accountSection.exportAccountButton.isEnabled = true
                binding.accountSection.exportAccountButton.setText(R.string.account_export_button)
                binding.accountSection.exportAccountStatus.visibility = View.GONE
                binding.accountSection.exportShareButton.visibility = View.GONE
            }

            is AccountExportState.Running -> {
                binding.accountSection.exportAccountButton.isEnabled = false
                binding.accountSection.exportAccountStatus.visibility = View.VISIBLE
                binding.accountSection.exportAccountStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_secondary),
                )
                binding.accountSection.exportAccountStatus.text = getString(
                    R.string.account_export_progress,
                    state.page,
                    state.readings,
                )
                binding.accountSection.exportShareButton.visibility = View.GONE
            }

            is AccountExportState.Ready -> {
                binding.accountSection.exportAccountButton.isEnabled = true
                binding.accountSection.exportAccountButton.setText(R.string.account_export_button)
                binding.accountSection.exportAccountStatus.visibility = View.VISIBLE
                binding.accountSection.exportAccountStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_secondary),
                )
                binding.accountSection.exportAccountStatus.text = getString(
                    R.string.account_export_ready,
                    state.readings,
                    state.pages,
                )
                binding.accountSection.exportShareButton.visibility = View.VISIBLE
            }

            is AccountExportState.Error -> {
                binding.accountSection.exportAccountButton.isEnabled = true
                binding.accountSection.exportAccountButton.setText(R.string.account_export_retry)
                binding.accountSection.exportAccountStatus.visibility = View.VISIBLE
                binding.accountSection.exportAccountStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.divider),
                )
                binding.accountSection.exportAccountStatus.text = state.message
                binding.accountSection.exportShareButton.visibility = View.GONE
            }
        }
    }

    private fun shareAccountExport() {
        val ready = viewModel.accountExport.value as? AccountExportState.Ready ?: return
        val shared = FileSharing.share(
            activity = requireActivity(),
            file = ready.file,
            mimeType = "application/json",
            chooserTitle = getString(R.string.account_export_chooser),
        )
        if (!shared) {
            Snackbar.make(binding.root, R.string.error_unknown, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteAccount() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.account_delete_confirm_title)
            .setMessage(R.string.account_delete_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.account_delete_confirm_ok) { _, _ ->
                viewModel.deleteAccount()
            }
            .show()
    }

    private fun renderCollection(status: CollectionStatus) {
        binding.collectionStatusText.text = if (status.active) {
            getString(
                R.string.profile_collection_active,
                status.deviceName.ifBlank { status.mac },
                status.count,
            )
        } else {
            getString(R.string.profile_collection_inactive)
        }
        binding.collectionStopButton.visibility =
            if (status.active) View.VISIBLE else View.GONE
    }

    private fun renderSyncStatus(status: SyncStatus) {
        binding.syncLastAt.text = if (status.lastSyncAt == null) {
            getString(R.string.profile_sync_never)
        } else {
            getString(
                R.string.profile_sync_last,
                TimeFormat.relative(requireContext(), status.lastSyncAt),
            )
        }
        binding.syncPending.text = getString(R.string.profile_sync_pending, status.pendingCount)
        binding.syncConflicts.text = getString(
            R.string.profile_sync_conflicts,
            status.lastConflictCount,
        )

        // 410 游标失效：明确提示已重新全量同步，不静默处理。
        binding.syncResyncNotice.text = status.resyncedAt?.let {
            getString(R.string.profile_sync_resynced, TimeFormat.relative(requireContext(), it))
        }
        binding.syncResyncNotice.visibility =
            if (status.resyncedAt != null) View.VISIBLE else View.GONE

        val sessionExpiresAt = viewModel.sessionExpiresAt()
        binding.sessionExpiresAt.text = if (sessionExpiresAt == null) {
            getString(R.string.profile_session_expires_unknown)
        } else {
            getString(
                R.string.profile_session_expires,
                TimeFormat.date(requireContext(), sessionExpiresAt),
            )
        }
        binding.syncLocalOnly.visibility = if (status.localOnly) View.VISIBLE else View.GONE

        // 登录失效：展示暂停说明与「重新登录」入口，同时隐藏普通同步按钮。
        binding.syncAuthExpired.visibility = if (status.authExpired) View.VISIBLE else View.GONE
        binding.reloginButton.visibility = if (status.authExpired) View.VISIBLE else View.GONE
        binding.syncNowButton.visibility = if (status.authExpired) View.GONE else View.VISIBLE

        // 后台同步的最近错误；登录失效时由上面的说明文案覆盖，不重复展示。
        val lastError = status.lastError?.takeIf { it.isNotBlank() && !status.authExpired }
        binding.syncLastError.text = lastError?.let {
            getString(R.string.profile_sync_last_error, it)
        }
        binding.syncLastError.visibility = if (lastError != null) View.VISIBLE else View.GONE
    }

    private fun confirmRelogin() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_relogin_title)
            .setMessage(R.string.profile_relogin_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.relogin() }
            .show()
    }

    private fun renderPermissions(status: PermissionStatus) {
        val context = requireContext()
        fun statusText(granted: Boolean): String =
            getString(if (granted) R.string.profile_permission_granted else R.string.profile_permission_denied)

        binding.scanPermissionStatus.text = statusText(status.scanGranted)
        binding.scanPermissionStatus.setTextColor(
            ContextCompat.getColor(
                context,
                if (status.scanGranted) R.color.success else R.color.danger,
            ),
        )

        binding.connectPermissionStatus.text = statusText(status.connectGranted)
        binding.connectPermissionStatus.setTextColor(
            ContextCompat.getColor(
                context,
                if (status.connectGranted) R.color.success else R.color.danger,
            ),
        )

        if (BlePermissions.requiresLocation()) {
            binding.locationPermissionStatus.text = statusText(status.locationGranted)
            binding.locationPermissionStatus.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (status.locationGranted) {
                        R.color.success
                    } else {
                        R.color.danger
                    },
                ),
            )
        } else {
            binding.locationPermissionStatus.setText(R.string.profile_location_not_required)
            binding.locationPermissionStatus.setTextColor(
                ContextCompat.getColor(context, R.color.text_secondary),
            )
        }

        val notificationHint = NotificationPermission.requiresRuntimePermission() &&
            !status.notificationGranted
        binding.alertPermissionHint.visibility = if (notificationHint) View.VISIBLE else View.GONE
        binding.alertPermissionButton.visibility = if (notificationHint) View.VISIBLE else View.GONE
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null),
        )
        startActivity(intent)
    }

    private fun openRepository() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, "https://github.com/platojobs/PKotlinApp".toUri()),
            )
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.error_unknown, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun confirmClearLogs() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scan_log_clear_title)
            .setMessage(R.string.scan_log_clear_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> viewModel.clearLogs() }
            .show()
    }
}
