package com.platojobs.pkotlinapp.ui.products

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemProductBinding
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.domain.product.SupportedMetric

class ProductAdapter(
    private val deviceCounts: () -> Map<String, Int> = { emptyMap() },
    private val onClick: (ProductModel) -> Unit,
) : ListAdapter<ProductModel, ProductAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, deviceCounts, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemProductBinding,
        private val deviceCounts: () -> Map<String, Int>,
        private val onClick: (ProductModel) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(product: ProductModel) {
            val context = binding.root.context
            binding.model.text = product.model.ifBlank { product.id }
            binding.vendor.text = product.vendor
            binding.meta.text = buildMeta(context.getString(product.category.labelRes()), product)
            binding.firmware.text = context.getString(R.string.product_firmware, product.firmwareVersion)
            binding.firmware.visibility =
                if (product.firmwareVersion.isBlank()) View.GONE else View.VISIBLE
            binding.note.text = product.note
            binding.note.visibility = if (product.note.isBlank()) View.GONE else View.VISIBLE

            val count = deviceCounts()[product.deviceTypeKey] ?: 0
            binding.myDevices.text = context.getString(R.string.product_my_devices, count)
            binding.myDevices.visibility = if (count > 0) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onClick(product) }
            binding.root.contentDescription = context.getString(
                R.string.product_open_description,
                binding.model.text,
            )
        }

        /** 分类 + App 真正能解析的指标；没有指标时只显示分类，不编造能力。 */
        private fun buildMeta(category: String, product: ProductModel): String {
            val metrics = product.supportedMetrics.joinToString(SEPARATOR) { metric ->
                binding.root.context.getString(metric.labelRes())
            }
            return if (metrics.isBlank()) category else "$category$SEPARATOR$metrics"
        }
    }

    private companion object {
        const val SEPARATOR = " · "

        val DIFF = object : DiffUtil.ItemCallback<ProductModel>() {
            override fun areItemsTheSame(oldItem: ProductModel, newItem: ProductModel) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ProductModel, newItem: ProductModel) =
                oldItem == newItem
        }
    }
}

internal fun ProductCategory.labelRes(): Int = when (this) {
    ProductCategory.HEALTH -> R.string.product_category_health
    ProductCategory.ENVIRONMENT -> R.string.product_category_environment
    ProductCategory.GATEWAY -> R.string.product_category_gateway
    ProductCategory.OTHER -> R.string.product_category_other
}

internal fun SupportedMetric.labelRes(): Int = when (this) {
    SupportedMetric.HEART_RATE -> R.string.product_metric_heart_rate
    SupportedMetric.BATTERY -> R.string.product_metric_battery
    SupportedMetric.TEMPERATURE -> R.string.product_metric_temperature
    SupportedMetric.NUS -> R.string.product_metric_nus
}