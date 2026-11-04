package com.platojobs.pkotlinapp.domain.repository

import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceInfo
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.ScanLog
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    suspend fun saveDevice(device: Device)

    suspend fun getDevices(): List<Device>

    fun observeDevices(): Flow<List<Device>>

    suspend fun getDevice(mac: String): Device?

    fun observeDevice(mac: String): Flow<Device?>

    suspend fun deleteDevice(mac: String)
}

interface RuleRepository {
    fun observeRules(): Flow<List<DeviceRule>>

    suspend fun getRules(): List<DeviceRule>

    suspend fun addRule(type: RuleType, threshold: Int): Long

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun deleteRule(id: Long)

    suspend fun markTriggered(id: Long, timestamp: Long)
}

interface RuleEventRepository {
    fun observeRecent(limit: Int = 20): Flow<List<RuleEvent>>

    suspend fun getRecent(limit: Int): List<RuleEvent>

    suspend fun add(event: RuleEvent): Long

    suspend fun clear()
}

interface ScanLogRepository {
    fun observeRecent(limit: Int = 50): Flow<List<ScanLog>>

    suspend fun add(log: ScanLog)

    suspend fun clear()
}

interface DeviceInfoRepository {
    suspend fun fetchDeviceInfo(mac: String): DeviceInfo
}

interface ProductRepository {
    suspend fun fetchProducts(): List<ProductModel>
}

interface MarketRepository {
    suspend fun fetchMarketItems(): List<MarketItem>
}
