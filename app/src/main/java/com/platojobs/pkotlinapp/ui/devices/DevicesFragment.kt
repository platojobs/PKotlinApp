package com.platojobs.pkotlinapp.ui.devices

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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.DialogRuleAddBinding
import com.platojobs.pkotlinapp.databinding.FragmentDevicesBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.EmptyStateView
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
        binding.savedEmptyContainer.addView(
            emptyState,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )

        binding.addRuleButton.setOnClickListener { showAddRuleDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.devices.collect { devices ->
                        savedAdapter.submitList(devices)
                        binding.devicesCount.text = getString(
                            R.string.devices_count_with_groups,
                            devices.size,
                            devices.mapNotNull { it.group?.takeIf { group -> group.isNotBlank() } }
                                .distinct()
                                .size,
                        )
                        binding.savedEmptyContainer.visibility =
                            if (devices.isEmpty()) View.VISIBLE else View.GONE
                        binding.savedList.visibility =
                            if (devices.isEmpty()) View.GONE else View.VISIBLE
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
        _binding = null
        super.onDestroyView()
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
            }
        }
        dialog.show()
    }
}
