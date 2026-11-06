package com.platojobs.pkotlinapp.domain.repository

import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceInfo
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.HourCount
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.domain.model.MetricCount
import com.platojobs.pkotlinapp.domain.model.RuleComparator
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingMetric
import com.platojobs.pkotlinapp.domain.reading.ReadingSample
import com.platojobs.pkotlinapp.domain.reading.ReadingSummary
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneRun
import com.platojobs.pkotlinapp.domain.scene.SceneTrigger
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    suspend fun saveDevice(device: Device)

    suspend fun getDevices(): List<Device>

    fun observeDevices(): Flow<List<Device>>

    suspend fun getDevice(mac: String): Device?

    fun observeDevice(mac: String): Flow<Device?>

    suspend fun deleteDevice(mac: String)

    /** 把设备移入房间；[roomId] 为 null 时回到「未分配」。 */
    suspend fun assignRoom(mac: String, roomId: Long?)

    /** 场景「标记设备」动作：写入真实标记时间。 */
    suspend fun markDevice(mac: String, timestamp: Long = System.currentTimeMillis())

    // 首页设备状态聚合：全部走 SQL COUNT，不把全表拉进内存。
    fun observeOnlineCount(since: Long): Flow<Int>

    fun observeOfflineCount(since: Long): Flow<Int>

    fun observeUnassignedRoomCount(): Flow<Int>

    fun observeActiveCount(): Flow<Int>

    /** 首页下钻：在线/离线设备列表。 */
    fun observeDevicesByOnlineState(online: Boolean, since: Long): Flow<List<Device>>

    fun observeUnassignedRoomDevices(): Flow<List<Device>>

    fun observeDevicesByMacs(macs: List<String>): Flow<List<Device>>
}

/** 本地场景仓库：仅本机存储，无远端同步接口。 */
interface SceneRepository {
    fun observeScenes(): Flow<List<Scene>>

    suspend fun getScenes(): List<Scene>

    suspend fun getScene(id: Long): Scene?

    /** 按触发规则查找绑定场景（引擎触发时使用）。 */
    suspend fun findSceneByRuleId(ruleId: Long): Scene?

    /**
     * [id] 为 null 表示新建；保存时整体替换动作列表与触发规则。
     * [trigger] 为 null / [com.platojobs.pkotlinapp.domain.scene.SceneTrigger.Manual] 时删除触发规则。
     */
    suspend fun saveScene(
        id: Long?,
        name: String,
        actions: List<SceneAction>,
        trigger: SceneTrigger? = null,
        enabled: Boolean = true,
    ): Long

    /** 启停场景；同时同步其触发规则的 enabled，保证引擎立即不再判定。 */
    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun deleteScene(id: Long)
}

/** 场景执行记录仓库：只增不伪造，界面只读。 */
interface SceneRunRepository {
    fun observeRecent(limit: Int = 20): Flow<List<SceneRun>>

    fun observeLatest(): Flow<SceneRun?>

    suspend fun getRecent(limit: Int): List<SceneRun>

    suspend fun add(run: SceneRun): Long

    suspend fun clear()
}

/** 本机房间：仅设备归属，不参与云端同步。 */
interface RoomRepository {
    fun observeRooms(): Flow<List<Room>>

    suspend fun getRooms(): List<Room>

    suspend fun createRoom(name: String): Long

    suspend fun renameRoom(id: Long, name: String)

    /** 删除房间，并把房间内设备统一回到「未分配」。 */
    suspend fun deleteRoom(id: Long)
}

interface RuleRepository {
    fun observeRules(): Flow<List<DeviceRule>>

    suspend fun getRules(): List<DeviceRule>

    suspend fun addRule(type: RuleType, threshold: Int): Long

    /** 新增指标阈值规则（指标 + 比较符 + 阈值 + 连续 N 条）。 */
    suspend fun addMetricRule(
        metric: String,
        comparator: RuleComparator,
        thresholdValue: Double,
        consecutive: Int,
    ): Long

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun deleteRule(id: Long)

    suspend fun markTriggered(id: Long, timestamp: Long)
}

interface RuleEventRepository {
    fun observeRecent(limit: Int = 20): Flow<List<RuleEvent>>

    /** 指定时间窗内的真实触发条数（首页今日告警摘要）。 */
    fun observeCountBetween(from: Long, to: Long): Flow<Int>

    /** 时间窗内的指标越界次数（只含带指标证据的事件）。 */
    fun observeMetricViolationCountBetween(from: Long, to: Long): Flow<Int>

    /** 时间窗内最近一条真实告警；没有则为 null。 */
    fun observeLatestBetween(from: Long, to: Long): Flow<RuleEvent?>

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

    // 首页今日概览：时间窗由调用方按本地 0 点切分，聚合在 SQL 里完成。
    fun observeCountBetween(from: Long, to: Long): Flow<Int>

    fun observeMetricCountsBetween(from: Long, to: Long): Flow<List<MetricCount>>

    fun observeHourlyCountsBetween(from: Long, to: Long): Flow<List<HourCount>>

    /** 每台设备某个指标的最新读数（首页低电量下钻）。 */
    fun observeLatestPerDeviceMetric(metric: String): Flow<List<Reading>>

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

interface MarketRepository {
    /** [deviceTypes] 来自服务端目录，避免市场自行维护第二份类型清单。 */
    suspend fun fetchMarketItems(deviceTypes: List<String>): List<MarketItem>
}
