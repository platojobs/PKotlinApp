package com.platojobs.pkotlinapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemLiveDeviceBinding
import com.platojobs.pkotlinapp.domain.model.ScannedDevice
import com.platojobs.pkotlinapp.ui.common.RssiLevel

/** 扫描弹层里的实时设备列表。 */
class LiveDeviceAdapter(
    private val onClick: (ScannedDevice) -> Unit,
) : ListAdapter<ScannedDevice, LiveDeviceAdapter.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).mac.hashCode().toLong()

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
            // 与设备卡共用同一判定：0 / 127 等非负值不是真实读数，不显示 dBm 数值。
            val validRssi = device.rssi.takeIf { RssiLevel.isValid(it) }
            binding.rssi.text = validRssi?.let {
                context.getString(R.string.device_rssi_value, it)
            }.orEmpty()
            binding.rssi.visibility = if (validRssi == null) View.GONE else View.VISIBLE
            binding.signal.setRssi(validRssi)
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
