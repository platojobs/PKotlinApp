package com.platojobs.pkotlinapp.ui.detail

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import com.platojobs.pkotlinapp.databinding.ActivityDeviceDetailBinding
import com.platojobs.pkotlinapp.ui.common.applyBottomInset
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.applyTopInset
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

    /**
     * Android 16 新增的受保护系统广播：
     * - ACTION_KEY_MISSING：绑定信息丢失（要求 BLUETOOTH_CONNECT，旧版本还需要系统签名权限）
     * - ACTION_ENCRYPTION_CHANGE：链路加密状态变化
     * 两者都带 @FlaggedApi，真机上能否收到取决于系统 flag，需真机验证。
     */
    private val securityReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
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
            }
        }
    }

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

        when (state) {
            is DeviceDetailViewModel.InfoState.Success -> {
                val info = state.info
                binding.vendorValue.text =
                    info.vendor.ifBlank { getString(R.string.detail_unknown_value) }
                binding.deviceTypeValue.text =
                    info.deviceType.ifBlank { getString(R.string.detail_unknown_value) }
                binding.firmwareValue.text =
                    info.firmwareVersion.ifBlank { getString(R.string.detail_unknown_value) }
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
