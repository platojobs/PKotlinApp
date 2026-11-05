package com.platojobs.pkotlinapp.ui.home

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.prefs.DeviceSortMode
import com.platojobs.pkotlinapp.databinding.FragmentHomeBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.PermissionRationale
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
            onManageRooms = ::showRoomManager,
            onCreateRoom = { promptRoomName(getString(R.string.room_create), "") { viewModel.createRoom(it) } },
            onRoomClick = { roomId, empty ->
                if (empty) {
                    openScanDialog()
                } else {
                    viewModel.toggleRoom(roomId ?: HomeViewModel.UNASSIGNED_ROOM_ID)
                }
            },
            onDeviceLongClick = ::showRoomPicker,
        )

        val columns = if (resources.configuration.fontScale >= LARGE_FONT_SCALE) 1 else 2
        binding.homeList.layoutManager = GridLayoutManager(requireContext(), columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (columns == 1) 1
                    else adapter.currentList.getOrNull(position)?.span ?: 2
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 403/离线等家庭操作失败统一转成可读提示，不弹原始错误码。
                viewModel.householdMessages.collect { message -> toast(message) }
            }
        }
    }

    private fun render(state: HomeUiState) {
        renderHouseholdChips(state)
        renderGroupChips(state)
        renderItems(state)
        when {
            state.loading -> threeState.showLoading()

            // 还没有任何房间：房间是首页主结构，空态先引导建房间，而不是直接扫描。
            state.devices.isEmpty() && state.rooms.isEmpty() && !state.searching ->
                threeState.showEmpty(
                    title = getString(R.string.room_empty_title),
                    hint = getString(R.string.room_empty_hint),
                    action = getString(R.string.room_create),
                    onAction = {
                        promptRoomName(getString(R.string.room_create), "") {
                            viewModel.createRoom(it)
                        }
                    },
                )

            // 有房间但无设备：保留房间分区，点空房间标题即引导扫描。
            state.devices.isEmpty() && !state.searching -> threeState.showContent()

            // 搜索无结果时仍是设备维度的空态，主行动回到扫描。
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
        if (state.devices.isNotEmpty() || state.rooms.isNotEmpty()) {
            items += HomeItem.DevicesHeader(state.devices.size, state.groups.size)
            if (!state.searching && state.rooms.isEmpty()) {
                items += HomeItem.RoomEmpty
            }
            state.sections.forEach { section ->
                // 搜索结果是平铺的设备清单，折叠状态不影响搜索可见性。
                val collapsed = !state.searching && (
                    section.room?.id?.let { it in state.collapsedRooms }
                        ?: (HomeViewModel.UNASSIGNED_ROOM_ID in state.collapsedRooms)
                    )
                if (!state.searching) {
                    items += HomeItem.RoomHeader(
                        roomId = section.room?.id,
                        name = section.room?.name ?: getString(R.string.room_unassigned),
                        deviceCount = section.devices.size,
                        collapsed = collapsed,
                        empty = section.room != null && section.devices.isEmpty(),
                        unsynced = !state.householdSynced || section.room?.synced == false,
                    )
                }
                if (!collapsed) {
                    section.devices.forEach { device ->
                        items += HomeItem.DeviceCard(
                            device = device,
                            reading = state.latestReadings[device.mac],
                            connected = state.connectedMac == device.mac,
                        )
                    }
                }
            }
        }
        items += HomeItem.ScenesHeader(state.scenes.size)
        items += HomeItem.SceneCards(state.scenes, sceneProgress)
        adapter.submitList(items)
    }

    /** 多于一个家庭时才显示切换控件；单家庭不占用首页空间。 */
    private fun renderHouseholdChips(state: HomeUiState) {
        if (state.households.size <= 1) {
            binding.householdScroll.visibility = View.GONE
            return
        }
        binding.householdScroll.visibility = View.VISIBLE
        val signature = state.households.map { it.remoteId to it.name } to state.currentHousehold?.remoteId
        if (binding.householdChips.tag == signature) return
        binding.householdChips.tag = signature
        binding.householdChips.removeAllViews()
        state.households.forEach { household ->
            binding.householdChips.addView(
                makeChip(household.name, state.currentHousehold?.remoteId == household.remoteId) {
                    viewModel.switchHousehold(household.remoteId)
                },
            )
        }
    }

    /** 长按设备卡片：选择目标房间；首项为「未分配」用于移出房间。 */
    private fun showRoomPicker(device: Device) {
        if (!device.canEditRoom) {
            toast(getString(R.string.household_shared_device_readonly))
            return
        }
        val rooms = lastState.rooms
        val labels = mutableListOf(getString(R.string.room_unassigned))
        labels += rooms.map { it.name }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.room_pick_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val target = rooms.getOrNull(which - 1)
                viewModel.assignRoom(device.mac, target?.id)
                toast(
                    if (target == null) getString(R.string.room_moved_unassigned)
                    else getString(R.string.room_moved_to, target.name),
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    private fun showRoomManager() {
        val rooms = lastState.rooms
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        if (rooms.isEmpty()) {
            content.addView(TextView(context).apply {
                setText(R.string.room_manage_empty)
                setTextAppearance(R.style.TextAppearance_App_Caption)
                setTextColor(context.getColor(R.color.text_secondary))
            })
        }
        rooms.forEach { room ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(48)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply {
                text = room.name
                setTextAppearance(R.style.TextAppearance_App_Callout)
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    promptRoomName(getString(R.string.room_rename), room.name) {
                        viewModel.renameRoom(room.id, it)
                    }
                }
            })
            row.addView(TextView(context).apply {
                setText(R.string.room_delete)
                setTextAppearance(R.style.TextAppearance_App_Caption)
                setTextColor(context.getColor(R.color.danger))
                gravity = android.view.Gravity.CENTER
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setOnClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.room_delete_title)
                        .setMessage(getString(R.string.room_delete_message, room.name))
                        .setPositiveButton(R.string.room_delete) { _, _ ->
                            viewModel.deleteRoom(room.id)
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            })
            content.addView(row)
        }
        val scroll = ScrollView(context).apply { addView(content) }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.room_manage_title)
            .setView(scroll)
            .setPositiveButton(R.string.room_create) { _, _ ->
                promptRoomName(getString(R.string.room_create), "") { viewModel.createRoom(it) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun promptRoomName(title: String, initial: String, onConfirm: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.room_name_hint)
            setText(initial)
            setSelection(text.length)
        }
        val container = FrameLayout(requireContext()).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onConfirm(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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

    private companion object {
        /** 200% 字号下两列卡片只剩半屏宽，降为单列保证文案不被截断。 */
        const val LARGE_FONT_SCALE = 1.5f
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        binding.homeList.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
