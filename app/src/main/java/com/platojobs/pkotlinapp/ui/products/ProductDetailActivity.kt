package com.platojobs.pkotlinapp.ui.products

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ActivityProductDetailBinding
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.model.ProductModel
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import com.platojobs.pkotlinapp.ui.common.applyBottomInset
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.applyTopInset
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 产品详情页：说明、可解析指标、兼容性/固件、GATT 服务与「我的设备」条目。 */
class ProductDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductDetailBinding

    private val deviceTypeKey: String by lazy {
        intent.getStringExtra(EXTRA_DEVICE_TYPE).orEmpty()
    }

    private val viewModel: ProductDetailViewModel by viewModels {
        ProductDetailViewModel.factory(
            app = application as PKotlinApplication,
            deviceTypeKey = deviceTypeKey,
        )
    }

    private lateinit var threeState: ThreeStateHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (deviceTypeKey.isBlank()) {
            finish()
            return
        }
        binding = ActivityProductDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyHorizontalInsets()
        binding.toolbar.applyTopInset()
        binding.detailScroll.applyBottomInset()
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.skeletonProductDetail.configure(rows = 4, rowHeightPx = dp(72))
        threeState = ThreeStateHost(
            content = binding.detailScroll,
            skeleton = binding.skeletonProductDetail,
            overlay = binding.detailEmptyContainer,
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: ProductDetailState) {
        when (state) {
            ProductDetailState.Loading -> threeState.showLoading()

            ProductDetailState.NotFound -> threeState.showEmpty(
                title = getString(R.string.product_detail_not_found_title),
                hint = getString(R.string.product_detail_not_found_hint),
                action = getString(R.string.products_retry),
                onAction = { viewModel.load() },
            )

            is ProductDetailState.Failure -> threeState.showError(
                message = getString(R.string.products_error, state.message.orEmpty()),
                onRetry = { viewModel.load() },
            )

            is ProductDetailState.Content -> {
                renderContent(state.product, state.devices)
                threeState.showContent()
            }
        }
    }

    private fun renderContent(product: ProductModel, devices: List<Device>) {
        binding.detailName.text = product.model.ifBlank { product.id }
        binding.detailVendor.text = product.vendor
        binding.detailCategory.text = getString(product.category.labelRes())

        val description = product.note
        binding.detailDescription.text = description
        binding.detailDescriptionTitle.visibility = if (description.isBlank()) View.GONE else View.VISIBLE
        binding.detailDescription.visibility = if (description.isBlank()) View.GONE else View.VISIBLE

        renderMetrics(product)
        renderCompatibility(product)
        renderServices(product)
        renderMyDevices(devices)
    }

    private fun renderMetrics(product: ProductModel) {
        binding.detailMetrics.removeAllViews()
        val metrics = product.supportedMetrics
        binding.detailMetrics.visibility = if (metrics.isEmpty()) View.GONE else View.VISIBLE
        binding.detailMetricsEmpty.visibility = if (metrics.isEmpty()) View.VISIBLE else View.GONE
        metrics.forEach { metric ->
            val chip = Chip(this).apply {
                text = getString(metric.labelRes())
                isClickable = false
                isCheckable = false
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
            }
            chip.setTextAppearance(R.style.TextAppearance_App_Caption)
            binding.detailMetrics.addView(chip)
        }
    }

    private fun renderCompatibility(product: ProductModel) {
        val compatibility = product.compatibility
        val label = compatibility?.displayLabel().orEmpty()
        val features = compatibility?.features.orEmpty()
        val merged = if (features.isEmpty()) {
            label
        } else {
            listOf(label, getString(R.string.product_detail_features, features.joinToString(" · ")))
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
        val hasCompatibility = merged.isNotBlank()
        binding.detailCompatibility.text = merged
        binding.detailCompatibilityTitle.visibility =
            if (hasCompatibility) View.VISIBLE else View.GONE
        binding.detailCompatibility.visibility =
            if (hasCompatibility) View.VISIBLE else View.GONE
        binding.detailFirmware.text = getString(R.string.product_detail_firmware, product.firmwareVersion)
        binding.detailFirmware.visibility =
            if (product.firmwareVersion.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderServices(product: ProductModel) {
        binding.detailServices.removeAllViews()
        val services = product.services
        binding.detailServicesEmpty.visibility =
            if (services.isEmpty()) View.VISIBLE else View.GONE
        services.forEach { service ->
            binding.detailServices.addView(
                TextView(this).apply {
                    text = getString(
                        R.string.product_service_item,
                        service.name.ifBlank { service.uuid },
                        service.uuid,
                    )
                    setTextAppearance(R.style.TextAppearance_App_Caption)
                    setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.space_4))
                },
            )
        }
    }

    /** 我的设备：按真实 deviceType 归属匹配，点击进入设备详情。 */
    private fun renderMyDevices(devices: List<Device>) {
        binding.detailDevices.removeAllViews()
        binding.detailDevicesHint.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        devices.forEach { device ->
            val label = device.name.ifBlank {
                device.advertisedName ?: device.mac
            }
            binding.detailDevices.addView(
                TextView(this).apply {
                    text = getString(R.string.product_detail_device_row, label)
                    setTextAppearance(R.style.TextAppearance_App_Body)
                    minHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    contentDescription = text
                    setOnClickListener {
                        startActivity(
                            DeviceDetailActivity.createIntent(
                                this@ProductDetailActivity,
                                device.mac,
                                device.name,
                                device.lastRssi,
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_DEVICE_TYPE = "extra_device_type"

        fun start(context: android.content.Context, deviceTypeKey: String) {
            val intent = android.content.Intent(context, ProductDetailActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_TYPE, deviceTypeKey)
            }
            context.startActivity(intent)
        }
    }
}
