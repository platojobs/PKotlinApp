package com.platojobs.pkotlinapp.domain.repository

import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceInfo
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingMetric
import com.platojobs.pkotlinapp.domain.reading.ReadingSample
import com.platojobs.pkotlinapp.domain.reading.ReadingSummary
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
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

    /** 指定时间窗内的真实触发条数（首页今日告警摘要）。 */
    fun observeCountBetween(from: Long, to: Long): Flow<Int>

    suspend fun getRecent(limit: Int): List<RuleEvent>

    suspend fun add(event: RuleEvent): Long

    suspend fun clear()
}

interface ScanLogRepository {
    fun observeRecent(limit: Int = 50): Flow<List<ScanLog>>

    suspend fun add(log: ScanLog)

    suspend fun clear()
}

interface ReadingRepository {
    /** 成功写入返回 true；外键/唯一键冲突等返回 false，绝不抛出。 */
    suspend fun record(deviceId: String, sample: ReadingSample): Boolean

    /** 该设备真实出现过的指标（动态 Chip 数据源）。 */
    fun observeMetrics(deviceId: String): Flow<List<String>>

    /** 最近 [limit] 个点，按时间正序返回（供图表直接使用）。 */
    fun observeRecent(
        deviceId: String,
        metric: String,
        limit: Int = 60,
    ): Flow<List<Reading>>

    suspend fun getRecent(deviceId: String, metric: String, limit: Int): List<Reading>

    /** 每台设备最近一条读数（首页卡片）。 */
    fun observeLatestPerDevice(): Flow<List<Reading>>

    /** 本地 24h 汇总（离线可用）。 */
    suspend fun summary(deviceId: String, metric: String, hours: Int = 24): ReadingSummary

    /** 本地指标清单（离线兜底）：按 lastAt 倒序，unit 取最新一条。 */
    suspend fun localMetrics(deviceId: String): List<ReadingMetric>

    /** 指定时间之后的本地读数（正序），供离线分桶兜底。 */
    suspend fun readingsSince(deviceId: String, metric: String, from: Long): List<Reading>

    /** 闭区间内的本地读数（正序），供 7 天内导出。 */
    suspend fun readingsBetween(
        deviceId: String,
        metric: String,
        from: Long,
        to: Long,
    ): List<Reading>

    /** 清理 7 天前数据；启动与同步后调用。 */
    suspend fun cleanup()
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

/** 本地场景仓库：仅本机存储，无远端同步接口。 */
interface SceneRepository {
    fun observeScenes(): Flow<List<Scene>>

    suspend fun getScenes(): List<Scene>

    /** [id] 为 null 表示新建；保存时整体替换动作列表。 */
    suspend fun saveScene(id: Long?, name: String, actions: List<SceneAction>): Long

    suspend fun deleteScene(id: Long)
}
