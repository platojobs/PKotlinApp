package com.platojobs.pkotlinapp.domain.model

enum class RuleType {
    LOW_RSSI,
    DISCONNECTED,
    BOND_LOST,
    /** 指标读数阈值规则：指标 + 比较符 + 阈值 + 连续 N 条满足。 */
    METRIC_THRESHOLD,
    /** 定时规则：按固定间隔触发，由引擎的定时 tick 消费。 */
    SCHEDULE,
    /** 服务端 device_offline：指定设备超过 offlineSeconds 未出现；由定时 tick 判定。 */
    DEVICE_OFFLINE,
}

/** 指标阈值比较符；[symbol] 用于展示与存储，[matches] 为纯判定。 */
enum class RuleComparator(val symbol: String) {
    GREATER(">"),
    LESS("<"),
    GREATER_OR_EQUAL(">="),
    LESS_OR_EQUAL("<="),
    ;

    fun matches(value: Double, threshold: Double): Boolean = when (this) {
        GREATER -> value > threshold
        LESS -> value < threshold
        GREATER_OR_EQUAL -> value >= threshold
        LESS_OR_EQUAL -> value <= threshold
    }

    companion object {
        fun parse(raw: String?): RuleComparator =
            entries.firstOrNull { it.name == raw || it.symbol == raw } ?: GREATER
    }
}

enum class SyncState {
    PENDING,
    SYNCED,
}

/** Room 中保存的设备历史记录。 */
data class Device(
    val mac: String,
    /**
     * 用户设置的别名（openapi `alias`）。扫描不会写入该字段，
     * 只有用户在详情页编辑别名时才会更新。
     */
    val name: String = "",
    val lastRssi: Int,
    val lastSeenTimestamp: Long,
    val id: String = "",
    val platform: String = PLATFORM_ANDROID,
    val externalId: String = "",
    val updatedAt: Long = lastSeenTimestamp,
    val deletedAt: Long? = null,
    val syncState: SyncState = SyncState.PENDING,
    // 云端 enrich 字段的本地缓存，仅用于断网降级展示与下次推送复用。
    val deviceType: String? = null,
    val vendor: String? = null,
    val firmwareVersion: String? = null,
    val compatibility: String? = null,
    // catalog 展示名与云端分组：离线时优先用展示名，分组按真实值展示。
    val deviceTypeName: String? = null,
    val group: String? = null,
    /**
     * 设备广播名（openapi `advertisedName`，BLE Local Name）。
     * 扫描只更新该字段；与用户别名 [name] 独立，互不覆盖。
     */
    val advertisedName: String? = null,
    /**
     * 用户是否显式编辑过别名（包括清空）。
     * 扫描路径永远不置位；置位后即使 [name] 为空也会作为显式清空上报。
     */
    val aliasDirty: Boolean = false,
    /** 首次添加时间，只写一次；用于「按添加时间」排序。 */
    val createdAt: Long = lastSeenTimestamp,
    /** 本地房间归属；null 表示未分配。 */
    val roomId: Long? = null,
    /** 设备所属家庭（households.id）；null 表示旧数据，首次家庭同步后回填。 */
    val householdId: Long? = null,
    /** 服务端房间 UUID；null 表示未分配或仅本地房间。 */
    val remoteRoomId: String? = null,
    /** 是否为其他家庭成员共享的设备：可见但只读，不参与本机 push。 */
    val shared: Boolean = false,
    /** 房间变更待上报：离线/失败时保留，下次同步重试。 */
    val pendingRoomPatch: Boolean = false,
    val pendingRoomLocalId: Long? = null,
    val pendingHouseholdRemoteId: String? = null,
    /** 场景「标记设备」动作写入的时间戳；null 表示未标记。 */
    val markedAt: Long? = null,
    /**
     * 服务端最后一次接受该设备读数上报的接收时间（毫秒）；null 表示从未上报。
     * 服务端只读字段，只用于展示 presence 与「最近上报」，不参与 LWW，也不参与上报。
     */
    val lastSeenAt: Long? = null,
) {
    /** 共享设备不允许本机修改房间归属（服务端会对非 owner 返回 404）。 */
    val canEditRoom: Boolean get() = !shared
}

/**
 * 服务端推导的在线三态（openapi 0.21.0）：
 * - [UNKNOWN]：从未上报过读数，不能并入离线；
 * - [ONLINE]：`now - lastSeenAt <= 900s`；
 * - [OFFLINE]：曾上报，但已超出窗口。
 */
enum class DevicePresence {
    UNKNOWN,
    ONLINE,
    OFFLINE,
}

/** 服务端 presence 的判定窗口：固定 15 分钟，客户端不实现第二套阈值。 */
const val PRESENCE_WINDOW_MS = 900_000L

/**
 * 按 [lastSeenAt] 与服务端同一窗口推导 presence。
 * 只用于展示；服务端在每次响应时按自己的时钟重算，本机时钟偏差会让结果略有差异。
 */
fun presenceOf(lastSeenAt: Long?, now: Long): DevicePresence = when {
    lastSeenAt == null -> DevicePresence.UNKNOWN
    now - lastSeenAt <= PRESENCE_WINDOW_MS -> DevicePresence.ONLINE
    else -> DevicePresence.OFFLINE
}

/** 列表/详情展示的设备状态：本机连接是最强证据，其次才是服务端 presence。 */
enum class DeviceLinkStatus {
    /** 本机蓝牙当前已连接。 */
    LOCAL_CONNECTED,

    /** 本机未连接，但服务端表示最近 15 分钟内有读数上报。 */
    CLOUD_RECENT,

    /** 本机未连接，服务端表示最近一次上报已超出 15 分钟窗口。 */
    CLOUD_STALE,

    /** 本机未连接，服务端从未收到过该设备的读数上报（unknown 单独一档）。 */
    CLOUD_UNKNOWN,
}

/** 本机 BLE 连接优先于服务端 presence；[connectedMac] 为空表示当前没有连接。 */
fun deviceLinkStatus(device: Device, connectedMac: String?, now: Long): DeviceLinkStatus =
    deviceLinkStatus(
        lastSeenAt = device.lastSeenAt,
        locallyConnected = connectedMac != null && connectedMac.equals(device.mac, ignoreCase = true),
        now = now,
    )

/**
 * 详情页等只有 `lastSeenAt` 与连接状态的场景：与列表共用同一条推导规则。
 */
fun deviceLinkStatus(
    lastSeenAt: Long?,
    locallyConnected: Boolean,
    now: Long,
): DeviceLinkStatus = when {
    locallyConnected -> DeviceLinkStatus.LOCAL_CONNECTED
    else -> when (presenceOf(lastSeenAt, now)) {
        DevicePresence.ONLINE -> DeviceLinkStatus.CLOUD_RECENT
        DevicePresence.OFFLINE -> DeviceLinkStatus.CLOUD_STALE
        DevicePresence.UNKNOWN -> DeviceLinkStatus.CLOUD_UNKNOWN
    }
}

/** 本机房间：remoteId 为空表示仅存本机、尚未同步。 */
data class Room(
    val id: Long,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val remoteId: String? = null,
    val householdId: Long? = null,
) {
    val synced: Boolean get() = remoteId != null
}

/**
 * 列表/详情展示优先级：用户别名 > 广播名 > catalog 型号展示名。
 * 全部缺失时由 UI 回退「未知设备」。
 */
fun Device.displayName(): String? =
    name.takeIf { it.isNotBlank() }
        ?: advertisedName?.takeIf { it.isNotBlank() }
        ?: deviceTypeName?.takeIf { it.isNotBlank() }

const val PLATFORM_ANDROID = "android"

/**
 * 「未获取到 RSSI」的跨组件哨兵值。
 *
 * 通知、Intent 等通道无法传 null 时统一使用它；UI 层优先传 null。
 * 分档逻辑（`RssiLevel`）会把 null 与该值都映射为「未知」档，
 * 因此它不会被显示成「信号极弱」。其它任何整数值都按 dBm 参与分档。
 */
const val UNKNOWN_RSSI: Int = Int.MIN_VALUE

/** 设备唯一键：Android 使用规范化大写 MAC。 */
fun normalizeExternalId(mac: String): String = mac.trim().uppercase()

/** 云端读数查询用的 externalId：历史数据缺失时回退本机主键。 */
val Device.cloudExternalId: String get() = externalId.ifBlank { mac }

/**
 * 本机扫描/连接得到的 RSSI；从未在本机扫描到时为 null。
 *
 * 数据层用 0 作为「没有本机 RSSI」的占位值（`RemoteSyncDataSource` 等从云端
 * 同步来的设备会写入 0）。0 dBm 在 BLE 上实际不可达，但语义上仍是一个合法
 * 强信号值，所以展示层必须先经过这里转成未知，不能直接喂给信号分档。
 */
val Device.localRssi: Int? get() = lastRssi.takeIf { it != 0 }

/** 云端最近读数在 UI 状态中的键：平台必须参与，避免跨端 externalId 撞车。 */
val Device.cloudReadingKey: String get() = "${platform.trim().lowercase()}|${cloudExternalId.uppercase()}"

/** 一次 BLE 扫描回调中的设备快照。 */
data class ScannedDevice(
    val name: String?,
    val mac: String,
    val rssi: Int,
    val timestamp: Long,
)

/** 兼容性元数据（openapi `Compatibility`，保留扩展字段）。 */
data class Compatibility(
    val platforms: List<String> = emptyList(),
    val minAppVersion: String = "",
    val protocol: String = "",
    val features: List<String> = emptyList(),
    val deviceTypes: List<String> = emptyList(),
) {
    /** 展示用文案；空字段直接省略，不显示 "null" 或空串。 */
    fun displayLabel(): String {
        val parts = buildList {
            if (platforms.isNotEmpty()) add(platforms.joinToString("/"))
            if (protocol.isNotBlank()) add(protocol)
            if (features.isNotEmpty()) add(features.joinToString("/"))
            if (deviceTypes.isNotEmpty()) add("适配 " + deviceTypes.joinToString("/"))
        }
        return parts.joinToString(" · ")
    }

    /** 市场筛选维度：优先适配型号，其次平台与协议。 */
    fun filterTags(): List<String> = when {
        deviceTypes.isNotEmpty() -> deviceTypes
        platforms.isNotEmpty() -> platforms
        protocol.isNotBlank() -> listOf(protocol)
        else -> emptyList()
    }
}

/** 服务端返回的设备详情；enrich 字段均为 null 时表示未注册型号。 */
data class DeviceInfo(
    val mac: String,
    val vendor: String?,
    val deviceType: String?,
    val firmwareVersion: String?,
    val deviceTypeKey: String? = null,
    val alias: String = "",
    val advertisedName: String = "",
    val group: String = "",
    /** 本机扫描到的最近出现时间；0 表示本机从未扫描到。 */
    val lastSeenTimestamp: Long = 0L,
    /** 服务端最近一次接受读数上报的时间；null 表示从未上报（不拿 updatedAt 冒充）。 */
    val lastSeenAt: Long? = null,
    /** 设备所属平台；非 Android 设备本机无法直连，只能看云端状态。 */
    val platform: String? = null,
    val compatibility: Compatibility? = null,
    val fromLocalCache: Boolean = false,
)

/** 详情展示优先级：用户别名 > 广播名 > catalog 型号名。 */
fun DeviceInfo.displayName(): String? =
    alias.takeIf { it.isNotBlank() }
        ?: advertisedName.takeIf { it.isNotBlank() }
        ?: displayDeviceTypeName()

/**
 * 型号展示优先级：缓存/catalog 展示名 > 型号键（标注未识别）> 型号未知。
 * [deviceType] 只保存人类可读名称；[deviceTypeKey] 保存接口返回的型号键。
 */
fun DeviceInfo.displayDeviceTypeName(): String? =
    deviceType?.takeIf { it.isNotBlank() }

fun DeviceInfo.unrecognizedDeviceTypeKey(): String? =
    deviceTypeKey?.takeIf { it.isNotBlank() && deviceType.isNullOrBlank() }

/** 离线降级：只用 Room 已缓存字段构造详情，缺失字段保持 null，由 UI 统一兜底。 */
fun Device.toOfflineDeviceInfo(): DeviceInfo = DeviceInfo(
    mac = mac,
    vendor = vendor,
    deviceType = deviceTypeName,
    deviceTypeKey = deviceType,
    firmwareVersion = firmwareVersion,
    alias = name,
    advertisedName = advertisedName.orEmpty(),
    group = group.orEmpty(),
    lastSeenAt = lastSeenAt,
    platform = platform,
    compatibility = compatibility?.takeIf { it.isNotBlank() }?.let {
        Compatibility(protocol = it)
    },
    fromLocalCache = true,
)

data class GattServiceSpec(
    val uuid: String,
    val name: String,
    val characteristics: List<String>,
)

data class MarketItem(
    val id: String,
    val title: String,
    val category: String,
    val version: String,
    val compatibility: String,
    val description: String,
    val vendor: String = "",
    /** 用于筛选的真实维度（兼容型号/平台），不编造分类。 */
    val compatibilityTags: List<String> = emptyList(),
)

data class DeviceRule(
    val id: Long,
    val type: RuleType,
    /** RSSI 规则的整数阈值；指标规则请使用 [thresholdValue]。 */
    val threshold: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTriggeredTimestamp: Long,
    /** 指标规则的目标指标（[com.platojobs.pkotlinapp.domain.reading.ReadingMetrics] 归一化值）。 */
    val metric: String? = null,
    val comparator: RuleComparator = RuleComparator.GREATER,
    val thresholdValue: Double = 0.0,
    /** 连续满足的读数条数（1–5），用于防抖。 */
    val consecutive: Int = 1,
    /** 绑定的场景 id：非空表示该规则是场景触发条件，由场景执行器消费动作。 */
    val sceneId: Long? = null,
    /** 定时规则的执行间隔（分钟）。 */
    val intervalMinutes: Int? = null,
    /** 服务端条件的设备绑定；null 表示旧规则对所有设备生效。 */
    val deviceMac: String? = null,
    /** device_offline 的离线判定秒数。 */
    val offlineSeconds: Int = 300,
    /** schedule 的本地时刻（HH:MM）/ IANA 时区 / ISO 星期（1=周一）；interval 规则为空。 */
    val timeOfDay: String? = null,
    val timezone: String? = null,
    val daysOfWeek: String? = null,
) {
    val isMetricRule: Boolean get() = type == RuleType.METRIC_THRESHOLD
}

/** 一条真实命中的规则事件；指标规则额外携带触发值、阈值与单位。 */
data class RuleEvent(
    val id: Long,
    val ruleType: RuleType,
    val mac: String,
    val message: String,
    val timestamp: Long,
    val metric: String? = null,
    val value: Double? = null,
    val thresholdValue: Double? = null,
    val unit: String? = null,
)

data class ScanLog(
    val id: Long,
    val mac: String,
    val name: String,
    val rssi: Int,
    val timestamp: Long,
)

/** 首页今日概览：按指标分组的真实读数条数。 */
data class MetricCount(
    val metric: String,
    val count: Int,
)

/** 首页今日概览：按小时分组的真实读数条数（小时序号相对本地当天 0 点）。 */
data class HourCount(
    val hourIndex: Int,
    val count: Int,
)

fun ruleTypeOf(value: String): RuleType =
    runCatching { RuleType.valueOf(value) }.getOrDefault(RuleType.LOW_RSSI)
