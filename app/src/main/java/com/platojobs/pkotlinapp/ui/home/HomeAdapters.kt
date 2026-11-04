package com.platojobs.pkotlinapp.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemLiveDeviceBinding
import com.platojobs.pkotlinapp.databinding.ItemRecentDeviceBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.ui.common.TimeFormat

class LiveDeviceAdapter(
    private val onClick: (ScannedDevice) -> Unit,
) : ListAdapter<ScannedDevice, LiveDeviceAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLiveDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemLiveDeviceBinding,
        private val onClick: (ScannedDevice) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: ScannedDevice) {
            val context = binding.root.context
            binding.name.text = device.name ?: context.getString(R.string.device_unknown)
            binding.mac.text = device.mac
            binding.rssi.text = context.getString(R.string.device_rssi_value, device.rssi)
            binding.signal.setRssi(device.rssi)
            binding.root.setOnClickListener { onClick(device) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ScannedDevice>() {
            override fun areItemsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice) =
                oldItem.mac == newItem.mac

            override fun areContentsTheSame(oldItem: ScannedDevice, newItem: ScannedDevice) =
                oldItem == newItem
        }
    }
}

class RecentDeviceAdapter(
    private val onClick: (Device) -> Unit,
) : ListAdapter<Device, RecentDeviceAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecentDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemRecentDeviceBinding,
        private val onClick: (Device) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device) {
            val context = binding.root.context
            binding.name.text = device.name.ifBlank { context.getString(R.string.device_unknown) }
            binding.mac.text = device.mac
            binding.rssi.text = context.getString(R.string.device_rssi_value, device.lastRssi)
            binding.signal.setRssi(device.lastRssi)
            binding.lastSeen.text = context.getString(
                R.string.device_last_seen,
                TimeFormat.relative(context, device.lastSeenTimestamp),
            )
            binding.root.setOnClickListener { onClick(device) }
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
