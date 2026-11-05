package com.platojobs.pkotlinapp.ui.detail

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import com.platojobs.pkotlinapp.data.collection.CollectionLauncher
import com.platojobs.pkotlinapp.data.collection.CollectionStatus
import com.platojobs.pkotlinapp.databinding.ActivityDeviceDetailBinding
import com.platojobs.pkotlinapp.databinding.DialogDeviceEditBinding
import com.platojobs.pkotlinapp.databinding.ItemReadingBinding
import com.platojobs.pkotlinapp.domain.model.displayDeviceTypeName
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.model.unrecognizedDeviceTypeKey
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.reading.ReadingRange
import com.platojobs.pkotlinapp.domain.reading.ReadingSummary
import com.platojobs.pkotlinapp.domain.rule.MetricThreshold
import com.platojobs.pkotlinapp.ui.chart.ChartBucket
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.FileSharing
import com.platojobs.pkotlinapp.ui.common.NotificationPermission
import com.platojobs.pkotlinapp.ui.common.applyBottomInset
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import com.platojobs.pkotlinapp.ui.common.applyTopInset
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDetailBinding

    private val mac: String by lazy { intent.getStringExtra(EXTRA_MAC).orEmpty() }
    private val deviceName: String by lazy { intent.getStringExtra(EXTRA_NAME).orEmpty() }
    private val initialRssi: Int by lazy {
        intent.getIntExtra(EXTRA_RSSI, Int.MIN_VALUE)
    }

    private val viewModel: DeviceDetailViewModel by viewModels {
        DeviceDetailViewModel.factory(
            app = application as PKotlinApplication,
            mac = mac,
            name = deviceName,
        )
    }

    private var receiverRegistered = false
    private var renderedMetricSignatures: List<String> = emptyList()
    private var renderedRangeChips = false
    private var connectedForCollection = false
    private var settingCollectionSwitch = false
    private var chartUnit: String = ""

    /** Android 13+：开启采集时申请通知权限；拒绝则降级为「无通知但继续采集」。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Snackbar.make(
                binding.root,
                R.string.collection_notification_denied,
                Snackbar.LENGTH_LONG,
            ).show()
        }
        startCollection()
    }

    /**
     * Android 16 新增的受保护系统广播：
     * - ACTION_KEY_MISSING：绑定信息丢失（要求 BLUETOOTH_CONNECT，旧版本还需要系统签名权限）
     * - ACTION_ENCRYPTION_CHANGE：链路加密状态变化
     * 两者都带 @FlaggedApi，真机上能否收到取决于系统 flag，需真机验证。
     */
    private val securityReceiver = object : BroadcastReceiver() {
        // API 36 的常量是编译期内联字符串，在旧系统上仅作为 action 名称使用，不会崩溃。
        @SuppressLint("MissingPermission", "InlinedApi")
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
            if (device != null && device.address != mac) return

            when (intent.action) {
                BluetoothDevice.ACTION_KEY_MISSING -> viewModel.onKeyMissing()
                BluetoothDevice.ACTION_ENCRYPTION_CHANGE -> {
                    val enabled = intent.getBooleanExtra(
                        BluetoothDevice.EXTRA_ENCRYPTION_ENABLED,
                        false,
                    )
                    viewModel.onEncryptionChanged(enabled)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (mac.isBlank()) {
            finish()
            return
        }

        binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyHorizontalInsets()
        binding.toolbar.applyTopInset()
        binding.scrollContent.applyBottomInset()

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        binding.macText.text = mac

        binding.connectButton.setOnClickListener {
            if (!viewModel.connect()) {
                showError(R.string.error_connect_failed)
            }
        }
        binding.disconnectButton.setOnClickListener { viewModel.disconnect() }
        binding.keyMissingRetryButton.setOnClickListener {
            if (!viewModel.retryAfterRepair()) {
                showError(R.string.error_connect_failed)
            }
        }
        binding.infoRetryButton.setOnClickListener { viewModel.loadInfo() }
        binding.editProfileButton.setOnClickListener { showEditDeviceDialog() }
        binding.readingsPermissionButton.setOnClickListener { openAppSettings() }
        binding.readingsExportButton.setOnClickListener { viewModel.exportReadings() }
        binding.readingsChart.setOnBucketSelectedListener { bucket -> renderChartSelection(bucket) }
        binding.roomRow.setOnClickListener { showRoomPickerDialog() }
        binding.collectionControls.collectionSwitch.setOnCheckedChangeListener { _, checked ->
            if (settingCollectionSwitch) return@setOnCheckedChangeListener
            viewModel.setBackgroundCollectionEnabled(checked)
            if (checked) {
                requestNotificationThenStartCollection()
            } else {
                CollectionLauncher.stop(this)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                CollectionLauncher.stop(this@DeviceDetailActivity)
                viewModel.disconnect()
                isEnabled = false
                finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state -> renderConnection(state) }
                }
                launch {
                    viewModel.rssi.collect { rssiValue -> renderRssi(rssiValue) }
                }
                launch {
                    viewModel.info.collect { state -> renderInfo(state) }
                }
                launch {
                    viewModel.encryption.collect { info -> renderEncryption(info) }
                }
                launch {
                    viewModel.readings.collect { state -> renderReadings(state) }
                }
                launch {
                    viewModel.export.collect { state -> renderExport(state) }
                }
                launch {
                    viewModel.collectionStatus.collect { status -> renderCollectionStatus(status) }
                }
                launch {
                    combine(viewModel.rooms, viewModel.roomId) { rooms, roomId -> rooms to roomId }
                        .collect { (rooms, roomId) -> renderRoom(rooms, roomId) }
                }
                launch {
                    // 403/离线等房间操作失败统一转成可读提示。
                    viewModel.roomMessages.collect { message ->
                        Toast.makeText(this@DeviceDetailActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // API 36 的 action 常量会被内联；旧系统只是收不到该广播，注册本身是安全的。
    @SuppressLint("InlinedApi")
    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_KEY_MISSING)
                addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE)
            }
            ContextCompat.registerReceiver(
                this,
                securityReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(securityReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun renderConnection(state: BleConnectionState) {
        val connected = state is BleConnectionState.Connected && state.mac == mac
        val connecting = state is BleConnectionState.Connecting && state.mac == mac
        val keyMissing = state is BleConnectionState.KeyMissing && state.mac == mac

        binding.connectionStatus.text = when {
            connected -> getString(R.string.detail_connected)
            connecting -> getString(R.string.detail_connecting)
            keyMissing -> getString(R.string.detail_key_missing_title)
            else -> getString(R.string.detail_disconnected)
        }
        binding.connectButton.visibility =
            if (!connected && !connecting && !keyMissing) View.VISIBLE else View.GONE
        binding.disconnectButton.visibility =
            if (connected || connecting) View.VISIBLE else View.GONE
        binding.keyMissingCard.visibility = if (keyMissing) View.VISIBLE else View.GONE

        // 后台采集开关只在连接态可见；连接成功时若开关已持久化为开启，自动恢复服务。
        renderCollectionSwitch(connected)
        if (connected && !connectedForCollection) {
            connectedForCollection = true
            if (viewModel.backgroundCollection.value) requestNotificationThenStartCollection()
        }
        if (!connected) {
            CollectionLauncher.stop(this)
        }
        connectedForCollection = connected
    }

    private fun renderCollectionSwitch(connected: Boolean) {
        binding.collectionControls.collectionSwitch.visibility = if (connected) View.VISIBLE else View.GONE
        binding.collectionControls.collectionHint.visibility = if (connected) View.VISIBLE else View.GONE
        settingCollectionSwitch = true
        binding.collectionControls.collectionSwitch.isChecked = viewModel.backgroundCollection.value
        settingCollectionSwitch = false
    }

    private fun renderCollectionStatus(status: CollectionStatus) {
        binding.collectionControls.collectionHint.text = if (status.active) {
            getString(R.string.profile_collection_active, status.deviceName, status.count)
        } else {
            getString(R.string.detail_collection_desc)
        }
    }

    /** Android 13+ 先申请通知权限；拒绝后仍启动服务（前台服务可运行，只是通知不可见）。 */
    private fun requestNotificationThenStartCollection() {
        if (NotificationPermission.requiresRuntimePermission()) {
            if (!NotificationPermission.isGranted(this)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startCollection()
    }

    private fun startCollection() {
        if (isFinishing || isDestroyed) return
        if (!connectedForCollection) {
            Snackbar.make(
                binding.root,
                R.string.collection_requires_connection,
                Snackbar.LENGTH_LONG,
            ).show()
            return
        }
        val result = CollectionLauncher.start(this, mac, deviceName)
        if (!result.started) {
            viewModel.setBackgroundCollectionEnabled(false)
            settingCollectionSwitch = true
            binding.collectionControls.collectionSwitch.isChecked = false
            settingCollectionSwitch = false
            Snackbar.make(
                binding.root,
                R.string.collection_start_failed,
                Snackbar.LENGTH_LONG,
            ).show()
        } else if (!result.notificationsGranted) {
            Snackbar.make(
                binding.root,
                R.string.collection_notification_denied,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    private fun renderRssi(value: Int?) {
        val display = value ?: initialRssi.takeIf { it != Int.MIN_VALUE }
        binding.signalView.setRssi(display)
        binding.rssiText.text = if (display == null) {
            getString(R.string.device_rssi_unknown)
        } else {
            getString(R.string.device_rssi_value, display)
        }
    }

    private fun renderInfo(state: DeviceDetailViewModel.InfoState) {
        binding.infoLoading.visibility =
            if (state is DeviceDetailViewModel.InfoState.Loading) View.VISIBLE else View.GONE
        binding.infoContent.visibility =
            if (state is DeviceDetailViewModel.InfoState.Success) View.VISIBLE else View.GONE
        binding.infoHint.visibility =
            if (state is DeviceDetailViewModel.InfoState.Idle) View.VISIBLE else View.GONE
        binding.infoError.visibility =
            if (state is DeviceDetailViewModel.InfoState.Error) View.VISIBLE else View.GONE
        binding.infoRetryButton.visibility =
            if (state is DeviceDetailViewModel.InfoState.Error) View.VISIBLE else View.GONE
        binding.editProfileButton.visibility =
            if (state is DeviceDetailViewModel.InfoState.Success) View.VISIBLE else View.GONE
        binding.infoOfflineHint.visibility = View.GONE

        when (state) {
            is DeviceDetailViewModel.InfoState.Success -> {
                val info = state.info
                val unknown = getString(R.string.detail_unknown_value)
                binding.infoOfflineHint.visibility =
                    if (info.fromLocalCache) View.VISIBLE else View.GONE

                binding.vendorValue.text = info.vendor?.takeIf { it.isNotBlank() } ?: unknown
                // 展示名优先；只有型号键时标注「未识别的型号」；两者都缺失才显示「型号未知」。
                val displayName = info.displayDeviceTypeName()
                val unrecognizedKey = info.unrecognizedDeviceTypeKey()
                binding.deviceTypeValue.text = when {
                    displayName != null -> displayName
                    unrecognizedKey != null -> getString(
                        R.string.detail_device_type_unrecognized,
                        unrecognizedKey,
                    )
                    else -> getString(R.string.detail_device_type_unknown)
                }
                binding.firmwareValue.text =
                    info.firmwareVersion?.takeIf { it.isNotBlank() } ?: unknown
                // 展示优先级：用户别名 > 广播名 > catalog 型号名 > 扫描时传入的名称。
                binding.aliasValue.text =
                    info.displayName() ?: deviceName.ifBlank { unknown }
                binding.groupValue.text =
                    info.group.ifBlank { getString(R.string.detail_group_empty) }
                binding.lastSeenValue.text =
                    TimeFormat.relative(this, info.lastSeenTimestamp)
                binding.compatibilityValue.text =
                    info.compatibility?.displayLabel()?.takeIf { it.isNotBlank() } ?: unknown
            }

            is DeviceDetailViewModel.InfoState.Error -> {
                binding.infoError.text = getString(
                    R.string.detail_info_error,
                    state.message ?: getString(R.string.error_network),
                )
            }

            else -> Unit
        }
    }

    private fun showEditDeviceDialog() {
        val current = (viewModel.info.value as? DeviceDetailViewModel.InfoState.Success)?.info
        val dialogBinding = DialogDeviceEditBinding.inflate(layoutInflater)
        dialogBinding.aliasInput.setText(current?.alias.orEmpty())
        dialogBinding.groupInput.setText(current?.group.orEmpty())

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.device_edit_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.device_edit_save) { _, _ ->
                val alias = dialogBinding.aliasInput.text?.toString()?.trim().orEmpty()
                val groupInput = dialogBinding.groupInput.text?.toString()?.trim().orEmpty()
                // 分组保持三态：未改动且本地未知 → null（不向服务端上报）；
                // 用户清空 → ""（显式清除）；其余 → 实际值。
                val initialGroup = current?.group
                val group = if (groupInput == initialGroup.orEmpty()) initialGroup else groupInput
                viewModel.saveProfile(alias = alias, group = group)
            }
            .show()
    }

    private fun renderRoom(rooms: List<Room>, roomId: Long?) {
        binding.roomValue.text = rooms.firstOrNull { it.id == roomId }?.name
            ?: getString(R.string.room_unassigned)
    }

    /** 移入/移出房间：「未分配」即移出；本机还没有房间时提供新建并归入。 */
    private fun showRoomPickerDialog() {
        val rooms = viewModel.rooms.value
        val labels = mutableListOf(getString(R.string.room_unassigned)) + rooms.map { it.name }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.room_pick_title)
            .setItems(labels.toTypedArray()) { _, which ->
                viewModel.assignRoom(rooms.getOrNull(which - 1)?.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
        if (rooms.isEmpty()) {
            builder.setPositiveButton(R.string.room_create) { _, _ ->
                promptNewRoom { viewModel.createRoomAndAssign(it) }
            }
        }
        builder.show()
    }

    private fun promptNewRoom(onConfirm: (String) -> Unit) {
        val input = EditText(this).apply { hint = getString(R.string.room_name_hint) }
        val container = FrameLayout(this).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.room_create)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(onConfirm)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 数据区：权限/空状态/加载三选一，正常路径展示区间与指标 Chip、图表、24h 汇总。 */
    private fun renderReadings(state: ReadingsState) {
        val hasPermission = BlePermissions.hasConnect(this)
        val hasMetrics = hasPermission && state.metrics.isNotEmpty()

        binding.readingsPermissionBlock.visibility = if (hasPermission) View.GONE else View.VISIBLE
        binding.readingsLoadingHint.visibility =
            if (hasPermission && state.loadingMetrics) View.VISIBLE else View.GONE
        binding.readingsEmptyHint.visibility =
            if (hasPermission && !state.loadingMetrics && state.metrics.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
        binding.readingsRangeTitle.visibility = if (hasMetrics) View.VISIBLE else View.GONE
        binding.readingsRangeChips.visibility = if (hasMetrics) View.VISIBLE else View.GONE
        binding.readingsChipScroll.visibility = if (hasMetrics) View.VISIBLE else View.GONE
        binding.readingsExportRow.visibility = if (hasMetrics) View.VISIBLE else View.GONE
        binding.readingsOfflineHint.visibility =
            if (hasMetrics && state.offlineLocal) View.VISIBLE else View.GONE
        binding.readingsOfflineHint.setText(
            if (state.offlineOnlyLocal7d) {
                R.string.readings_offline_local_7d
            } else {
                R.string.readings_offline_hint
            },
        )
        if (hasMetrics) {
            renderRangeChips(state.selectedRange)
            renderMetricChips(state.metrics, state.selectedMetric)
        }

        val series = state.series
        val hasBuckets = hasMetrics && series != null && series.buckets.isNotEmpty()
        binding.readingsChart.visibility = if (hasBuckets) View.VISIBLE else View.GONE
        binding.readingsChartSelection.visibility = if (hasBuckets) View.VISIBLE else View.GONE
        if (!hasBuckets) {
            binding.readingsChartSelection.setText(R.string.readings_chart_tap_hint)
        }
        if (series != null) {
            chartUnit = series.unit
            binding.readingsChart.setSeries(
                series.buckets.map {
                    ChartBucket(it.start, it.end, it.min, it.max, it.avg, it.count, it.last)
                },
                series.from,
                series.to,
                series.unit,
                metricLabel(series.metric),
                rangeLabel(state.selectedRange),
            )
        }
        renderChartSelection(binding.readingsChart.currentSelection())
        binding.readingsSeriesEmptyHint.visibility =
            if (hasMetrics && !state.loadingSeries && !hasBuckets) View.VISIBLE else View.GONE

        renderSummary(state.summary)
        renderRecent(state.recent)
    }

    /** 区间 Chip：1 小时 / 24 小时 / 7 天 / 30 天，默认 24 小时。 */
    private fun renderRangeChips(selected: ReadingRange) {
        if (!renderedRangeChips) {
            renderedRangeChips = true
            binding.readingsRangeChips.removeAllViews()
            ReadingRange.entries.forEach { range ->
                val label = rangeLabel(range)
                val chip = Chip(this).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                    isSingleLine = false
                    maxLines = 2
                    ellipsize = null
                    contentDescription = label
                    setOnClickListener { viewModel.selectRange(range) }
                }
                binding.readingsRangeChips.addView(chip)
            }
        }
        ReadingRange.entries.forEachIndexed { index, range ->
            val chip = binding.readingsRangeChips.getChildAt(index) as? Chip ?: return@forEachIndexed
            val checked = range == selected
            if (chip.isChecked != checked) chip.isChecked = checked
        }
    }

    private fun renderMetricChips(metrics: List<MetricItem>, selected: String?) {
        val signatures = metrics.map { "${it.metric}|${it.count}|${it.unit}" }
        if (renderedMetricSignatures != signatures) {
            renderedMetricSignatures = signatures
            binding.readingsChips.removeAllViews()
            metrics.forEach { item ->
                val label = resources.getQuantityString(
                    R.plurals.readings_metric_with_count,
                    item.count.toInt(),
                    metricLabel(item.metric),
                    item.count,
                )
                val chip = Chip(this).apply {
                    id = View.generateViewId()
                    text = label
                    isCheckable = true
                    // 200% 字号下允许换行，避免指标名被省略号截断。
                    isSingleLine = false
                    maxLines = 2
                    ellipsize = null
                    contentDescription = label
                    setOnClickListener { viewModel.selectMetric(item.metric) }
                }
                binding.readingsChips.addView(chip)
            }
        }
        for (index in metrics.indices) {
            val chip = binding.readingsChips.getChildAt(index) as? Chip ?: continue
            val checked = metrics[index].metric == selected
            if (chip.isChecked != checked) chip.isChecked = checked
        }
    }

    private fun rangeLabel(range: ReadingRange): String = when (range) {
        ReadingRange.HOUR_1 -> getString(R.string.readings_range_1h)
        ReadingRange.HOUR_24 -> getString(R.string.readings_range_24h)
        ReadingRange.DAY_7 -> getString(R.string.readings_range_7d)
        ReadingRange.DAY_30 -> getString(R.string.readings_range_30d)
    }

    /** 点选回调：展示当前桶的区间（本地时区）、min/max/avg/last 与真实条数；空白取消。 */
    private fun renderChartSelection(bucket: ChartBucket?) {
        if (binding.readingsChart.visibility != View.VISIBLE) {
            binding.readingsChartSelection.visibility = View.GONE
            return
        }
        binding.readingsChartSelection.visibility = View.VISIBLE
        if (bucket == null) {
            binding.readingsChartSelection.setText(R.string.readings_chart_tap_hint)
            return
        }
        val last = if (bucket.last.isFinite()) bucket.last else bucket.avg
        val count = if (bucket.count > 0L) bucket.count.toString() else "—"
        binding.readingsChartSelection.text = getString(
            R.string.readings_chart_bucket_detail,
            bucketTimeLabel(bucket.start, bucket.end),
            formatChartValue(bucket.min),
            formatChartValue(bucket.max),
            formatChartValue(bucket.avg),
            formatChartValue(last),
            count,
        )
    }

    private fun bucketTimeLabel(startMs: Long, endMs: Long): String {
        val dateFormat = SimpleDateFormat(getString(R.string.time_format), Locale.getDefault())
        val timeOnly = SimpleDateFormat(getString(R.string.time_format_time), Locale.getDefault())
        val startText = dateFormat.format(Date(startMs))
        val endText = if (dateFormat.format(Date(endMs)) == startText) {
            timeOnly.format(Date(endMs))
        } else {
            dateFormat.format(Date(endMs))
        }
        return "$startText – $endText"
    }

    private fun formatChartValue(value: Double): String =
        MetricThreshold.formatValue(value) + chartUnit

    /** 导出：进度显示真实条数；成功后走系统分享，失败给可重试提示。 */
    private fun renderExport(state: ExportState) {
        when (state) {
            ExportState.Idle -> {
                binding.readingsExportButton.isEnabled = true
                binding.readingsExportProgress.visibility = View.GONE
            }

            is ExportState.Running -> {
                binding.readingsExportButton.isEnabled = false
                binding.readingsExportProgress.visibility = View.VISIBLE
                binding.readingsExportProgress.text = getString(
                    R.string.detail_export_running,
                    state.rows,
                )
            }

            is ExportState.Ready -> {
                binding.readingsExportButton.isEnabled = true
                binding.readingsExportProgress.visibility = View.GONE
                viewModel.consumeExport()
                Snackbar.make(
                    binding.root,
                    getString(R.string.detail_export_success, state.rows),
                    Snackbar.LENGTH_LONG,
                ).show()
                shareCsv(state.file)
            }

            is ExportState.Error -> {
                binding.readingsExportButton.isEnabled = true
                binding.readingsExportProgress.visibility = View.GONE
                val message = when (state.kind) {
                    ExportState.ErrorKind.NO_METRIC ->
                        getString(R.string.detail_export_no_metric)

                    ExportState.ErrorKind.TOO_MANY -> getString(
                        R.string.detail_export_too_many,
                        state.detail.toIntOrNull() ?: EXPORT_MAX_ROWS,
                    )

                    ExportState.ErrorKind.FAILED -> getString(
                        R.string.detail_export_error,
                        state.detail.ifBlank { getString(R.string.error_unknown) },
                    )
                }
                viewModel.consumeExport()
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                    .setAction(R.string.detail_export_retry) { viewModel.exportReadings() }
                    .show()
            }
        }
    }

    private fun shareCsv(file: File) {
        FileSharing.share(
            activity = this,
            file = file,
            mimeType = "text/csv",
            chooserTitle = getString(R.string.detail_export_share_title),
        )
    }

    private fun renderSummary(summary: ReadingSummary?) {
        val visible = summary != null && summary.count > 0
        binding.readingsSummary.visibility = if (visible) View.VISIBLE else View.GONE
        if (summary == null || !visible) return
        val unit = summary.unit
        binding.readingsSummary.text = getString(
            R.string.readings_summary_line,
            formatMeasure(summary.min, unit),
            formatMeasure(summary.max, unit),
            formatMeasure(summary.avg, unit),
            formatMeasure(summary.last, unit),
        )
    }

    private fun renderRecent(recent: List<Reading>) {
        binding.readingsRecentTitle.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        binding.readingsRecentList.removeAllViews()
        recent.forEach { reading ->
            val row = ItemReadingBinding.inflate(layoutInflater, binding.readingsRecentList, false)
            row.readingValue.text = formatMeasure(reading.value, reading.unit)
            row.readingTime.text = TimeFormat.relative(this, reading.recordedAt)
            binding.readingsRecentList.addView(row.root)
        }
    }

    private fun formatMeasure(value: Double?, unit: String): String {
        if (value == null || !value.isFinite()) return getString(R.string.detail_unknown_value)
        val number = if (kotlin.math.abs(value - value.toLong()) < 0.05) {
            String.format(Locale.getDefault(), "%.0f", value)
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
        return if (unit.isBlank()) number else number + unit
    }

    private fun metricLabel(metric: String): String = when (metric) {
        ReadingMetrics.TEMPERATURE -> getString(R.string.readings_metric_temperature)
        ReadingMetrics.HUMIDITY -> getString(R.string.readings_metric_humidity)
        ReadingMetrics.BATTERY -> getString(R.string.readings_metric_battery)
        ReadingMetrics.HEART_RATE -> getString(R.string.readings_metric_heart_rate)
        ReadingMetrics.CO2 -> getString(R.string.readings_metric_co2)
        ReadingMetrics.PRESSURE -> getString(R.string.readings_metric_pressure)
        else -> getString(R.string.readings_metric_other)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun renderEncryption(info: EncryptionInfo?) {
        binding.encryptionText.text = if (info == null) {
            getString(R.string.detail_encryption_idle)
        } else {
            getString(
                R.string.detail_encryption_changed,
                getString(
                    if (info.enabled) {
                        R.string.detail_encryption_enabled
                    } else {
                        R.string.detail_encryption_disabled
                    },
                ),
            )
        }
    }

    private fun showError(messageRes: Int) {
        Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val EXTRA_MAC = "extra_mac"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_RSSI = "extra_rssi"
        private const val EXPORT_MAX_ROWS = 50_000

        fun createIntent(
            context: Context,
            mac: String,
            name: String,
            rssi: Int,
        ): Intent = Intent(context, DeviceDetailActivity::class.java).apply {
            putExtra(EXTRA_MAC, mac)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_RSSI, rssi)
        }
    }
}
