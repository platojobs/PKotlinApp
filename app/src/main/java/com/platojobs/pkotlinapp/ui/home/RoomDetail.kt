package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.Room
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.Reading

/** 房间详情里的单台设备：最新读数与在线/标记状态都来自本机真实缓存。 */
data class RoomDeviceEntry(
    val mac: String,
    val name: String,
    val online: Boolean,
    val marked: Boolean,
    val reading: Reading?,
)

/**
 * 房间聚合视图：数量全部由传入的设备列表计算，不额外伪造任何统计。
 */
data class RoomDetail(
    val roomId: Long,
    val roomName: String,
    val total: Int,
    val onlineCount: Int,
    val offlineCount: Int,
    val markedCount: Int,
    val entries: List<RoomDeviceEntry>,
) {
    val isEmpty: Boolean get() = total == 0

    /** 批量操作的默认目标：当前房间内所有设备。 */
    val allMacs: List<String> get() = entries.map { it.mac }
}

object RoomDetailBuilder {

    /**
     * @param devices 已经按当前家庭过滤后的设备集合；这里再按 [room] 归属过滤一次，避免调用方传错。
     * @param readings 每台设备的最新一条读数（`mac -> Reading`）。
     * @param onlineSince 与首页在线统计同一阈值（最近出现时间 >= 该值视为在线）。
     */
    fun build(
        room: Room,
        devices: List<Device>,
        readings: Map<String, Reading>,
        onlineSince: Long,
        unknownName: String,
    ): RoomDetail {
        val entries = devices
            .filter { it.roomId == room.id }
            .map { device ->
                RoomDeviceEntry(
                    mac = device.mac,
                    name = device.displayName()?.takeIf(String::isNotBlank) ?: unknownName,
                    online = device.lastSeenTimestamp >= onlineSince,
                    marked = device.markedAt != null,
                    reading = readings[device.mac],
                )
            }
        val online = entries.count { it.online }
        return RoomDetail(
            roomId = room.id,
            roomName = room.name,
            total = entries.size,
            onlineCount = online,
            offlineCount = entries.size - online,
            markedCount = entries.count { it.marked },
            entries = entries,
        )
    }
}
