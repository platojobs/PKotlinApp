package com.platojobs.pkotlinapp.ui.home

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.BleError
import com.platojobs.pkotlinapp.databinding.DialogScanBinding
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.PermissionRationale
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity
import kotlinx.coroutines.launch

/**
 * 扫描弹层：真实扫描结果实时刷新，选中设备后连接并进入详情。
 * 首页从「扫描添加」或空态主按钮进入。
 */
class ScanDialogFragment : DialogFragment() {

    private var _binding: DialogScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by activityViewModels {
        HomeViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private lateinit var adapter: LiveDeviceAdapter
    private var pendingDevice: ScannedDevice? = null

    private val scanPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            viewModel.startScan()
        } else {
            toast(getString(R.string.status_permission_missing))
        }
    }

    private val connectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val device = pendingDevice
        pendingDevice = null
        if (device != null && granted.values.all { it }) {
            connectAndOpen(device)
        } else {
            toast(getString(R.string.status_permission_missing))
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        _binding = DialogScanBinding.inflate(layoutInflater)
        adapter = LiveDeviceAdapter { device -> onDeviceClick(device) }
        binding.scanList.layoutManager = LinearLayoutManager(requireContext())
        binding.scanList.adapter = adapter
        binding.scanToggle.setOnClickListener {
            if (viewModel.scanState.value is HomeViewModel.ScanState.Scanning) {
                viewModel.stopScan()
            } else {
                startScanWithPermission()
            }
        }
        observe()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.home_live_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        viewModel.stopScan()
        super.onDismiss(dialog)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanResults.collect { devices ->
                    adapter.submitList(devices)
                    binding.scanEmpty.visibility =
                        if (devices.isEmpty()) View.VISIBLE else View.GONE
                    binding.scanList.visibility =
                        if (devices.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanState.collect { state ->
                    binding.scanToggle.setText(
                        if (state is HomeViewModel.ScanState.Scanning) R.string.action_stop_scan
                        else R.string.action_scan,
                    )
                    binding.scanStatus.text = when (state) {
                        HomeViewModel.ScanState.Idle -> getString(R.string.status_scan_idle)
                        HomeViewModel.ScanState.Scanning -> getString(R.string.status_scanning)
                        is HomeViewModel.ScanState.Error -> errorText(state)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ruleMessages.collect { toast(it) }
            }
        }
    }

    private fun startScanWithPermission() {
        if (!BlePermissions.bluetoothEnabled(requireContext())) {
            toast(getString(R.string.status_bluetooth_off))
            return
        }
        if (!BlePermissions.hasScan(requireContext())) {
            PermissionRationale.show(
                this,
                R.string.onboarding_rationale_scan_title,
                R.string.onboarding_rationale_scan_body,
            ) {
                scanPermissionLauncher.launch(BlePermissions.runtimePermissions())
            }
            return
        }
        viewModel.startScan()
    }

    private fun onDeviceClick(device: ScannedDevice) {
        if (!BlePermissions.hasConnect(requireContext())) {
            PermissionRationale.show(
                this,
                R.string.onboarding_rationale_scan_title,
                R.string.onboarding_rationale_connect_body,
            ) {
                pendingDevice = device
                connectPermissionLauncher.launch(BlePermissions.runtimePermissions())
            }
            return
        }
        connectAndOpen(device)
    }

    private fun connectAndOpen(device: ScannedDevice) {
        viewModel.connect(device.mac)
        startActivity(
            DeviceDetailActivity.createIntent(
                requireContext(),
                device.mac,
                device.name.orEmpty(),
                device.rssi,
            ),
        )
        dismiss()
    }

    private fun errorText(state: HomeViewModel.ScanState.Error): String = when (state.error) {
        BleError.BLUETOOTH_DISABLED -> getString(R.string.status_bluetooth_off)
        BleError.MISSING_PERMISSION -> getString(R.string.status_permission_missing)
        BleError.UNSUPPORTED -> getString(R.string.status_ble_unsupported)
        else -> getString(R.string.status_scan_failed, state.code)
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ScanDialogFragment"
    }
}
