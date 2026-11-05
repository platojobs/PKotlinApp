package com.platojobs.pkotlinapp.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemDeviceCardBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeAlertBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeRoomBinding
import com.platojobs.pkotlinapp.databinding.ItemRoomEmptyBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeScenesBinding
import com.platojobs.pkotlinapp.databinding.ItemHomeSectionBinding
import com.platojobs.pkotlinapp.databinding.ItemSceneCardBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.displayName
import com.platojobs.pkotlinapp.domain.reading.Reading
import com.platojobs.pkotlinapp.domain.scene.Scene
import com.platojobs.pkotlinapp.domain.scene.SceneAction
import com.platojobs.pkotlinapp.domain.scene.SceneActionType
import com.platojobs.pkotlinapp.ui.common.enableCardPressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface HomeItem {
    val span: Int get() = 2

    data class Alert(val count: Int) : HomeItem

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
        val connected: Boolean,
    ) : HomeItem {
        override val span: Int get() = 1
    }

    data class ScenesHeader(val count: Int) : HomeItem

    data class SceneCards(
        val scenes: List<Scene>,
        val progress: SceneProgress?,
    ) : HomeItem
}

class HomeAdapter(
    private val unknownName: String,
    private val onDeviceClick: (Device) -> Unit,
    private val onAlertClick: () -> Unit,
    private val onRunScene: (Scene) -> Unit,
    private val onManageScenes: () -> Unit,
    private val onManageRooms: () -> Unit,
    private val onCreateRoom: () -> Unit,
    private val onRoomClick: (roomId: Long?, empty: Boolean) -> Unit,
    private val onDeviceLongClick: (Device) -> Unit,
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HomeItem.Alert -> TYPE_ALERT
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
            is HomeItem.DevicesHeader -> (holder as SectionHolder).bindDevices(item)
            is HomeItem.RoomHeader -> (holder as RoomHolder).bind(item)
            HomeItem.RoomEmpty -> (holder as RoomEmptyHolder).bind()
            is HomeItem.DeviceCard -> (holder as DeviceHolder).bind(item)
            is HomeItem.ScenesHeader -> (holder as SectionHolder).bindScenes(item)
            is HomeItem.SceneCards -> (holder as ScenesHolder).bind(item)
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
            binding.root.contentDescription = context.getString(
                R.string.room_a11y_header,
                item.name,
                binding.roomMeta.text,
            )
            binding.root.setOnClickListener { onRoomClick(item.roomId, item.empty) }
            binding.root.setOnLongClickListener(null)
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
            binding.connectionStatus.setText(
                if (item.connected) R.string.home_device_connected
                else R.string.home_device_disconnected,
            )
            binding.connectionStatus.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (item.connected) R.color.success else R.color.text_tertiary,
                ),
            )
            binding.signal.setRssi(device.lastRssi)

            val reading = item.reading
            if (reading == null) {
                binding.readingValue.setText(R.string.home_device_no_reading)
                binding.readingValue.setTextColor(
                    ContextCompat.getColor(context, R.color.text_tertiary),
                )
                binding.readingLabel.visibility = View.GONE
            } else {
                binding.readingValue.text = formatReading(reading)
                binding.readingValue.setTextColor(
                    ContextCompat.getColor(context, R.color.text_primary),
                )
                binding.readingLabel.visibility = View.VISIBLE
                binding.readingLabel.text = context.getString(
                    R.string.home_device_last_reading_at,
                    context.getString(R.string.home_device_last_reading),
                    formatTime(reading.recordedAt),
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
        const val MAX_QUICK_SCENES = 3
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatReading(reading: Reading): String {
    val number = if (reading.value % 1.0 == 0.0) {
        reading.value.toLong().toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", reading.value)
    }
    return "$number ${reading.unit}"
}

fun sceneActionLabel(context: Context, action: SceneAction): String = when (action.type) {
    SceneActionType.CONNECT_DEVICE -> context.getString(R.string.scene_action_connect_device)
    SceneActionType.DISCONNECT_DEVICE -> context.getString(R.string.scene_action_disconnect_device)
    SceneActionType.START_COLLECTION -> context.getString(R.string.scene_action_start_collection)
    SceneActionType.STOP_COLLECTION -> context.getString(R.string.scene_action_stop_collection)
    SceneActionType.MUTE_ALERTS -> context.getString(R.string.scene_action_mute_alerts)
    SceneActionType.SYNC_NOW -> context.getString(R.string.scene_action_sync_now)
}
