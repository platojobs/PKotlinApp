package com.platojobs.pkotlinapp.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemDeviceCardBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeActivityBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeAlertBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeOverviewBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeRoomBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeStatusBinding
import com.platojobs.pkotlinapp.databinding.ItemRoomEmptyBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeScenesBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeSectionBinding
import com.platojobs.pkotlinapp.databinding.ItemSceneCardBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceLinkStatus
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.CloudReadingState
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.reading.ReadingMetrics
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.ui.common.TimeFormat
import com.platojobs.pkotlinapp.ui.common.enableCardPressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface HomeItem {
    val span: Int get() = 2

    data class Alert(val count: Int) : HomeItem

    /** 今日概览：读数总量、按指标分布、越界次数。 */
    data class TodayOverview(val overview: HomeOverview) : HomeItem

    /** 设备状态：本机在线/本机离线/未分配/低电量，点击下钻。 */
    data class DeviceStatus(
        val overview: HomeOverview,
        val filter: HomeStatusFilter?,
    ) : HomeItem

    /** 最近活动：同步、告警、场景触发，均来自真实记录。 */
    data class RecentActivity(val overview: HomeOverview) : HomeItem

    data class DevicesHeader(val count: Int, val groupCount: Int) : HomeItem

    /** 房间分区标题；[roomId] 为 null 表示「未分配」。 */
    data class RoomHeader(
        val roomId: Long?,
        val name: String,
        val deviceCount: Int,
        val collapsed: Boolean,
        val empty: Boolean,
        /** 离线/尚未同步时标注「未同步」。 */
        val unsynced: Boolean = false,
    ) : HomeItem

    /** 完全没有房间时的真实空态。 */
    data object RoomEmpty : HomeItem

    data class DeviceCard(
        val device: Device,
        val reading: Reading?,
        val cloudReading: CloudReadingState?,
        /** 本机连接优先，其次服务端 presence 三态。 */
        val status: DeviceLinkStatus,
    ) : HomeItem {
        override val span: Int get() = 1
    }

    data class ScenesHeader(val count: Int) : HomeItem

    data class SceneCards(
        val scenes: List<Scene>,
        val progress: SceneProgress?,
    ) : HomeItem
}

/** 稳定的条目 ID：固定条目用类型值，带业务键的条目把键哈希放进低位。 */
internal fun HomeItem.stableId(): Long = when (this) {
    is HomeItem.Alert -> 1L
    is HomeItem.TodayOverview -> 2L
    is HomeItem.DeviceStatus -> 3L
    is HomeItem.RecentActivity -> 4L
    is HomeItem.DevicesHeader -> 5L
    is HomeItem.RoomHeader -> stableId(6, roomId ?: -1L)
    HomeItem.RoomEmpty -> 7L
    is HomeItem.DeviceCard -> stableId(8, device.mac)
    is HomeItem.ScenesHeader -> 9L
    is HomeItem.SceneCards -> 10L
}

private fun stableId(type: Int, key: Any): Long =
    (type.toLong() shl 48) or (key.hashCode().toLong() and 0x0000_FFFF_FFFF_FFFFL)

class HomeAdapter(
    private val unknownName: String,
    private val onDeviceClick: (Device) -> Unit,
    private val onAlertClick: () -> Unit,
    private val onRunScene: (Scene) -> Unit,
    private val onManageScenes: () -> Unit,
    private val onManageRooms: () -> Unit,
    private val onCreateRoom: () -> Unit,
    private val onRoomClick: (roomId: Long?, empty: Boolean) -> Unit,
    private val onRoomToggle: (roomId: Long?) -> Unit,
    private val onDeviceLongClick: (Device) -> Unit,
    private val onStatusClick: (HomeStatusFilter) -> Unit,
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(Diff) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HomeItem.Alert -> TYPE_ALERT
        is HomeItem.TodayOverview -> TYPE_TODAY
        is HomeItem.DeviceStatus -> TYPE_STATUS
        is HomeItem.RecentActivity -> TYPE_ACTIVITY
        is HomeItem.DevicesHeader -> TYPE_SECTION
        is HomeItem.RoomHeader -> TYPE_ROOM
        HomeItem.RoomEmpty -> TYPE_ROOM_EMPTY
        is HomeItem.DeviceCard -> TYPE_DEVICE
        is HomeItem.ScenesHeader -> TYPE_SECTION
        is HomeItem.SceneCards -> TYPE_SCENES
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ALERT -> AlertHolder(ItemHomeAlertBinding.inflate(inflater, parent, false))
            TYPE_TODAY -> TodayHolder(ItemHomeOverviewBinding.inflate(inflater, parent, false))
            TYPE_STATUS -> StatusHolder(ItemHomeStatusBinding.inflate(inflater, parent, false))
            TYPE_ACTIVITY -> ActivityHolder(ItemHomeActivityBinding.inflate(inflater, parent, false))
            TYPE_SECTION -> SectionHolder(ItemHomeSectionBinding.inflate(inflater, parent, false))
            TYPE_ROOM -> RoomHolder(ItemHomeRoomBinding.inflate(inflater, parent, false))
            TYPE_ROOM_EMPTY -> RoomEmptyHolder(ItemRoomEmptyBinding.inflate(inflater, parent, false))
            TYPE_DEVICE -> DeviceHolder(ItemDeviceCardBinding.inflate(inflater, parent, false))
            else -> ScenesHolder(ItemHomeScenesBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HomeItem.Alert -> (holder as AlertHolder).bind(item)
            is HomeItem.TodayOverview -> (holder as TodayHolder).bind(item)
            is HomeItem.DeviceStatus -> (holder as StatusHolder).bind(item)
            is HomeItem.RecentActivity -> (holder as ActivityHolder).bind(item)
            is HomeItem.DevicesHeader -> (holder as SectionHolder).bindDevices(item)
            is HomeItem.RoomHeader -> (holder as RoomHolder).bind(item)
            HomeItem.RoomEmpty -> (holder as RoomEmptyHolder).bind()
            is HomeItem.DeviceCard -> (holder as DeviceHolder).bind(item)
            is HomeItem.ScenesHeader -> (holder as SectionHolder).bindScenes(item)
            is HomeItem.SceneCards -> (holder as ScenesHolder).bind(item)
        }
    }

    private inner class TodayHolder(
        private val binding: ItemHomeOverviewBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem.TodayOverview) {
            val context = binding.root.context
            val overview = item.overview
            binding.todayReadingsValue.text = overview.todayReadings.toString()
            binding.todayViolations.visibility =
                if (overview.violationCount > 0) View.VISIBLE else View.GONE
            binding.todayViolations.text = context.getString(
                R.string.home_today_violations,
                overview.violationCount,
            )

            binding.todayMetrics.removeAllViews()
            overview.metricCounts.forEach { row ->
                val line = TextView(context).apply {
                    text = context.getString(
                        R.string.home_today_metric_row,
                        metricName(context, row.metric),
                        row.count,
                    )
                    setTextAppearance(R.style.TextAppearance_App_Body)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    contentDescription = text
                }
                binding.todayMetrics.addView(line)
            }
            binding.todayMetricsEmpty.visibility =
                if (overview.metricCounts.isEmpty()) View.VISIBLE else View.GONE

            val peak = overview.hourlyCounts.peakHour()
            binding.todayHourly.visibility = if (peak == null) View.GONE else View.VISIBLE
            if (peak != null) {
                binding.todayHourly.text = context.getString(
                    R.string.home_today_hourly_desc,
                    peak.hourIndex,
                    peak.count,
                )
            }
            binding.root.contentDescription = context.getString(
                R.string.home_today_metric_row,
                context.getString(R.string.home_today_readings),
                overview.todayReadings,
            )
        }
    }

    private inner class StatusHolder(
        private val binding: ItemHomeStatusBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem.DeviceStatus) {
            val context = binding.root.context
            val overview = item.overview
            bindCell(
                binding.statusOnline,
                binding.statusOnlineCount,
                context.getString(R.string.home_status_local_connected),
                overview.localConnectedCount,
            ) { onStatusClick(HomeStatusFilter.LOCAL_CONNECTED) }
            bindCell(
                binding.statusOffline,
                binding.statusOfflineCount,
                context.getString(R.string.home_status_cloud_recent),
                overview.cloudRecentCount,
            ) { onStatusClick(HomeStatusFilter.CLOUD_RECENT) }
            bindCell(
                binding.statusUnassigned,
                binding.statusUnassignedCount,
                context.getString(R.string.home_status_unassigned),
                overview.unassignedCount,
            ) { onStatusClick(HomeStatusFilter.UNASSIGNED) }
            binding.statusLowBatteryLabel.text = context.getString(
                R.string.home_status_low_battery,
                HomeViewModel.LOW_BATTERY_THRESHOLD.toInt(),
            )
            bindCell(
                binding.statusLowBattery,
                binding.statusLowBatteryCount,
                binding.statusLowBatteryLabel.text.toString(),
                overview.lowBatteryCount,
            ) { onStatusClick(HomeStatusFilter.LOW_BATTERY) }

            val filter = item.filter
            binding.statusFilterHint.visibility = View.VISIBLE
            if (filter == null) {
                // 口径说明：服务端 presence 只代表「最近 15 分钟有没有读数上报」。
                binding.statusFilterHint.text = context.getString(
                    R.string.home_status_presence_hint,
                    overview.cloudStaleCount,
                    overview.cloudUnknownCount,
                )
            } else {
                val label = when (filter) {
                    HomeStatusFilter.LOCAL_CONNECTED ->
                        context.getString(R.string.home_status_local_connected)

                    HomeStatusFilter.CLOUD_RECENT ->
                        context.getString(R.string.home_status_cloud_recent)

                    HomeStatusFilter.UNASSIGNED -> context.getString(R.string.home_status_unassigned)
                    HomeStatusFilter.LOW_BATTERY -> context.getString(
                        R.string.home_status_low_battery,
                        HomeViewModel.LOW_BATTERY_THRESHOLD.toInt(),
                    )
                }
                val count = when (filter) {
                    HomeStatusFilter.LOCAL_CONNECTED -> overview.localConnectedCount
                    HomeStatusFilter.CLOUD_RECENT -> overview.cloudRecentCount
                    HomeStatusFilter.UNASSIGNED -> overview.unassignedCount
                    HomeStatusFilter.LOW_BATTERY -> overview.lowBatteryCount
                }
                binding.statusFilterHint.text = context.getString(
                    R.string.home_status_filter_active,
                    label,
                    count,
                )
            }
            binding.root.contentDescription = context.getString(
                R.string.home_device_status_title,
            )
        }

        private fun bindCell(
            cell: View,
            countView: TextView,
            label: String,
            count: Int,
            onClick: () -> Unit,
        ) {
            countView.text = count.toString()
            cell.contentDescription = "$label $count"
            cell.setOnClickListener { onClick() }
        }
    }

    private inner class ActivityHolder(
        private val binding: ItemHomeActivityBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem.RecentActivity) {
            val context = binding.root.context
            val overview = item.overview
            val none = context.getString(R.string.home_activity_none)
            binding.activitySync.text = overview.latestSyncAt?.let { at ->
                context.getString(
                    R.string.home_activity_sync,
                    TimeFormat.relative(context, at),
                )
            } ?: context.getString(
                R.string.home_activity_sync,
                context.getString(R.string.home_activity_no_sync),
            )
            binding.activityAlert.text = overview.latestAlert?.let { alert ->
                context.getString(
                    R.string.home_activity_alert,
                    TimeFormat.relative(context, alert.timestamp),
                )
            } ?: context.getString(R.string.home_activity_alert, none)
            binding.activityScene.text = overview.latestSceneRun?.let { run ->
                context.getString(
                    R.string.home_activity_scene,
                    context.getString(
                        R.string.list_meta_line,
                        run.sceneName,
                        TimeFormat.relative(context, run.timestamp),
                    ),
                )
            } ?: context.getString(R.string.home_activity_scene, none)
        }
    }

    private inner class AlertHolder(
        private val binding: ItemHomeAlertBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeItem.Alert) {
            val context = binding.root.context
            if (item.count > 0) {
                binding.alertCount.text = context.getString(
                    R.string.home_alert_count_value,
                    item.count,
                )
                binding.alertCount.setTextColor(ContextCompat.getColor(context, R.color.danger))
            } else {
                binding.alertCount.setText(R.string.home_alert_none)
                binding.alertCount.setTextColor(
                    ContextCompat.getColor(context, R.color.text_secondary),
                )
            }
            binding.root.enableCardPressScale()
            binding.root.setOnClickListener { onAlertClick() }
        }
    }

    private inner class SectionHolder(
        private val binding: ItemHomeSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bindDevices(item: HomeItem.DevicesHeader) {
            val context = binding.root.context
            binding.sectionTitle.setText(R.string.home_devices_title)
            binding.sectionSubtitle.text = context.getString(
                R.string.home_devices_count,
                item.count,
                item.groupCount,
            )
            binding.sectionAction.visibility = View.VISIBLE
            binding.sectionAction.setText(R.string.room_manage)
            binding.sectionAction.setOnClickListener { onManageRooms() }
        }

        fun bindScenes(item: HomeItem.ScenesHeader) {
            binding.sectionTitle.setText(R.string.home_scenes_title)
            binding.sectionSubtitle.setText(R.string.home_scenes_local)
            binding.sectionAction.visibility = View.VISIBLE
            binding.sectionAction.setText(R.string.home_scenes_manage)
            binding.sectionAction.setOnClickListener { onManageScenes() }
        }
    }

    private inner class RoomHolder(
        private val binding: ItemHomeRoomBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeItem.RoomHeader) {
            val context = binding.root.context
            binding.roomTitle.text = item.name
            binding.roomArrow.visibility = if (item.empty) View.INVISIBLE else View.VISIBLE
            binding.roomArrow.text = if (item.collapsed) "▸" else "▾"
            val baseMeta = if (item.empty) {
                context.getString(R.string.room_no_devices)
            } else {
                context.getString(R.string.room_device_count, item.deviceCount)
            }
            binding.roomMeta.text = if (item.unsynced) {
                context.getString(R.string.room_unsynced_meta, baseMeta)
            } else {
                baseMeta
            }
            // 标题点击进入房间详情，箭头单独负责展开/折叠。
            binding.root.contentDescription = if (item.roomId != null && !item.empty) {
                context.getString(R.string.room_a11y_open_detail, item.name, binding.roomMeta.text)
            } else {
                context.getString(R.string.room_a11y_header, item.name, binding.roomMeta.text)
            }
            binding.root.setOnClickListener { onRoomClick(item.roomId, item.empty) }
            binding.root.setOnLongClickListener(null)
            binding.roomArrow.contentDescription = context.getString(
                if (item.collapsed) R.string.room_a11y_expand else R.string.room_a11y_collapse,
                item.name,
            )
            binding.roomArrow.setOnClickListener { onRoomToggle(item.roomId) }
        }
    }

    private inner class RoomEmptyHolder(
        private val binding: ItemRoomEmptyBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.roomEmptyTitle.setText(R.string.room_empty_title)
            binding.roomEmptyHint.setText(R.string.room_empty_hint)
            binding.roomEmptyAction.setText(R.string.room_create)
            binding.roomEmptyAction.setOnClickListener { onCreateRoom() }
            if (binding.roomEmptyAction.contentDescription.isNullOrEmpty()) {
                binding.roomEmptyAction.contentDescription =
                    binding.root.context.getString(R.string.room_create)
            }
            binding.roomEmptyAction.setOnLongClickListener(null)
        }
    }

    private inner class DeviceHolder(
        private val binding: ItemDeviceCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeItem.DeviceCard) {
            val context = binding.root.context
            val device = item.device
            binding.deviceName.text = device.displayName() ?: unknownName
            binding.markedBadge.visibility =
                if (device.markedAt != null) View.VISIBLE else View.GONE
            // 状态行与房间详情保持同一口径：本机连接 > 服务端 15 分钟内有上报 >
            // 有上报但已超时 > 从未上报。不出现不加限定的「在线」。
            val connected = item.status == DeviceLinkStatus.LOCAL_CONNECTED
            binding.connectionStatus.text = when (item.status) {
                DeviceLinkStatus.LOCAL_CONNECTED ->
                    context.getString(R.string.home_device_connected)

                DeviceLinkStatus.CLOUD_RECENT ->
                    context.getString(R.string.home_device_cloud_recent)

                DeviceLinkStatus.CLOUD_STALE -> context.getString(
                    R.string.home_device_cloud_stale,
                    device.lastSeenAt?.let { TimeFormat.relative(context, it) }
                        ?: context.getString(R.string.home_device_cloud_never),
                )

                DeviceLinkStatus.CLOUD_UNKNOWN ->
                    context.getString(R.string.home_device_cloud_unknown)
            }
            binding.connectionStatus.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (connected) R.color.success else R.color.text_tertiary,
                ),
            )
            binding.signal.setRssi(device.lastRssi)

            fun renderReading(text: CharSequence, primary: Boolean, label: CharSequence?) {
                binding.readingValue.text = text
                binding.readingValue.setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (primary) R.color.text_primary else R.color.text_tertiary,
                    ),
                )
                binding.readingLabel.visibility = if (label != null) View.VISIBLE else View.GONE
                binding.readingLabel.text = label
            }

            // 本机读数优先；没有本机读数时才展示云端四态，失败明确说「暂不可用」。
            val reading = item.reading
            val cloud = item.cloudReading
            when {
                reading != null -> renderReading(
                    formatReading(reading), true,
                    context.getString(
                        R.string.home_device_last_reading_at,
                        context.getString(R.string.home_device_last_reading),
                        formatTime(reading.recordedAt),
                    ),
                )

                cloud is CloudReadingState.Available -> renderReading(
                    formatReading(cloud.reading.value, cloud.reading.unit), true,
                    context.getString(
                        R.string.home_device_last_reading_at,
                        context.getString(R.string.home_device_cloud_reading),
                        formatTime(cloud.reading.recordedAt),
                    ),
                )

                cloud is CloudReadingState.Loading -> renderReading(
                    context.getString(R.string.home_device_cloud_loading), false, null,
                )

                cloud is CloudReadingState.Failed -> renderReading(
                    context.getString(R.string.home_device_cloud_unavailable), false, null,
                )

                cloud is CloudReadingState.Empty -> renderReading(
                    context.getString(R.string.home_device_no_reading), false,
                    context.getString(R.string.home_device_cloud_empty),
                )

                else -> renderReading(
                    context.getString(R.string.home_device_no_reading), false, null,
                )
            }
            binding.root.contentDescription = context.getString(
                R.string.home_device_open_named,
                context.getString(R.string.home_device_open),
                device.displayName() ?: unknownName,
            )
            binding.root.enableCardPressScale()
            binding.root.setOnClickListener { onDeviceClick(device) }
            binding.root.setOnLongClickListener {
                onDeviceLongClick(device)
                true
            }
        }
    }

    private inner class ScenesHolder(
        private val binding: ItemHomeScenesBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem.SceneCards) {
            val context = binding.root.context
            binding.sceneContainer.removeAllViews()
            val scenes = item.scenes.take(MAX_QUICK_SCENES)
            binding.sceneEmpty.visibility = if (scenes.isEmpty()) View.VISIBLE else View.GONE
            binding.sceneScroll.visibility = if (scenes.isEmpty()) View.GONE else View.VISIBLE
            scenes.forEach { scene ->
                val card = ItemSceneCardBinding.inflate(
                    LayoutInflater.from(context),
                    binding.sceneContainer,
                    false,
                )
                card.sceneName.text = scene.name
                val progress = item.progress?.takeIf { it.sceneId == scene.id }
                if (progress != null) {
                    card.sceneMeta.text = context.getString(
                        R.string.intelligence_scene_progress,
                        sceneActionLabel(context, progress.action),
                        progress.index + 1,
                        progress.total,
                    )
                    card.sceneRun.isEnabled = false
                } else {
                    card.sceneMeta.text = context.getString(
                        R.string.home_scene_meta_local,
                        context.getString(R.string.home_scenes_local),
                        context.resources.getQuantityString(
                            R.plurals.home_scene_actions_count,
                            scene.actions.size,
                            scene.actions.size,
                        ),
                    )
                    card.sceneRun.isEnabled = true
                }
                card.root.enableCardPressScale()
                card.root.setOnClickListener { onRunScene(scene) }
                card.sceneRun.setOnClickListener { onRunScene(scene) }
                binding.sceneContainer.addView(card.root)
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<HomeItem>() {
        override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean = when {
            oldItem is HomeItem.DeviceCard && newItem is HomeItem.DeviceCard ->
                oldItem.device.mac == newItem.device.mac

            oldItem is HomeItem.Alert && newItem is HomeItem.Alert -> true
            oldItem is HomeItem.TodayOverview && newItem is HomeItem.TodayOverview -> true
            oldItem is HomeItem.DeviceStatus && newItem is HomeItem.DeviceStatus -> true
            oldItem is HomeItem.RecentActivity && newItem is HomeItem.RecentActivity -> true
            oldItem is HomeItem.DevicesHeader && newItem is HomeItem.DevicesHeader -> true

            oldItem is HomeItem.RoomHeader && newItem is HomeItem.RoomHeader ->
                oldItem == newItem

            oldItem is HomeItem.RoomEmpty && newItem is HomeItem.RoomEmpty -> true

            oldItem is HomeItem.ScenesHeader && newItem is HomeItem.ScenesHeader -> true
            oldItem is HomeItem.SceneCards && newItem is HomeItem.SceneCards -> true
            else -> false
        }

        override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val TYPE_ALERT = 0
        const val TYPE_SECTION = 1
        const val TYPE_DEVICE = 3
        const val TYPE_SCENES = 4
        const val TYPE_ROOM = 5
        const val TYPE_ROOM_EMPTY = 6
        const val TYPE_TODAY = 7
        const val TYPE_STATUS = 8
        const val TYPE_ACTIVITY = 9
        const val MAX_QUICK_SCENES = 3
    }
}

private fun metricName(context: Context, metric: String): String = context.getString(
    when (ReadingMetrics.displayAlias(metric)) {
        ReadingMetrics.TEMPERATURE -> R.string.readings_metric_temperature
        ReadingMetrics.HUMIDITY -> R.string.readings_metric_humidity
        ReadingMetrics.BATTERY -> R.string.readings_metric_battery
        ReadingMetrics.HEART_RATE -> R.string.readings_metric_heart_rate
        ReadingMetrics.CO2 -> R.string.readings_metric_co2
        ReadingMetrics.PRESSURE -> R.string.readings_metric_pressure
        else -> R.string.readings_metric_other
    },
)

internal fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

internal fun formatReading(reading: Reading): String =
    formatReading(reading.value, reading.unit)

internal fun formatReading(value: Double, unit: String): String {
    val number = if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", value)
    }
    val displayUnit = when (unit.trim().lowercase()) {
        "celsius", "°c" -> "℃"
        "fahrenheit", "°f" -> "℉"
        "percent" -> "%"
        else -> unit.trim()
    }
    return if (displayUnit.isBlank()) number else "$number $displayUnit"
}

fun sceneActionLabel(context: Context, action: SceneAction): String = when (action.type) {
    SceneActionType.CONNECT_DEVICE -> context.getString(R.string.scene_action_connect_device)
    SceneActionType.DISCONNECT_DEVICE -> context.getString(R.string.scene_action_disconnect_device)
    SceneActionType.START_COLLECTION -> context.getString(R.string.scene_action_start_collection)
    SceneActionType.STOP_COLLECTION -> context.getString(R.string.scene_action_stop_collection)
    SceneActionType.MUTE_ALERTS -> context.getString(R.string.scene_action_mute_alerts)
    SceneActionType.SYNC_NOW -> context.getString(R.string.scene_action_sync_now)
    SceneActionType.NOTIFY -> context.getString(R.string.scene_action_notify)
    SceneActionType.RECORD_LOG -> context.getString(R.string.scene_action_record_log)
    SceneActionType.MARK_DEVICE -> context.getString(R.string.scene_action_mark_device)
}
