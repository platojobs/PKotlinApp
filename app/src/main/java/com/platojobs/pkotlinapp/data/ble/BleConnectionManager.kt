package com.platojobs.pkotlinapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.platojobs.pkotlinapp.domain.reading.GattReadingParser
import com.platojobs.pkotlinapp.domain.reading.ReadingSample
import com.platojobs.pkotlinapp.domain.reading.ReadingUuids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 单设备 GATT 连接管理。刻意不实现自动重连：
 * 收到 Android 16 的 ACTION_KEY_MISSING 后会写入 blockedMacs，
 * 必须由用户点击“已重新配对，重试连接”后才会解除。
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val context: Context,
    /** 解析成功的读数回调；失败路径在本地丢弃并记录日志，绝不回调脏数据。 */
    private val onReading: (deviceId: String, sample: ReadingSample) -> Unit = { _, _ -> },
) {

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    /** 每次成功读取远端 RSSI 的本地时间，供详情页展示「读数时间」；与 [rssi] 同步清空。 */
    private val _rssiUpdatedAt = MutableStateFlow<Long?>(null)
    val rssiUpdatedAt: StateFlow<Long?> = _rssiUpdatedAt.asStateFlow()

    /** 最近一次读取的结果；[RssiReadState.STALE] 表示当前展示的是上次成功值。 */
    private val _rssiReadState = MutableStateFlow(RssiReadState.IDLE)
    val rssiReadState: StateFlow<RssiReadState> = _rssiReadState.asStateFlow()

    /** GATT 同一时刻只接受一个 RSSI 请求；重复调用会被栈拒绝。 */
    @Volatile
    private var rssiReadInFlight = false

    private fun clearRssi() {
        rssiReadInFlight = false
        _rssi.value = null
        _rssiUpdatedAt.value = null
        _rssiReadState.value = RssiReadState.IDLE
    }

    private var gatt: BluetoothGatt? = null
    private val blockedMacs = mutableSetOf<String>()

    /** GATT 同一时刻只允许一个操作：CCCD 写入与初始读取串行执行，避免连续写被丢弃。 */
    private val descriptorQueue = ArrayDeque<DescriptorWrite>()
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var operationInFlight = false

    private data class DescriptorWrite(
        val descriptor: BluetoothGattDescriptor,
        val payload: ByteArray,
    )

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val mac = gatt.device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = BleConnectionState.Connected(mac)
                    runCatching { gatt.discoverServices() }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { gatt.close() }
                    if (this@BleConnectionManager.gatt === gatt) {
                        this@BleConnectionManager.gatt = null
                    }
                    resetReadingQueues()
                    clearRssi()
                    _state.value = if (blockedMacs.contains(mac)) {
                        BleConnectionState.KeyMissing(mac)
                    } else {
                        BleConnectionState.Disconnected(mac)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (this@BleConnectionManager.gatt !== gatt) return
            rssiReadInFlight = false
            // 合法 RSSI 恒为负；非负值按失败处理，保留上次成功值与时间。
            if (status == BluetoothGatt.GATT_SUCCESS && rssi < 0) {
                _rssi.value = rssi
                _rssiUpdatedAt.value = System.currentTimeMillis()
                _rssiReadState.value = RssiReadState.CURRENT
            } else {
                _rssiReadState.value = RssiReadState.STALE
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribeToReadings(gatt)
            }
        }

        /** API 33+ 的新回调；直接拿 value，避免再读 characteristic.value。 */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatchReading(gatt.device.address, characteristic.uuid.toString(), value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            dispatchReading(
                gatt.device.address,
                characteristic.uuid.toString(),
                characteristic.value ?: return,
            )
        }

        /** 心率/电池/温度/NUS TX 订阅：逐个写 CCCD，写完再串行读取可读特征。 */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            operationInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "descriptor write failed uuid=${descriptor.uuid} status=$status")
            }
            writeNextDescriptor()
        }

        /** 电池/温度等可读特征在订阅后主动读一次，拿到初始值。 */
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dispatchReading(gatt.device.address, characteristic.uuid.toString(), value)
            }
            operationInFlight = false
            startNextRead()
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value
            if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                dispatchReading(gatt.device.address, characteristic.uuid.toString(), value)
            }
            operationInFlight = false
            startNextRead()
        }
    }

    /**
     * 心率/电池/温度/NUS TX：开启 notify（有 indicate 则退回 indicate），并写 CCCD。
     * 不支持 notify 的只尝试 read；所有 GATT 操作通过队列串行执行，单个失败不影响其余特征。
     */
    private fun subscribeToReadings(gatt: BluetoothGatt) {
        val characteristics = gatt.services
            .flatMap { it.characteristics }
            .filter { ReadingUuids.normalize(it.uuid.toString()) in ReadingUuids.supported }
        val reads = mutableListOf<BluetoothGattCharacteristic>()
        characteristics.forEach { characteristic ->
            val uuid = ReadingUuids.normalize(characteristic.uuid.toString())
            val properties = characteristic.properties
            val notify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (notify || indicate) {
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    val payload = if (notify) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    }
                    if (runCatching { gatt.setCharacteristicNotification(characteristic, true) }
                            .getOrDefault(false)
                    ) {
                        descriptorQueue.addLast(DescriptorWrite(descriptor, payload))
                    } else {
                        Log.w(TAG, "setNotification failed uuid=$uuid")
                    }
                }
            }
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                reads += characteristic
            }
        }
        readQueue.addAll(reads)
        if (descriptorQueue.isEmpty()) {
            startNextRead()
        } else {
            writeNextDescriptor()
        }
    }

    private fun writeNextDescriptor() {
        val current = gatt ?: run {
            resetReadingQueues()
            return
        }
        val next = descriptorQueue.removeFirstOrNull()
        if (next == null) {
            startNextRead()
            return
        }
        operationInFlight = true
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.writeDescriptor(next.descriptor, next.payload) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                writeDescriptorCompat(current, next.descriptor, next.payload)
            }
        }.getOrDefault(false)
        if (!started) {
            // 写入未受理：继续下一个，避免整个订阅队列卡死。
            operationInFlight = false
            writeNextDescriptor()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        payload: ByteArray,
    ): Boolean {
        descriptor.value = payload
        return gatt.writeDescriptor(descriptor)
    }

    private fun startNextRead() {
        if (operationInFlight) return
        val current = gatt ?: run {
            resetReadingQueues()
            return
        }
        val next = readQueue.removeFirstOrNull() ?: return
        operationInFlight = true
        val started = runCatching { current.readCharacteristic(next) }.getOrDefault(false)
        if (!started) {
            operationInFlight = false
            startNextRead()
        }
    }

    private fun resetReadingQueues() {
        descriptorQueue.clear()
        readQueue.clear()
        operationInFlight = false
    }

    private fun dispatchReading(deviceId: String, uuid: String, value: ByteArray) {
        val samples = runCatching {
            GattReadingParser.parse(uuid, value, System.currentTimeMillis())
        }.onFailure {
            Log.w(TAG, "parser crashed uuid=$uuid len=${value.size}", it)
        }.getOrElse { emptyList() }
        if (samples.isEmpty()) {
            // 静默丢弃：只留本地日志，不写 Room，不影响 UI/连接状态。
            Log.w(
                TAG,
                "discard invalid reading uuid=${ReadingUuids.normalize(uuid)} " +
                    "len=${value.size} snapshot=${GattReadingParser.byteSnapshot(value)}",
            )
            return
        }
        samples.forEach { sample -> onReading(deviceId, sample) }
    }

    fun connect(mac: String): Boolean {
        if (blockedMacs.contains(mac)) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false

        closeCurrent()
        clearRssi()
        _state.value = BleConnectionState.Connecting(mac)

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: run {
            _state.value = BleConnectionState.Disconnected(mac)
            return false
        }
        gatt = runCatching {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) {
            _state.value = BleConnectionState.Disconnected(mac)
            return false
        }
        return true
    }

    fun disconnect(mac: String? = null) {
        val current = gatt?.device?.address ?: mac
        closeCurrent()
        clearRssi()
        _state.value = when {
            current == null -> BleConnectionState.Idle
            blockedMacs.contains(current) -> BleConnectionState.KeyMissing(current)
            else -> BleConnectionState.Disconnected(current)
        }
    }

    /** Android 16 绑定信息丢失：立即断开并禁止自动/手动重连，直到用户重新配对后确认。 */
    fun markKeyMissing(mac: String) {
        blockedMacs += mac
        _state.value = BleConnectionState.KeyMissing(mac)
        gatt?.let { runCatching { it.disconnect() } }
        gatt?.let { runCatching { it.close() } }
        gatt = null
        resetReadingQueues()
        clearRssi()
    }

    /** 用户在系统设置中重新配对后，允许再次连接。 */
    fun retryAfterRepair(mac: String): Boolean {
        blockedMacs -= mac
        return connect(mac)
    }

    fun isReconnectBlocked(mac: String): Boolean = blockedMacs.contains(mac)

    /**
     * 请求一次远端 RSSI。已有请求在途时直接忽略，避免 GATT 栈拒绝重复调用；
     * 请求发不出去（未连接或栈返回 false）时标记为上次读数。
     */
    fun readRssi() {
        val current = gatt ?: return
        if (rssiReadInFlight) return
        rssiReadInFlight = true
        val started = runCatching { current.readRemoteRssi() }.getOrDefault(false)
        if (!started) {
            rssiReadInFlight = false
            _rssiReadState.value = RssiReadState.STALE
        }
    }

    private fun closeCurrent() {
        gatt?.let { current ->
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
        gatt = null
        resetReadingQueues()
    }

    private companion object {
        const val TAG = "BleReadings"
        val CCCD_UUID: java.util.UUID =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
