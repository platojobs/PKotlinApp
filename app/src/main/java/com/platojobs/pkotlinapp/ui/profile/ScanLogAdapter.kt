package com.platojobs.pkotlinapp.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemScanLogBinding
import com.platojobs.pkotlinapp.domain.model.ScanLog
import com.platojobs.pkotlinapp.ui.common.TimeFormat

class ScanLogAdapter : ListAdapter<ScanLog, ScanLogAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScanLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemScanLogBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(log: ScanLog) {
            val context = binding.root.context
            binding.logName.text = log.name.ifBlank { context.getString(R.string.device_unknown) }
            binding.logMac.text = log.mac
            binding.logRssi.text = context.getString(R.string.device_rssi_value, log.rssi)
            binding.logTime.text = TimeFormat.relative(context, log.timestamp)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ScanLog>() {
            override fun areItemsTheSame(oldItem: ScanLog, newItem: ScanLog) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ScanLog, newItem: ScanLog) =
                oldItem == newItem
        }
    }
}
