package com.platojobs.pkotlinapp.ui.products

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemProductBinding
import com.platojobs.pkotlinapp.domain.model.ProductModel

class ProductAdapter : ListAdapter<ProductModel, ProductAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProductBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemProductBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(product: ProductModel) {
            val context = binding.root.context
            binding.model.text = product.model.ifBlank { product.id }
            binding.vendor.text = product.vendor
            binding.firmware.text = context.getString(R.string.product_firmware, product.firmwareVersion)
            binding.firmware.visibility =
                if (product.firmwareVersion.isBlank()) View.GONE else View.VISIBLE
            binding.note.text = product.note

            // GATT 服务/特征表由代码逐行构建，属于“代码建 View”的同一约定。
            binding.serviceContainer.removeAllViews()
            if (product.services.isEmpty()) {
                binding.serviceContainer.addView(
                    TextView(context).apply {
                        text = context.getString(R.string.product_no_gatt)
                        setTextAppearance(R.style.TextAppearance_App_Caption)
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    },
                )
                return
            }
            product.services.forEach { service ->
                binding.serviceContainer.addView(
                    TextView(context).apply {
                        text = context.getString(
                            R.string.product_service_item,
                            service.name.ifBlank { service.uuid },
                            service.uuid,
                        )
                        setTextAppearance(R.style.TextAppearance_App_Caption)
                        setTypeface(typeface, Typeface.BOLD)
                    },
                )
                // 新版 catalog 不返回特征列表，没有数据时不渲染空行。
                if (service.characteristics.isNotEmpty()) {
                    binding.serviceContainer.addView(
                        TextView(context).apply {
                            text = context.getString(
                                R.string.product_characteristics_item,
                                service.characteristics.joinToString(" · "),
                            )
                            setTextAppearance(R.style.TextAppearance_App_Footnote)
                            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                bottomMargin = context.resources.getDimensionPixelSize(
                                    R.dimen.space_4,
                                )
                            }
                        },
                    )
                }
            }
        }

        private fun Int.dp(context: android.content.Context): Int =
            (this * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ProductModel>() {
            override fun areItemsTheSame(oldItem: ProductModel, newItem: ProductModel) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ProductModel, newItem: ProductModel) =
                oldItem == newItem
        }
    }
}
