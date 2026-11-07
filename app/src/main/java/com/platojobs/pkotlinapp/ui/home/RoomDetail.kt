package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceLinkStatus
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.model.SyncState
import com.platojobs.pkotlinapp.domain.model.cloudReadingKey
import com.platojobs.pkotlinapp.domain.model.deviceLinkStatus
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.CloudReadingState
import com.platojobs.pkotlinapp.domain.reading.Reading

/** 房间详情里的单台设备：本机读数优先，缺失时才展示云端四态。 */
data class RoomDeviceEntry(
    val mac: String,
    val name: String,
    /** 本机连接优先，其次服务端 presence 三态（见 [DeviceLinkStatus]）。 */
    val status: DeviceLinkStatus,
    /** 服务端最近一次接受读数上报的时间；null 表示从未上报。 */
    val lastSeenAt: Long?,
    val marked: Boolean,
    /** 标记尚在本机等待上传（离线/未登录）；用于界面说明当前状态。 */
    val pending: Boolean,
    val reading: Reading?,
    /** 本机无读数时的云端状态；本机有读数时为 null。 */
    val cloudReading: CloudReadingState?,
)

/**
 * 房间聚合视图：数量全部由传入的设备列表计算，不额外伪造任何统计。
 * 四个状态互斥，加起来等于 [total]。
 */
data class RoomDetail(
    val roomId: Long,
    val roomName: String,
    val total: Int,
    val localConnectedCount: Int,
    val cloudRecentCount: Int,
    val cloudStaleCount: Int,
    val cloudUnknownCount: Int,
    val markedCount: Int,
    val entries: List<RoomDeviceEntry>,
) {
    val isEmpty: Boolean get() = total == 0

    /** 批量操作的默认目标：当前房间内所有设备。 */
    val allMacs: List<String> get() = entries.map { it.mac }

    /** 已标记但尚未被服务端确认的设备数。 */
    val pendingMarkCount: Int get() = entries.count { it.marked && it.pending }
}

object RoomDetailBuilder {

    /**
     * @param devices 已经按当前家庭过滤后的设备集合；这里再按 [room] 归属过滤一次，避免调用方传错。
     * @param readings 每台设备的最新一条本机读数（`mac -> Reading`），优先展示。
     * @param cloudReadings 本机无读数时按 [Device.cloudReadingKey] 查云端四态。
     * @param connectedMac 本机蓝牙当前连接的设备；只有它能算「本机已连接」。
     * @param now 推导 presence 的本机时钟；只有服务端返回 lastSeenAt 缺失时才完全依赖它。
     */
    fun build(
        room: Room,
        devices: List<Device>,
        readings: Map<String, Reading>,
        cloudReadings: Map<String, CloudReadingState>,
        connectedMac: String?,
        unknownName: String,
        now: Long = System.currentTimeMillis(),
    ): RoomDetail {
        val entries = devices
            .filter { it.roomId == room.id }
            .map { device ->
                val localReading = readings[device.mac]
                RoomDeviceEntry(
                    mac = device.mac,
                    name = device.displayName()?.takeIf(String::isNotBlank) ?: unknownName,
                    status = deviceLinkStatus(device, connectedMac, now),
                    lastSeenAt = device.lastSeenAt,
                    marked = device.markedAt != null,
                    pending = device.syncState == SyncState.PENDING,
                    reading = localReading,
                    // 本机读数优先：有本机读数时不再展示云端状态。
                    cloudReading = if (localReading == null) {
                        cloudReadings[device.cloudReadingKey]
                    } else {
                        null
                    },
                )
            }
        return RoomDetail(
            roomId = room.id,
            roomName = room.name,
            total = entries.size,
            localConnectedCount = entries.count { it.status == DeviceLinkStatus.LOCAL_CONNECTED },
            cloudRecentCount = entries.count { it.status == DeviceLinkStatus.CLOUD_RECENT },
            cloudStaleCount = entries.count { it.status == DeviceLinkStatus.CLOUD_STALE },
            cloudUnknownCount = entries.count { it.status == DeviceLinkStatus.CLOUD_UNKNOWN },
            markedCount = entries.count { it.marked },
            entries = entries,
        )
    }
}
