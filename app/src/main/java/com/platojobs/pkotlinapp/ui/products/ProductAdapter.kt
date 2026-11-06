package com.platojobs.pkotlinapp.ui.products

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemProductBinding
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalogItem
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.domain.product.productCategoryOf

class ProductAdapter(
    private val deviceCounts: () -> Map<String, Int> = { emptyMap() },
    private val onClick: (DeviceTypeCatalogItem) -> Unit,
) : ListAdapter<DeviceTypeCatalogItem, ProductAdapter.ViewHolder>(DIFF) {

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
        private val onClick: (DeviceTypeCatalogItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(product: DeviceTypeCatalogItem) {
            val context = binding.root.context
            binding.model.text = product.displayName.ifBlank { product.type }
            // 服务端目录不提供厂商与固件；不展示本地臆测值。
            binding.vendor.visibility = View.GONE
            binding.firmware.visibility = View.GONE
            binding.meta.text = buildMeta(product)
            binding.note.text = product.description
            binding.note.visibility =
                if (product.description.isBlank()) View.GONE else View.VISIBLE

            val count = deviceCounts()[product.type] ?: 0
            binding.myDevices.text = context.getString(R.string.product_my_devices, count)
            binding.myDevices.visibility = if (count > 0) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onClick(product) }
            binding.root.contentDescription = context.getString(
                R.string.product_open_description,
                binding.model.text,
            )
        }

        /** 分类 + App 真正能解析的指标；不支持的特征不在这里冒充能力。 */
        private fun buildMeta(product: DeviceTypeCatalogItem): String {
            val category = binding.root.context.getString(
                productCategoryOf(product.type).labelRes(),
            )
            val metrics = product.supportedMetrics.joinToString(SEPARATOR) { metric ->
                metric.label(binding.root.context)
            }
            return if (metrics.isBlank()) category else "$category$SEPARATOR$metrics"
        }
    }

    private companion object {
        const val SEPARATOR = " · "

        val DIFF = object : DiffUtil.ItemCallback<DeviceTypeCatalogItem>() {
            override fun areItemsTheSame(
                oldItem: DeviceTypeCatalogItem,
                newItem: DeviceTypeCatalogItem,
            ) = oldItem.type == newItem.type

            override fun areContentsTheSame(
                oldItem: DeviceTypeCatalogItem,
                newItem: DeviceTypeCatalogItem,
            ) = oldItem == newItem
        }
    }
}

internal fun ProductCategory.labelRes(): Int = when (this) {
    ProductCategory.HEALTH -> R.string.product_category_health
    ProductCategory.ENVIRONMENT -> R.string.product_category_environment
    ProductCategory.GATEWAY -> R.string.product_category_gateway
    ProductCategory.OTHER -> R.string.product_category_other
}
