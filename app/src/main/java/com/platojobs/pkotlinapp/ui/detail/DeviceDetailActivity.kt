package com.platojobs.pkotlinapp.ui.detail

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
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
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
import com.platojobs.pkotlinapp.databinding.ActivityDeviceDetailBinding
import com.platojobs.pkotlinapp.databinding.DialogDeviceEditBinding
import com.platojobs.pkotlinapp.databinding.ItemReadingBinding
import com.platojobs.pkotlinapp.domain.model.displayDeviceTypeName
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.model.unrecognizedDeviceTypeKey
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.reading.ReadingRange
import com.platojobs.pkotlinapp.domain.reading.ReadingSummary
import com.platojobs.pkotlinapp.ui.chart.ChartBucket
import com.platojobs.pkotlinapp.ui.common.BlePermissions
import com.platojobs.pkotlinapp.ui.common.applyBottomInset
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import com.platojobs.pkotlinapp.ui.common.applyTopInset
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
        if (series != null) {
            binding.readingsChart.setSeries(
                series.buckets.map {
                    ChartBucket(it.start, it.end, it.min, it.max, it.avg)
                },
                series.from,
                series.to,
                series.unit,
            )
        }
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
                val label = getString(
                    R.string.readings_metric_with_count,
                    metricLabel(item.metric),
                    item.count.toString(),
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
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
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
