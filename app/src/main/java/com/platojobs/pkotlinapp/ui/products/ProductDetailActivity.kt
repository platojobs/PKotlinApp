package com.platojobs.pkotlinapp.ui.products

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
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
import com.platojobs.pkotlinapp.data.catalog.CatalogRefreshResult
import com.platojobs.pkotlinapp.databinding.ActivityProductDetailBinding
import com.platojobs.pkotlinapp.domain.catalog.CatalogService
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalog
import com.platojobs.pkotlinapp.domain.catalog.DeviceTypeCatalogItem
import com.platojobs.pkotlinapp.domain.model.Device
import com.platojobs.pkotlinapp.domain.product.productCategoryOf
import com.platojobs.pkotlinapp.ui.catalog.CatalogUiState
import com.platojobs.pkotlinapp.ui.catalog.catalogStatusText
import com.platojobs.pkotlinapp.ui.catalog.failureText
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import com.platojobs.pkotlinapp.ui.common.applyBottomInsetPadding
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.applyTopInset
import com.platojobs.pkotlinapp.ui.detail.DeviceDetailActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 产品详情页：说明、目录声明的指标（含本机不支持项）、限制、兼容性与 GATT 服务。 */
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
        binding.detailScroll.applyBottomInsetPadding()
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
                message = state.failure?.let { failureText(this, it) }
                    ?: getString(R.string.catalog_error_unknown),
                onRetry = { viewModel.load() },
            )

            is ProductDetailState.Content -> {
                renderContent(
                    product = state.product,
                    devices = state.devices,
                    catalog = state.catalog,
                    refreshFailure = state.refreshFailure,
                )
                threeState.showContent()
            }
        }
    }

    private fun renderContent(
        product: DeviceTypeCatalogItem,
        devices: List<Device>,
        catalog: DeviceTypeCatalog,
        refreshFailure: CatalogRefreshResult.Failed?,
    ) {
        binding.detailName.text = product.displayName.ifBlank { product.type }
        // 服务端目录没有厂商字段，不展示本地臆测值。
        binding.detailVendor.visibility = View.GONE
        binding.detailCategory.text = getString(productCategoryOf(product.type).labelRes())

        val catalogLabel = catalogStatusText(
            this,
            CatalogUiState.Content(catalog, refreshFailure),
        )
        binding.detailCatalogStatus.text = catalogLabel.orEmpty()
        binding.detailCatalogStatus.visibility =
            if (catalogLabel.isNullOrBlank()) View.GONE else View.VISIBLE

        val description = product.description
        binding.detailDescription.text = description
        binding.detailDescriptionTitle.visibility =
            if (description.isBlank()) View.GONE else View.VISIBLE
        binding.detailDescription.visibility =
            if (description.isBlank()) View.GONE else View.VISIBLE

        renderMetrics(product)
        renderCompatibility(product)
        renderLimitations(product)
        renderServices(product.services)
        renderMyDevices(devices)
    }

    /** 目录声明的指标全部展示；本机解析器不支持的必须明确标注。 */
    private fun renderMetrics(product: DeviceTypeCatalogItem) {
        binding.detailMetrics.removeAllViews()
        val metrics = product.metrics
        binding.detailMetrics.visibility = if (metrics.isEmpty()) View.GONE else View.VISIBLE
        binding.detailMetricsEmpty.visibility = if (metrics.isEmpty()) View.VISIBLE else View.GONE
        metrics.forEach { metric ->
            val chip = Chip(this).apply {
                text = metric.chipLabel(this@ProductDetailActivity)
                isClickable = false
                isCheckable = false
                minHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
                // 不支持的指标使用文案本身说明，避免只靠颜色区分。
                contentDescription = text
            }
            chip.setTextAppearance(R.style.TextAppearance_App_Caption)
            binding.detailMetrics.addView(chip)
        }
    }

    private fun renderCompatibility(product: DeviceTypeCatalogItem) {
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
        // 固件版本不在目录契约中，保持隐藏而不是伪造。
        binding.detailFirmware.visibility = View.GONE
    }

    /** 服务端目录声明的使用限制，原始文案直出。 */
    private fun renderLimitations(product: DeviceTypeCatalogItem) {
        binding.detailLimitations.removeAllViews()
        val limitations = product.limitations
        binding.detailLimitationsTitle.visibility =
            if (limitations.isEmpty()) View.GONE else View.VISIBLE
        binding.detailLimitations.visibility =
            if (limitations.isEmpty()) View.GONE else View.VISIBLE
        limitations.forEach { limitation ->
            binding.detailLimitations.addView(
                TextView(this).apply {
                    text = getString(R.string.product_limitation_item, limitation)
                    setTextAppearance(R.style.TextAppearance_App_Caption)
                    isSingleLine = false
                    setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.space_4))
                },
            )
        }
    }

    private fun renderServices(services: List<CatalogService>) {
        binding.detailServices.removeAllViews()
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
                    isSingleLine = false
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
                    gravity = Gravity.CENTER_VERTICAL
                    isSingleLine = false
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

        fun start(context: Context, deviceTypeKey: String) {
            val intent = Intent(context, ProductDetailActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_TYPE, deviceTypeKey)
            }
            context.startActivity(intent)
        }
    }
}
