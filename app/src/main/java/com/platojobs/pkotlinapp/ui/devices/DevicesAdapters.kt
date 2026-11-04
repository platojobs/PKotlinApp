package com.platojobs.pkotlinapp.ui.devices

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.data.ble.BleConnectionState
import com.platojobs.pkotlinapp.databinding.ItemRuleBinding
import com.platojobs.pkotlinapp.databinding.ItemRuleEventBinding
import com.platojobs.pkotlinapp.databinding.ItemSavedDeviceBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.DeviceRule
import com.platojobs.pkotlinapp.domain.model.RuleEvent
import com.platojobs.pkotlinapp.domain.model.RuleType
import com.platojobs.pkotlinapp.ui.common.TimeFormat

class SavedDeviceAdapter(
    private val onClick: (Device) -> Unit,
    private val onDelete: (Device) -> Unit,
) : ListAdapter<Device, SavedDeviceAdapter.ViewHolder>(DIFF) {

    var connectionState: BleConnectionState = BleConnectionState.Idle
        set(value) {
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    var trends: Map<String, Int?> = emptyMap()
        set(value) {
            field = value
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding, onClick, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), connectionState, trends[getItem(position).mac])
    }

    class ViewHolder(
        private val binding: ItemSavedDeviceBinding,
        private val onClick: (Device) -> Unit,
        private val onDelete: (Device) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device, connectionState: BleConnectionState, trend: Int?) {
            val context = binding.root.context
            binding.name.text = device.name.ifBlank { context.getString(R.string.device_unknown) }
            binding.mac.text = device.mac
            binding.rssi.text = context.getString(R.string.device_rssi_value, device.lastRssi)
            binding.signal.setRssi(device.lastRssi)
            binding.lastSeen.text = context.getString(
                R.string.device_last_seen,
                TimeFormat.relative(context, device.lastSeenTimestamp),
            )

            val connected = (connectionState as? BleConnectionState.Connected)?.mac == device.mac
            val connecting = (connectionState as? BleConnectionState.Connecting)?.mac == device.mac
            binding.status.text = when {
                connected -> context.getString(R.string.devices_connected)
                connecting -> context.getString(R.string.devices_connecting)
                else -> context.getString(R.string.devices_disconnected)
            }
            binding.status.setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        connected -> R.color.signal_active
                        connecting -> R.color.brand_primary
                        else -> R.color.text_secondary
                    },
                ),
            )

            val trendText = when {
                trend == null -> context.getString(R.string.devices_trend_unknown)
                trend > 0 -> context.getString(R.string.devices_trend_up, trend)
                trend < 0 -> context.getString(R.string.devices_trend_down, -trend)
                else -> context.getString(R.string.devices_trend_flat)
            }
            binding.trend.text = context.getString(R.string.devices_trend, trendText)

            binding.root.setOnClickListener { onClick(device) }
            binding.deleteButton.setOnClickListener { onDelete(device) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Device>() {
            override fun areItemsTheSame(oldItem: Device, newItem: Device) =
                oldItem.mac == newItem.mac

            override fun areContentsTheSame(oldItem: Device, newItem: Device) =
                oldItem == newItem
        }
    }
}

class RuleAdapter(
    private val onToggle: (DeviceRule, Boolean) -> Unit,
    private val onDelete: (DeviceRule) -> Unit,
) : ListAdapter<DeviceRule, RuleAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onToggle, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemRuleBinding,
        private val onToggle: (DeviceRule, Boolean) -> Unit,
        private val onDelete: (DeviceRule) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: DeviceRule) {
            val context = binding.root.context
            binding.ruleTitle.text = context.getString(
                when (rule.type) {
                    RuleType.LOW_RSSI -> R.string.rule_low_rssi
                    RuleType.DISCONNECTED -> R.string.rule_disconnected
                    RuleType.BOND_LOST -> R.string.rule_bond_lost
                },
            )
            val thresholdText = if (rule.type == RuleType.LOW_RSSI) {
                context.getString(R.string.rule_threshold_value, rule.threshold)
            } else {
                context.getString(R.string.rule_threshold_not_applicable)
            }
            val enabledText = context.getString(
                if (rule.enabled) R.string.rule_enabled_desc else R.string.rule_disabled_desc,
            )
            binding.ruleDetail.text = "$thresholdText · $enabledText"

            binding.ruleSwitch.setOnCheckedChangeListener(null)
            binding.ruleSwitch.isChecked = rule.enabled
            binding.ruleSwitch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(rule, isChecked)
            }
            binding.deleteRuleButton.setOnClickListener { onDelete(rule) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<DeviceRule>() {
            override fun areItemsTheSame(oldItem: DeviceRule, newItem: DeviceRule) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DeviceRule, newItem: DeviceRule) =
                oldItem == newItem
        }
    }
}

class RuleEventAdapter : ListAdapter<RuleEvent, RuleEventAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRuleEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemRuleEventBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(event: RuleEvent) {
            val context = binding.root.context
            binding.eventMessage.text = event.message
            binding.eventMeta.text = "${event.mac} · ${TimeFormat.relative(context, event.timestamp)}"
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RuleEvent>() {
            override fun areItemsTheSame(oldItem: RuleEvent, newItem: RuleEvent) =
                oldItem.id == newItem.id && oldItem.timestamp == newItem.timestamp

            override fun areContentsTheSame(oldItem: RuleEvent, newItem: RuleEvent) =
                oldItem == newItem
        }
    }
}
