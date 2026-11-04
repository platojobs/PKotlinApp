package com.platojobs.pkotlinapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.ble.BleConnectionManager
import com.platojobs.pkotlinapp.data.ble.BleError
import com.platojobs.pkotlinapp.data.ble.BleException
import com.platojobs.pkotlinapp.data.ble.BleScanner
import com.platojobs.pkotlinapp.data.sync.SyncScheduler
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.domain.repository.DeviceRepository
import com.platojobs.pkotlinapp.domain.repository.ScanLogRepository
import com.platojobs.pkotlinapp.domain.rule.RuleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val deviceRepository: DeviceRepository,
    private val scanLogRepository: ScanLogRepository,
    private val bleScanner: BleScanner,
    private val bleConnectionManager: BleConnectionManager,
    private val ruleEngine: RuleEngine,
    private val syncScheduler: SyncScheduler,
    private val unknownDeviceName: String,
) : ViewModel() {

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _ruleMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val ruleMessages: SharedFlow<String> = _ruleMessages.asSharedFlow()

    val recentDevices: StateFlow<List<Device>> = deviceRepository.observeDevices()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private var scanJob: Job? = null
    private val lastDatabaseWriteAt = mutableMapOf<String, Long>()
    private val loggedMacsInSession = mutableSetOf<String>()

    fun startScan() {
        if (scanJob?.isActive == true) return
        _scanResults.value = emptyList()
        lastDatabaseWriteAt.clear()
        loggedMacsInSession.clear()

        scanJob = viewModelScope.launch {
            _scanState.value = ScanState.Scanning
            runCatching { ruleEngine.refresh() }
            try {
                bleScanner.scan().collect { device -> handleScanResult(device) }
                _scanState.value = ScanState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: BleException) {
                _scanState.value = ScanState.Error(e.error, e.code)
            } catch (e: Exception) {
                _scanState.value = ScanState.Error(BleError.SCAN_FAILED, 0)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_scanState.value is ScanState.Scanning) {
            _scanState.value = ScanState.Idle
        }
    }

    fun connect(mac: String): Boolean = bleConnectionManager.connect(mac)

    private fun handleScanResult(device: ScannedDevice) {
        _scanResults.update { current ->
            (current.filterNot { it.mac == device.mac } + device)
                .sortedByDescending { it.rssi }
                .take(MAX_LIVE_DEVICES)
        }

        val displayName = device.name ?: unknownDeviceName
        val now = device.timestamp
        val lastWrite = lastDatabaseWriteAt[device.mac] ?: 0L
        if (now - lastWrite >= DATABASE_WRITE_INTERVAL_MS) {
            lastDatabaseWriteAt[device.mac] = now
            viewModelScope.launch {
                deviceRepository.saveDevice(
                    Device(
                        mac = device.mac,
                        name = displayName,
                        lastRssi = device.rssi,
                        lastSeenTimestamp = now,
                    ),
                )
                // 网络可用时由 WorkManager 批量推送，失败自动退避。
                runCatching { syncScheduler.schedule() }
            }
        }

        if (loggedMacsInSession.add(device.mac)) {
            viewModelScope.launch {
                scanLogRepository.add(
                    ScanLog(
                        id = 0,
                        mac = device.mac,
                        name = displayName,
                        rssi = device.rssi,
                        timestamp = now,
                    ),
                )
            }
        }

        viewModelScope.launch {
            ruleEngine.onScan(device, displayName).forEach { event ->
                _ruleMessages.tryEmit(event.message)
            }
        }
    }

    sealed interface ScanState {
        data object Idle : ScanState

        data object Scanning : ScanState

        data class Error(val error: BleError, val code: Int) : ScanState
    }

    companion object {
        private const val MAX_LIVE_DEVICES = 100
        private const val DATABASE_WRITE_INTERVAL_MS = 2_000L

        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                val container = app.container
                HomeViewModel(
                    deviceRepository = container.deviceRepository,
                    scanLogRepository = container.scanLogRepository,
                    bleScanner = container.bleScanner,
                    bleConnectionManager = container.bleConnectionManager,
                    syncScheduler = container.syncScheduler,
                    ruleEngine = container.ruleEngine,
                    unknownDeviceName = app.getString(R.string.device_unknown),
                )
            }
        }
    }
}
