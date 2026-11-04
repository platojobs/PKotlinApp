package com.platojobs.pkotlinapp.ui.home

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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.BleError
import com.platojobs.pkotlinapp.databinding.FragmentHomeBinding
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.EmptyStateView
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private lateinit var liveAdapter: LiveDeviceAdapter
    private lateinit var recentAdapter: RecentDeviceAdapter
    private var pendingDevice: PendingDevice? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val context = context ?: return@registerForActivityResult
        val currentBinding = _binding ?: return@registerForActivityResult
        if (BlePermissions.hasScan(context) && BlePermissions.hasConnect(context)) {
            val device = pendingDevice
            if (device != null) {
                pendingDevice = null
                openDevice(device)
            } else {
                viewModel.startScan()
            }
        } else {
            // 用户拒绝系统弹窗后，如果不再触发「不再询问」，只能去设置页恢复权限。
            currentBinding.scanStatus.setText(R.string.status_permission_missing)
            Snackbar.make(currentBinding.root, R.string.status_permission_missing, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_open_settings) { openAppSettings() }
                .show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        liveAdapter = LiveDeviceAdapter { device ->
            openDevice(
                PendingDevice(
                    mac = device.mac,
                    name = device.name ?: getString(R.string.device_unknown),
                    rssi = device.rssi,
                ),
            )
        }
        binding.liveList.layoutManager = LinearLayoutManager(requireContext())
        binding.liveList.adapter = liveAdapter

        recentAdapter = RecentDeviceAdapter { device ->
            openDevice(
                PendingDevice(
                    mac = device.mac,
                    name = device.displayName() ?: getString(R.string.device_unknown),
                    rssi = device.lastRssi,
                ),
            )
        }
        binding.recentList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.recentList.adapter = recentAdapter

        val emptyState = EmptyStateView(requireContext()).apply {
            setIcon(R.drawable.ic_empty_ble)
            setTitle(getString(R.string.home_empty_title))
            setHint(getString(R.string.home_empty_hint))
            setAction(getString(R.string.action_scan)) { startScanWithPermission() }
        }
        binding.liveEmptyContainer.addView(
            emptyState,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )

        binding.scanToggle.setOnClickListener {
            if (viewModel.scanState.value is HomeViewModel.ScanState.Scanning) {
                viewModel.stopScan()
            } else {
                startScanWithPermission()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scanResults.collect { devices ->
                        liveAdapter.submitList(devices)
                        binding.liveEmptyContainer.visibility =
                            if (devices.isEmpty()) View.VISIBLE else View.GONE
                        binding.liveList.visibility =
                            if (devices.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.recentDevices.collect { devices ->
                        recentAdapter.submitList(devices)
                        binding.recentEmpty.visibility =
                            if (devices.isEmpty()) View.VISIBLE else View.GONE
                        binding.recentList.visibility =
                            if (devices.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.scanState.collect { state -> renderScanState(state) }
                }
                launch {
                    viewModel.ruleMessages.collect { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startScanIfPermitted()
    }

    override fun onStop() {
        viewModel.stopScan()
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            viewModel.stopScan()
        } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            startScanIfPermitted()
        }
    }

    override fun onDestroyView() {
        binding.liveList.adapter = null
        binding.recentList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun startScanWithPermission() {
        val context = requireContext()
        if (!BlePermissions.hasScan(context) || !BlePermissions.hasConnect(context)) {
            permissionLauncher.launch(BlePermissions.runtimePermissions())
            return
        }
        if (!BlePermissions.bluetoothEnabled(context)) {
            binding.scanStatus.setText(R.string.status_bluetooth_off)
            Snackbar.make(binding.root, R.string.status_bluetooth_off, Snackbar.LENGTH_LONG).show()
            return
        }
        viewModel.startScan()
    }

    private fun startScanIfPermitted() {
        if (isHidden) return
        val context = context ?: return
        if (!BlePermissions.hasScan(context) || !BlePermissions.hasConnect(context)) return
        viewModel.startScan()
    }

    private fun openDevice(device: PendingDevice) {
        val context = requireContext()
        if (!BlePermissions.hasConnect(context)) {
            pendingDevice = device
            permissionLauncher.launch(BlePermissions.runtimePermissions())
            return
        }
        if (!BlePermissions.bluetoothEnabled(context)) {
            Snackbar.make(binding.root, R.string.status_bluetooth_off, Snackbar.LENGTH_LONG).show()
            return
        }
        if (viewModel.connect(device.mac)) {
            startActivity(DeviceDetailActivity.createIntent(context, device.mac, device.name, device.rssi))
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

    private fun renderScanState(state: HomeViewModel.ScanState) {
        binding.scanStatus.text = when (state) {
            HomeViewModel.ScanState.Idle -> getString(R.string.status_scan_idle)
            HomeViewModel.ScanState.Scanning -> getString(R.string.status_scanning)
            is HomeViewModel.ScanState.Error -> when (state.error) {
                BleError.BLUETOOTH_DISABLED -> getString(R.string.status_bluetooth_off)
                BleError.UNSUPPORTED -> getString(R.string.status_ble_unsupported)
                BleError.MISSING_PERMISSION -> getString(R.string.status_permission_missing)
                BleError.SCAN_FAILED -> getString(R.string.status_scan_failed, state.code)
            }
        }
        binding.scanToggle.setText(
            if (state is HomeViewModel.ScanState.Scanning) {
                R.string.action_stop_scan
            } else {
                R.string.action_scan
            },
        )
    }

    private data class PendingDevice(
        val mac: String,
        val name: String,
        val rssi: Int,
    )
}
