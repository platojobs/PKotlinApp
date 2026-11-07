package com.platojobs.pkotlinapp.ui.home

import com.platojobs.pkotlinapp.domain.model.cloudReadingKey
import com.platojobs.pkotlinapp.domain.model.deviceLinkStatus

/**
 * 首页列表构建（纯 Kotlin，便于 JVM 压测）：
 * 输入聚合状态与场景进度，输出 RecyclerView 直接消费的扁平条目。
 */
object HomeListBuilder {

    fun build(
        state: HomeUiState,
        sceneProgress: SceneProgress?,
        unassignedRoomLabel: String,
    ): List<HomeItem> {
        val items = ArrayList<HomeItem>(
            state.devices.size + state.sections.size * 2 + EXTRA_ITEMS,
        )
        items += HomeItem.TodayOverview(state.overview)
        items += HomeItem.DeviceStatus(
            overview = state.overview,
            filter = state.statusFilter,
        )
        items += HomeItem.RecentActivity(state.overview)
        if (state.devices.isNotEmpty() || state.rooms.isNotEmpty()) {
            items += HomeItem.DevicesHeader(state.devices.size, state.groups.size)
            if (!state.searching && state.rooms.isEmpty() && state.statusFilter == null) {
                items += HomeItem.RoomEmpty
            }
            state.sections.forEach { section ->
                // 搜索结果是平铺的设备清单，折叠状态不影响搜索可见性。
                val collapsed = !state.searching && (
                    section.room?.id?.let { it in state.collapsedRooms }
                        ?: (HomeViewModel.UNASSIGNED_ROOM_ID in state.collapsedRooms)
                    )
                if (!state.searching && state.statusFilter == null) {
                    items += HomeItem.RoomHeader(
                        roomId = section.room?.id,
                        name = section.room?.name ?: unassignedRoomLabel,
                        deviceCount = section.devices.size,
                        collapsed = collapsed,
                        empty = section.room != null && section.devices.isEmpty(),
                        unsynced = !state.householdSynced || section.room?.synced == false,
                    )
                }
                if (!collapsed) {
                    section.devices.forEach { device ->
                        val localReading = state.latestReadings[device.mac]
                        items += HomeItem.DeviceCard(
                            device = device,
                            reading = localReading,
                            // 本机读数优先：有本机读数时不再暴露云端状态。
                            cloudReading = if (localReading == null) {
                                state.cloudReadings[device.cloudReadingKey]
                            } else {
                                null
                            },
                            status = deviceLinkStatus(device, state.connectedMac, state.now),
                        )
                    }
                }
            }
        }
        items += HomeItem.ScenesHeader(state.scenes.size)
        items += HomeItem.SceneCards(state.scenes, sceneProgress)
        return items
    }

    private const val EXTRA_ITEMS = 5
}
