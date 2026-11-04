package com.platojobs.pkotlinapp.ui.profile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
import com.platojobs.pkotlinapp.data.prefs.ThemeMode
import com.platojobs.pkotlinapp.databinding.FragmentProfileBinding
import com.platojobs.pkotlinapp.domain.sync.SyncStatus
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: ProfileViewModel by viewModels {
        ProfileViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private val scanLogAdapter = ScanLogAdapter()

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
                        binding.logsCount.text = getString(R.string.profile_logs_count, logs.size)
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
                if (status.scanGranted) R.color.signal_active else android.R.color.holo_red_dark,
            ),
        )

        binding.connectPermissionStatus.text = statusText(status.connectGranted)
        binding.connectPermissionStatus.setTextColor(
            ContextCompat.getColor(
                context,
                if (status.connectGranted) R.color.signal_active else android.R.color.holo_red_dark,
            ),
        )

        if (BlePermissions.requiresLocation()) {
            binding.locationPermissionStatus.text = statusText(status.locationGranted)
            binding.locationPermissionStatus.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (status.locationGranted) {
                        R.color.signal_active
                    } else {
                        android.R.color.holo_red_dark
                    },
                ),
            )
        } else {
            binding.locationPermissionStatus.setText(R.string.profile_location_not_required)
            binding.locationPermissionStatus.setTextColor(
                ContextCompat.getColor(context, R.color.text_secondary),
            )
        }
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
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/platojobs/PKotlinApp")),
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
