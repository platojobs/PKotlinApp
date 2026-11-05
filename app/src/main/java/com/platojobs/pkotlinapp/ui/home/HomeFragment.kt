package com.platojobs.pkotlinapp.ui.home

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.prefs.DeviceSortMode
import com.platojobs.pkotlinapp.databinding.FragmentHomeBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by activityViewModels {
        HomeViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private lateinit var adapter: HomeAdapter
    private lateinit var threeState: ThreeStateHost
    private var lastState: HomeUiState = HomeUiState()
    private var sceneProgress: SceneProgress? = null
    private var pendingDevice: Device? = null

    private val connectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val device = pendingDevice
        pendingDevice = null
        if (device != null && result.values.all { it }) {
            connectAndOpen(device)
        } else {
            toast(getString(R.string.status_permission_missing))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)

        adapter = HomeAdapter(
            unknownName = getString(R.string.device_unknown),
            onDeviceClick = ::onDeviceClick,
            onAlertClick = { (activity as? MainActivity)?.openIntelligence() },
            onRunScene = { scene -> viewModel.runScene(scene) },
            onManageScenes = { (activity as? MainActivity)?.openIntelligence() },
        )

        binding.homeList.layoutManager = GridLayoutManager(requireContext(), 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    adapter.currentList.getOrNull(position)?.span ?: 2
            }
        }
        binding.homeList.adapter = adapter
        binding.homeList.itemAnimator = null

        binding.skeletonHome.configure(rows = 4, rowHeightPx = dp(112))
        threeState = ThreeStateHost(
            content = binding.homeContent,
            skeleton = binding.skeletonHome,
            overlay = binding.homeOverlay,
        )

        binding.searchInput.doAfterTextChanged { text ->
            viewModel.setQuery(text?.toString().orEmpty())
        }
        binding.scanAddButton.setOnClickListener { openScanDialog() }

        buildSortChips()
        (activity as? MainActivity)?.attachHeaderScroll(binding.homeList)
        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    lastState = state
                    render(state)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sceneProgress.collect { progress ->
                    sceneProgress = progress
                    renderItems(lastState)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sceneResults.collect { result ->
                    toast(result.summary(viewModel.sceneName(result.sceneId)))
                }
            }
        }
    }

    private fun render(state: HomeUiState) {
        renderGroupChips(state)
        renderItems(state)
        when {
            state.loading -> threeState.showLoading()

            state.devices.isEmpty() && !state.syncError.isNullOrBlank() ->
                threeState.showError(state.syncError) { viewModel.syncNow() }

            state.devices.isEmpty() -> threeState.showEmpty(
                title = getString(R.string.home_devices_empty_title),
                hint = getString(R.string.home_devices_empty_hint),
                action = getString(R.string.home_devices_empty_action),
                onAction = { openScanDialog() },
            )

            else -> threeState.showContent()
        }
    }

    private fun renderItems(state: HomeUiState) {
        val items = mutableListOf<HomeItem>()
        items += HomeItem.Alert(state.alertCountToday)
        if (state.devices.isNotEmpty()) {
            items += HomeItem.DevicesHeader(state.devices.size, state.groups.size)
            state.sections.forEach { section ->
                if (section.groupLabel != null || state.sections.size > 1) {
                    items += HomeItem.GroupHeader(section.groupLabel)
                }
                section.devices.forEach { device ->
                    items += HomeItem.DeviceCard(
                        device = device,
                        reading = state.latestReadings[device.mac],
                        connected = state.connectedMac == device.mac,
                    )
                }
            }
        }
        items += HomeItem.ScenesHeader(state.scenes.size)
        items += HomeItem.SceneCards(state.scenes, sceneProgress)
        adapter.submitList(items)
    }

    private fun buildSortChips() {
        binding.sortChips.removeAllViews()
        DeviceSortMode.entries.forEach { mode ->
            val chip = makeChip(sortLabel(mode), lastState.sortMode == mode) {
                viewModel.setSortMode(mode)
            }
            binding.sortChips.addView(chip)
        }
    }

    private fun renderGroupChips(state: HomeUiState) {
        val signature = state.groups to state.selectedGroup
        if (binding.groupChips.tag == signature) return
        binding.groupChips.tag = signature
        binding.groupChips.removeAllViews()
        if (state.groups.isEmpty()) {
            binding.groupScroll.visibility = View.GONE
            return
        }
        binding.groupScroll.visibility = View.VISIBLE
        binding.groupChips.addView(
            makeChip(getString(R.string.home_group_all), state.selectedGroup == null) {
                viewModel.setGroup(null)
            },
        )
        state.groups.forEach { group ->
            binding.groupChips.addView(
                makeChip(group, state.selectedGroup == group) {
                    viewModel.setGroup(group)
                },
            )
        }
        if (state.devices.any { it.group.isNullOrBlank() }) {
            binding.groupChips.addView(
                makeChip(getString(R.string.home_ungrouped), state.selectedGroup == "") {
                    viewModel.setGroup("")
                },
            )
        }
    }

    private fun makeChip(label: String, checked: Boolean, onClick: () -> Unit): Chip =
        Chip(requireContext()).apply {
            text = label
            isCheckable = true
            isChecked = checked
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }

    private fun sortLabel(mode: DeviceSortMode): String = when (mode) {
        DeviceSortMode.RECENT -> getString(R.string.home_sort_recent)
        DeviceSortMode.NAME -> getString(R.string.home_sort_name)
        DeviceSortMode.RSSI -> getString(R.string.home_sort_rssi)
        DeviceSortMode.ADDED -> getString(R.string.home_sort_added)
    }

    private fun onDeviceClick(device: Device) {
        if (!BlePermissions.bluetoothEnabled(requireContext())) {
            toast(getString(R.string.status_bluetooth_off))
            return
        }
        if (!BlePermissions.hasConnect(requireContext())) {
            pendingDevice = device
            connectPermissionLauncher.launch(BlePermissions.runtimePermissions())
            return
        }
        connectAndOpen(device)
    }

    private fun connectAndOpen(device: Device) {
        viewModel.connect(device.mac)
        startActivity(
            DeviceDetailActivity.createIntent(
                requireContext(),
                device.mac,
                device.displayName().orEmpty(),
                device.lastRssi ?: Int.MIN_VALUE,
            ),
        )
    }

    private fun openScanDialog() {
        ScanDialogFragment().show(childFragmentManager, ScanDialogFragment.TAG)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        binding.homeList.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
