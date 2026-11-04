package com.platojobs.pkotlinapp.ui.market

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemMarketBinding
import com.platojobs.pkotlinapp.domain.model.MarketItem

class MarketAdapter : ListAdapter<MarketItem, MarketAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMarketBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemMarketBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MarketItem) {
            val context = binding.root.context
            binding.category.text = item.category
            binding.title.text = item.title
            binding.vendor.text = context.getString(R.string.market_vendor, item.vendor)
            binding.vendor.visibility =
                if (item.vendor.isBlank()) View.GONE else View.VISIBLE
            binding.version.text = context.getString(R.string.market_version, item.version)
            // fixture 中部分配件没有 SKU，不展示空的「SKU：」行。
            binding.version.visibility = if (item.version.isBlank()) View.GONE else View.VISIBLE
            binding.compatibility.text =
                context.getString(R.string.market_compatibility, item.compatibility)
            binding.description.text = item.description
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MarketItem>() {
            override fun areItemsTheSame(oldItem: MarketItem, newItem: MarketItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MarketItem, newItem: MarketItem) =
                oldItem == newItem
        }
    }
}
