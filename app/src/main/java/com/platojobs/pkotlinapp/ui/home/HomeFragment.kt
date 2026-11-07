package com.platojobs.pkotlinapp.ui.home

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
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
import com.platojobs.pkotlinapp.diagnostics.StartupDiagnostics
import com.platojobs.pkotlinapp.diagnostics.StartupStage
import com.platojobs.pkotlinapp.domain.household.householdRolePermissions
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.CloudReadingState
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.PermissionRationale
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import com.platojobs.pkotlinapp.ui.common.applyBottomInsetPadding
import com.platojobs.pkotlinapp.ui.common.deviceLinkStatusText
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            onCreateRoom = ::createRoomFlow,
            onRoomClick = { roomId, empty ->
                when {
                    empty -> openScanDialog()
                    roomId != null -> showRoomDetail(roomId)
                    else -> viewModel.toggleRoom(HomeViewModel.UNASSIGNED_ROOM_ID)
                }
            },
            onRoomToggle = { roomId ->
                viewModel.toggleRoom(roomId ?: HomeViewModel.UNASSIGNED_ROOM_ID)
            },
            onDeviceLongClick = ::showRoomPicker,
            onStatusClick = { filter ->
                viewModel.toggleStatusFilter(filter)
                binding.homeList.post {
                    if (adapter.itemCount > STATUS_SCROLL_POSITION) {
                        binding.homeList.smoothScrollToPosition(STATUS_SCROLL_POSITION)
                    }
                }
            },
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
        binding.homeList.applyBottomInsetPadding()

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
        binding.homeRefresh.setOnRefreshListener { viewModel.refreshNow() }

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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { message -> toast(message) }
            }
        }
    }

    private fun render(state: HomeUiState) {
        renderHouseholdChips(state)
        renderGroupChips(state)
        renderItems(state)
        binding.homeRefresh.isRefreshing = state.refreshing
        when {
            state.loading -> threeState.showLoading()

            // 还没有任何房间：房间是首页主结构，空态先引导建房间，而不是直接扫描。
            state.devices.isEmpty() && state.rooms.isEmpty() && !state.searching &&
                state.statusFilter == null ->
                threeState.showEmpty(
                    title = getString(R.string.room_empty_title),
                    hint = getString(R.string.room_empty_hint),
                    action = getString(R.string.room_create),
                    onAction = ::createRoomFlow,
                )

            // 状态筛选后没有匹配设备时不替换整页空态，保留聚合与筛选提示。
            state.devices.isEmpty() && state.statusFilter != null -> threeState.showContent()

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
        val items = HomeListBuilder.build(
            state = state,
            sceneProgress = sceneProgress,
            unassignedRoomLabel = getString(R.string.room_unassigned),
        )
        adapter.submitList(items)
        if (!state.loading) StartupDiagnostics.mark(StartupStage.HOME_DATA_READY)
    }

    /** 多于一个家庭时才显示切换控件；单家庭不占用首页空间。 */
    private fun renderHouseholdChips(state: HomeUiState) {
        if (state.households.size <= 1) {
            binding.householdScroll.visibility = View.GONE
            return
        }
        binding.householdScroll.visibility = View.VISIBLE
        val signature = state.households.map { Triple(it.remoteId, it.name, it.deviceCount) } to
            state.currentHousehold?.remoteId
        if (binding.householdChips.tag == signature) return
        binding.householdChips.tag = signature
        binding.householdChips.removeAllViews()
        state.households.forEach { household ->
            // 设备数取服务端统计，不数本地缓存。
            val label = getString(
                R.string.home_household_chip,
                household.name,
                household.deviceCount,
            )
            binding.householdChips.addView(
                makeChip(label, state.currentHousehold?.remoteId == household.remoteId) {
                    viewModel.switchHousehold(household.remoteId)
                },
            )
        }
    }

    /** 房间增删改排序是 owner 专属（后端 requireHouseholdOwner）；成员点击只给出说明。 */
    private fun canManageRooms(): Boolean =
        lastState.currentHousehold?.let { householdRolePermissions(it.role).manageRooms } ?: true

    private fun createRoomFlow() {
        if (!canManageRooms()) {
            toast(getString(R.string.room_manage_member_hint))
            return
        }
        promptRoomName(getString(R.string.room_create), "") { viewModel.createRoom(it) }
    }

    /**
     * 房间详情：聚合本机真实设备与最新读数，筛选后可批量标记（供场景动作引用）或断开当前连接。
     * 所有数据来自 [HomeUiState]，不额外请求也不构造假数据。
     */
    private fun showRoomDetail(roomId: Long) {
        val state = lastState
        val room = state.rooms.firstOrNull { it.id == roomId } ?: return
        val detail = RoomDetailBuilder.build(
            room = room,
            devices = state.devices,
            readings = state.latestReadings,
            cloudReadings = state.cloudReadings,
            connectedMac = state.connectedMac,
            unknownName = getString(R.string.device_unknown),
            now = state.now,
        )
        if (detail.isEmpty) {
            openScanDialog()
            return
        }

        val context = requireContext()
        val largeFont = resources.configuration.fontScale >= LARGE_FONT_SCALE
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val summaryView = TextView(context).apply {
            text = roomSummary(detail, largeFont)
            setTextAppearance(R.style.TextAppearance_App_Caption)
            setTextColor(context.getColor(R.color.text_secondary))
        }
        content.addView(summaryView)
        // 标记随设备同步；离线/未登录时仍先写本机，明确告知等待上传的状态。
        if (detail.pendingMarkCount > 0) {
            content.addView(TextView(context).apply {
                setText(
                    getString(R.string.room_detail_mark_pending_hint, detail.pendingMarkCount),
                )
                setTextAppearance(R.style.TextAppearance_App_Caption)
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, dp(4), 0, 0)
            })
        }

        val selection = LinkedHashMap<String, CheckBox>()
        detail.entries.forEach { entry ->
            val row = CheckBox(context).apply {
                text = roomDeviceText(entry)
                isChecked = true
                minimumHeight = dp(48)
                setTextAppearance(R.style.TextAppearance_App_Body)
                // 多行文案时复选框默认垂直居中，勾选框会落在文字中间；顶对齐到设备名首行。
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            selection[entry.mac] = row
            content.addView(row)
        }

        // 三个动作（标记/取消/断开）横排在 100% 字号下也会把「断开连接」挤成两行，
        // 统一改为竖排：普通字号右对齐自适应宽度，大字号整行铺满。
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        fun selectedMacs(): List<String> =
            selection.filterValues { it.isChecked }.keys.toList()
        actions.addView(batchButton(R.string.room_detail_mark, largeFont) {
            val macs = selectedMacs()
            if (macs.isEmpty()) {
                toast(getString(R.string.room_detail_select_none))
            } else {
                viewModel.markDevices(macs)
            }
        })
        actions.addView(batchButton(R.string.room_detail_unmark, largeFont) {
            val macs = selectedMacs()
            if (macs.isEmpty()) {
                toast(getString(R.string.room_detail_select_none))
            } else {
                viewModel.unmarkDevices(macs)
            }
        })
        actions.addView(batchButton(R.string.room_detail_disconnect, largeFont) {
            val macs = selectedMacs()
            if (macs.isEmpty()) {
                toast(getString(R.string.room_detail_select_none))
            } else {
                viewModel.disconnectDevices(macs)
            }
        })
        content.addView(actions)

        val scroll = ScrollView(context).apply { addView(content) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(room.name)
            .setView(scroll)
            .setNeutralButton(R.string.room_detail_add_device) { _, _ -> openScanDialog() }
            .setNegativeButton(R.string.action_close, null)
            .show()

        // 云端读数按需返回：弹窗打开期间持续刷新每行读数与汇总，不阻塞首次展示。
        val refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { latest ->
                    val latestRoom = latest.rooms.firstOrNull { it.id == roomId } ?: room
                    val fresh = RoomDetailBuilder.build(
                        room = latestRoom,
                        devices = latest.devices,
                        readings = latest.latestReadings,
                        cloudReadings = latest.cloudReadings,
                        connectedMac = latest.connectedMac,
                        unknownName = getString(R.string.device_unknown),
                        now = latest.now,
                    )
                    summaryView.text = roomSummary(fresh, largeFont)
                    val freshEntries = fresh.entries.associateBy { it.mac }
                    selection.forEach { (mac, row) ->
                        freshEntries[mac]?.let { entry -> row.text = roomDeviceText(entry) }
                    }
                }
            }
        }
        dialog.setOnDismissListener { refreshJob.cancel() }
    }

    private fun roomSummary(detail: RoomDetail, largeFont: Boolean): String =
        if (largeFont) {
            getString(
                R.string.room_detail_summary_large,
                detail.total,
                detail.localConnectedCount,
                detail.cloudRecentCount,
                detail.cloudStaleCount,
                detail.cloudUnknownCount,
                detail.markedCount,
            )
        } else {
            getString(
                R.string.room_detail_summary,
                detail.total,
                detail.localConnectedCount,
                detail.cloudRecentCount,
                detail.cloudStaleCount,
                detail.cloudUnknownCount,
                detail.markedCount,
            )
        }

    private fun roomDeviceText(entry: RoomDeviceEntry): String {
        // 与首页卡片、详情页共用同一份四档文案；跨平台设备同样标注本机不可连接。
        val status = deviceLinkStatusText(
            requireContext(),
            entry.status,
            entry.lastSeenAt,
            entry.platform,
        )
        val readingText = when {
            entry.reading != null -> formatReading(entry.reading)
            else -> when (val cloud = entry.cloudReading) {
                is CloudReadingState.Available -> getString(
                    R.string.room_detail_cloud_meta,
                    formatReading(cloud.reading.value, cloud.reading.unit),
                    formatTime(cloud.reading.recordedAt),
                )

                is CloudReadingState.Loading -> getString(R.string.room_detail_cloud_loading)
                is CloudReadingState.Failed -> getString(R.string.room_detail_cloud_unavailable)
                is CloudReadingState.Empty -> getString(R.string.room_detail_cloud_empty)
                null -> getString(R.string.room_detail_no_reading)
            }
        }
        val marked = if (entry.marked) getString(R.string.room_detail_marked_suffix) else ""
        // 名称/状态与读数分两行：长名称或 100% 字号下也不会在词中间换行。
        val meta = buildString {
            append(getString(R.string.room_detail_device_meta, readingText, marked))
            // presence 只说明「最近一次上报」；给出服务端记录的具体时间，避免被读成绿灯。
            entry.lastSeenAt?.let { at ->
                append('\n')
                append(
                    getString(
                        R.string.room_detail_last_seen,
                        TimeFormat.relative(requireContext(), at),
                        formatLastSeenTimestamp(requireContext(), at),
                    ),
                )
            }
        }
        return getString(R.string.room_detail_device_title, entry.name, status) + "\n" + meta
    }

    private fun batchButton(textRes: Int, fullWidth: Boolean, onClick: () -> Unit): MaterialButton =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(textRes)
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                if (fullWidth) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
            }
            setOnClickListener { onClick() }
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
        val context = requireContext()
        if (!canManageRooms()) {
            showReadOnlyRoomManager()
            return
        }
        val ordered = lastState.rooms.toMutableList()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        // 先声明渲染闭包，move 里可以重新渲染当前顺序（Kotlin 局部函数不支持前向引用）。
        var renderRows: () -> Unit = {}

        // 上下移动即整段提交；服务端 409 时仓库已重新拉取，首页提示「已刷新」。
        fun move(index: Int, delta: Int) {
            val target = index + delta
            if (target !in ordered.indices) return
            val item = ordered.removeAt(index)
            ordered.add(target, item)
            renderRows()
            viewModel.reorderRooms(ordered.map { it.id })
        }

        fun moveButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) =
            TextView(context).apply {
                text = label
                contentDescription = description
                setTextAppearance(R.style.TextAppearance_App_Callout)
                gravity = android.view.Gravity.CENTER
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                alpha = if (enabled) 1f else 0.3f
                isEnabled = enabled
                setOnClickListener { onClick() }
            }

        renderRows = {
            content.removeAllViews()
            if (ordered.isEmpty()) {
                content.addView(TextView(context).apply {
                    setText(R.string.room_manage_empty)
                    setTextAppearance(R.style.TextAppearance_App_Caption)
                    setTextColor(context.getColor(R.color.text_secondary))
                })
            }
            ordered.forEachIndexed { index, room ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    minimumHeight = dp(48)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(
                    moveButton("↑", getString(R.string.room_move_up), index > 0) { move(index, -1) },
                )
                row.addView(
                    moveButton(
                        "↓",
                        getString(R.string.room_move_down),
                        index < ordered.lastIndex,
                    ) { move(index, 1) },
                )
                row.addView(TextView(context).apply {
                    text = room.name
                    setTextAppearance(R.style.TextAppearance_App_Callout)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(dp(8), 0, dp(8), 0)
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
        }

        renderRows()
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

    /** 成员视图：只列房间并说明权限归属，不提供任何会 403 的入口。 */
    private fun showReadOnlyRoomManager() {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(TextView(context).apply {
            setText(R.string.room_manage_member_hint)
            setTextAppearance(R.style.TextAppearance_App_Caption)
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(8))
        })
        lastState.rooms.forEach { room ->
            content.addView(TextView(context).apply {
                text = room.name
                setTextAppearance(R.style.TextAppearance_App_Body)
                minimumHeight = dp(48)
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.room_manage_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(R.string.action_close, null)
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

    /** 供「市场」页服务入口直达配对向导。 */
    fun showScanDialog() {
        if (!isAdded) return
        ScanDialogFragment().show(childFragmentManager, ScanDialogFragment.TAG)
    }

    private fun openScanDialog() = showScanDialog()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 200% 字号下两列卡片只剩半屏宽，降为单列保证文案不被截断。 */
        const val LARGE_FONT_SCALE = 1.5f

        /** 今日概览 / 设备状态 / 最近活动之后的设备分区标题位置。 */
        const val STATUS_SCROLL_POSITION = 3
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

/** 服务端 `lastSeenAt` 的具体时刻（MM-dd HH:mm），配合相对时间一起展示。 */
internal fun formatLastSeenTimestamp(context: Context, timestamp: Long): String =
    SimpleDateFormat(context.getString(R.string.time_format), Locale.getDefault())
        .format(Date(timestamp))
