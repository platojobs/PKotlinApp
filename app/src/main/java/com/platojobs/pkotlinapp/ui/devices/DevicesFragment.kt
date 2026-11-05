package com.platojobs.pkotlinapp.ui.devices

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.DialogRuleAddBinding
import com.platojobs.pkotlinapp.databinding.FragmentDevicesBinding
import com.platojobs.pkotlinapp.data.prefs.DeviceSortMode
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.EmptyStateView
import com.platojobs.pkotlinapp.ui.common.NotificationPermission
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: DevicesViewModel by viewModels {
        DevicesViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private val savedAdapter by lazy { SavedDeviceAdapter(::onDeviceClick, ::confirmDeleteDevice) }
    private val ruleAdapter by lazy {
        RuleAdapter(
            onToggle = { rule, enabled -> viewModel.setRuleEnabled(rule, enabled) },
            onDelete = { rule -> viewModel.deleteRule(rule) },
        )
    }
    private val eventAdapter by lazy { RuleEventAdapter() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private var emptyState: EmptyStateView? = null
    private var groupChipState: Pair<List<String>, String?>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.savedList.layoutManager = LinearLayoutManager(requireContext())
        binding.savedList.adapter = savedAdapter

        binding.rulesList.layoutManager = LinearLayoutManager(requireContext())
        binding.rulesList.adapter = ruleAdapter

        binding.eventsList.layoutManager = LinearLayoutManager(requireContext())
        binding.eventsList.adapter = eventAdapter

        val emptyState = EmptyStateView(requireContext()).apply {
            setIcon(R.drawable.ic_empty_ble)
            setTitle(getString(R.string.devices_empty_title))
            setHint(getString(R.string.devices_empty_hint))
        }
        this.emptyState = emptyState
        binding.savedEmptyContainer.addView(
            emptyState,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )

        setupSortChips()
        binding.searchInput.setText(viewModel.filter.value.search)
        binding.searchInput.doAfterTextChanged { text ->
            viewModel.setSearch(text?.toString().orEmpty())
        }

        binding.addRuleButton.setOnClickListener { showAddRuleDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        savedAdapter.submitList(state.visible)
                        binding.devicesCount.text = getString(
                            R.string.devices_count_with_groups,
                            state.all.size,
                            state.all.mapNotNull { it.group?.takeIf { group -> group.isNotBlank() } }
                                .distinct()
                                .size,
                        )
                        val empty = state.visible.isEmpty()
                        binding.savedEmptyContainer.visibility =
                            if (empty) View.VISIBLE else View.GONE
                        binding.savedList.visibility =
                            if (empty) View.GONE else View.VISIBLE
                        if (empty) renderEmptyState(state.all.isNotEmpty())
                        renderGroupChips(state.groups, state.filter.group)
                        syncSortChips(state.filter.sort)
                    }
                }
                launch {
                    viewModel.trends.collect { savedAdapter.trends = it }
                }
                launch {
                    viewModel.connectionState.collect { savedAdapter.connectionState = it }
                }
                launch {
                    viewModel.rules.collect { rules ->
                        ruleAdapter.submitList(rules)
                        binding.rulesEmpty.visibility =
                            if (rules.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.events.collect { events ->
                        eventAdapter.submitList(events)
                        binding.eventsEmpty.visibility =
                            if (events.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.savedList.adapter = null
        binding.rulesList.adapter = null
        binding.eventsList.adapter = null
        emptyState = null
        groupChipState = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupSortChips() {
        val labels = mapOf(
            DeviceSortMode.RECENT to R.string.devices_sort_recent,
            DeviceSortMode.NAME to R.string.devices_sort_name,
            DeviceSortMode.RSSI to R.string.devices_sort_rssi,
            DeviceSortMode.ADDED to R.string.devices_sort_added,
        )
        labels.forEach { (mode, label) ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = getString(label)
                isCheckable = true
                isChecked = viewModel.filter.value.sort == mode
                tag = mode
            }
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) viewModel.setSort(mode)
            }
            binding.sortChips.addView(chip)
        }
    }

    private fun syncSortChips(sort: DeviceSortMode) {
        repeat(binding.sortChips.childCount) { index ->
            val chip = binding.sortChips.getChildAt(index) as? Chip ?: return@repeat
            if (chip.tag is DeviceSortMode) {
                chip.isChecked = chip.tag == sort
            }
        }
    }

    private fun renderGroupChips(groups: List<String>, selected: String?) {
        val key = groups to selected
        if (groupChipState == key) return
        groupChipState = key
        binding.groupChips.removeAllViews()
        binding.groupChipScroll.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        if (groups.isEmpty()) return

        addGroupChip(label = getString(R.string.devices_group_all), value = null, selected = selected)
        groups.forEach { group ->
            val label = if (group == DeviceListFiltering.GROUP_UNGROUPED) {
                getString(R.string.devices_group_ungrouped)
            } else {
                group
            }
            addGroupChip(label = label, value = group, selected = selected)
        }
    }

    private fun addGroupChip(label: String, value: String?, selected: String?) {
        val chip = Chip(requireContext()).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            isChecked = selected == value
            tag = value ?: GROUP_ALL_TAG
        }
        chip.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val tag = chip.tag
                viewModel.setGroup(if (tag == GROUP_ALL_TAG) null else tag as String?)
            }
        }
        binding.groupChips.addView(chip)
    }

    private fun renderEmptyState(hasDevices: Boolean) {
        val view = emptyState ?: return
        if (hasDevices) {
            view.setTitle(getString(R.string.devices_filter_empty_title))
            view.setHint(getString(R.string.devices_filter_empty_hint))
            view.setAction(getString(R.string.devices_filter_clear)) {
                binding.searchInput.setText("")
                viewModel.clearFilters()
            }
        } else {
            view.setTitle(getString(R.string.devices_empty_title))
            view.setHint(getString(R.string.devices_empty_hint))
            view.setAction(getString(R.string.devices_empty_scan_action)) {
                (activity as? MainActivity)?.openScanTab()
            }
        }
    }

    private fun onDeviceClick(device: Device) {
        val context = requireContext()
        if (!BlePermissions.hasConnect(context)) {
            Snackbar.make(binding.root, R.string.error_no_permission, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_open_settings) { openAppSettings() }
                .show()
            return
        }
        if (!BlePermissions.bluetoothEnabled(context)) {
            Snackbar.make(binding.root, R.string.status_bluetooth_off, Snackbar.LENGTH_LONG).show()
            return
        }
        if (viewModel.connect(device.mac)) {
            startActivity(
                DeviceDetailActivity.createIntent(
                    context,
                    device.mac,
                    device.displayName().orEmpty(),
                    device.lastRssi,
                ),
            )
        } else {
            Snackbar.make(binding.root, R.string.error_connect_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null),
            ),
        )
    }

    private fun confirmDeleteDevice(device: Device) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.devices_delete_title)
            .setMessage(
                getString(
                    R.string.devices_delete_message,
                    device.displayName() ?: getString(R.string.device_unknown),
                ),
            )
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                viewModel.deleteDevice(device.mac)
            }
            .show()
    }

    private fun showAddRuleDialog() {
        val dialogBinding = DialogRuleAddBinding.inflate(layoutInflater)

        fun selectedType(): RuleType = when (dialogBinding.typeGroup.checkedRadioButtonId) {
            R.id.typeDisconnected -> RuleType.DISCONNECTED
            R.id.typeBondLost -> RuleType.BOND_LOST
            else -> RuleType.LOW_RSSI
        }

        fun renderType() {
            val type = selectedType()
            dialogBinding.thresholdLayout.visibility =
                if (type == RuleType.LOW_RSSI) View.VISIBLE else View.GONE
            dialogBinding.typeDescription.setText(
                when (type) {
                    RuleType.LOW_RSSI -> R.string.rule_desc_low_rssi
                    RuleType.DISCONNECTED -> R.string.rule_desc_disconnected
                    RuleType.BOND_LOST -> R.string.rule_desc_bond_lost
                },
            )
        }

        dialogBinding.typeGroup.setOnCheckedChangeListener { _, _ -> renderType() }
        renderType()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rule_add_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val type = selectedType()
                val threshold = if (type == RuleType.LOW_RSSI) {
                    val value = dialogBinding.thresholdInput.text?.toString()?.toIntOrNull()
                    if (value == null || value !in -100..0) {
                        dialogBinding.thresholdLayout.error =
                            getString(R.string.rule_threshold_error)
                        return@setOnClickListener
                    }
                    dialogBinding.thresholdLayout.error = null
                    value
                } else {
                    0
                }
                viewModel.addRule(type, threshold)
                dialog.dismiss()
                // 新增阈值规则时顺带申请通知权限；拒绝只影响提醒，不阻断规则记录。
                if (NotificationPermission.requiresRuntimePermission() &&
                    !NotificationPermission.isGranted(requireContext())
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        dialog.show()
    }
}

/** 「全部」分组 Chip 的哨兵 tag；真实分组名不会包含控制字符。 */
private const val GROUP_ALL_TAG = "\u0000all"
