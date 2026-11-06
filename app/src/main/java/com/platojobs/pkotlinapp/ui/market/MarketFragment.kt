package com.platojobs.pkotlinapp.ui.market

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.FragmentMarketBinding
import com.platojobs.pkotlinapp.domain.market.CompatibilityProfile
import com.platojobs.pkotlinapp.domain.market.MarketScenario
import com.platojobs.pkotlinapp.domain.market.ScenarioRecommendation
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.catalog.CatalogUiState
import com.platojobs.pkotlinapp.ui.catalog.catalogStatusText
import com.platojobs.pkotlinapp.ui.catalog.failureText
import com.platojobs.pkotlinapp.ui.common.HelpCenterDialog
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import com.platojobs.pkotlinapp.ui.products.label
import com.platojobs.pkotlinapp.ui.products.labelRes
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 「市场」不是虚构电商，而是设备与服务市场：
 * 兼容性查询（真实 GATT 服务 + 解析能力）、场景选购建议、服务入口。
 * 商品条目只是 catalog 示例，默认隐藏，且永不展示库存/价格/销量。
 */
class MarketFragment : Fragment(R.layout.fragment_market) {

    private var _binding: FragmentMarketBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MarketViewModel by viewModels {
        MarketViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private lateinit var threeState: ThreeStateHost

    /** 防止 Switch 的程序化 setChecked 触发监听导致重复写入。 */
    private var bindingSwitch = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMarketBinding.bind(view)

        binding.skeletonMarket.configure(rows = 4, rowHeightPx = dp(88))
        threeState = ThreeStateHost(
            content = binding.marketContent,
            skeleton = binding.skeletonMarket,
            overlay = binding.emptyContainer,
        )

        (activity as? MainActivity)?.attachHeaderScroll(binding.marketScroll)

        binding.deviceTypeChips.contentDescription =
            getString(R.string.market_compat_chip_group)
        binding.showAccessoriesSwitch.setOnCheckedChangeListener { _, checked ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            viewModel.setShowAccessories(checked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { state -> render(state) } }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: MarketUiState) {
        when (state) {
            MarketUiState.Loading -> threeState.showLoading()

            is MarketUiState.Content -> {
                val data = state.data
                val status = catalogStatusText(requireContext(), CatalogUiState.Content(
                    snapshot = state.catalog,
                    refreshFailure = state.refreshFailure,
                ))
                binding.catalogStatus.text = status.orEmpty()
                binding.catalogStatus.visibility =
                    if (status.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.marketScroll.visibility = View.VISIBLE
                renderChips(data)
                renderCompatibility(data)
                renderGuides(data)
                renderServices()
                renderAccessories(data)
                if (data.profiles.isEmpty()) {
                    threeState.showEmpty(
                        title = getString(R.string.market_compat_empty),
                        hint = getString(R.string.market_empty_hint),
                        action = getString(R.string.market_retry),
                        onAction = { viewModel.load() },
                    )
                } else {
                    threeState.showContent()
                }
            }

            is MarketUiState.Error -> threeState.showError(
                message = state.failure?.let { failureText(requireContext(), it) }
                    ?: getString(R.string.catalog_error_unknown),
                onRetry = { viewModel.load() },
            )
        }
    }

    private fun renderChips(data: MarketData) {
        binding.deviceTypeChips.removeAllViews()
        binding.deviceTypeChips.visibility =
            if (data.profiles.isEmpty()) View.GONE else View.VISIBLE
        data.profiles.forEach { profile ->
            val chip = Chip(requireContext()).apply {
                text = profile.displayName
                isCheckable = true
                isChecked = profile.deviceTypeKey == data.selectedProfile?.deviceTypeKey
                // Chip 在 Material 里不支持多行（setSingleLine(false) 会抛异常）；
                // 200% 字号下由 ChipGroup 的横向滚动与省略号兜底。
                ellipsize = TextUtils.TruncateAt.END
                minimumHeight = dimen(R.dimen.touch_min)
                contentDescription = getString(
                    R.string.market_compat_chip_content,
                    profile.displayName,
                )
                setOnClickListener { viewModel.selectType(profile.deviceTypeKey) }
            }
            binding.deviceTypeChips.addView(chip)
        }
    }

    private fun renderCompatibility(data: MarketData) {
        binding.compatibilityCards.removeAllViews()
        val profile = data.selectedProfile
        binding.compatibilityEmpty.visibility =
            if (profile == null) View.VISIBLE else View.GONE
        if (profile == null) return

        val card = marketCard()
        val body = cardBody(card)

        body.addView(title(profile.displayName))
        body.addView(
            caption(
                getString(
                    R.string.market_compat_category,
                    getString(profile.category.labelRes()),
                ),
            ),
        )
        val declared = profile.metrics
        body.addView(
            bodyText(
                if (declared.isEmpty()) {
                    getString(R.string.market_compat_no_metrics)
                } else {
                    getString(
                        R.string.market_compat_metrics,
                        declared.joinToString("、") { it.label(requireContext()) },
                    )
                },
            ),
            topMargin(R.dimen.space_8),
        )
        if (profile.unsupportedMetrics.isNotEmpty()) {
            body.addView(
                caption(
                    getString(
                        R.string.market_compat_unsupported_metrics,
                        profile.unsupportedMetrics.joinToString("、") {
                            it.label(requireContext())
                        },
                    ),
                ),
                topMargin(R.dimen.space_4),
            )
        }
        body.addView(
            bodyText(
                if (profile.services.isEmpty()) {
                    getString(R.string.market_compat_no_services)
                } else {
                    getString(
                        R.string.market_compat_services,
                        profile.services.joinToString("、") { "${it.name} (${it.uuid})" },
                    )
                },
            ),
            topMargin(R.dimen.space_4),
        )

        if (profile.limitations.isNotEmpty()) {
            body.addView(
                caption(getString(R.string.market_compat_limitations)),
                topMargin(R.dimen.space_8),
            )
            // 限制文案直接来自服务端目录，不在本地生成或改写。
            profile.limitations.forEach { limitation ->
                body.addView(
                    bodyText("• $limitation"),
                    topMargin(R.dimen.space_4),
                )
            }
        }

        binding.compatibilityCards.addView(card)
    }

    private fun renderGuides(data: MarketData) {
        binding.guideCards.removeAllViews()
        data.recommendations.forEach { recommendation ->
            val card = marketCard()
            val body = cardBody(card)
            body.addView(title(scenarioTitle(recommendation.scenario)))
            if (recommendation.recommended.isEmpty()) {
                body.addView(
                    bodyText(getString(R.string.market_guide_no_match)),
                    topMargin(R.dimen.space_4),
                )
            } else {
                body.addView(
                    bodyText(
                        getString(
                            R.string.market_guide_recommended,
                            recommendation.recommended.joinToString("、") { it.displayName },
                        ),
                    ),
                    topMargin(R.dimen.space_4),
                )
                body.addView(
                    bodyText(
                        getString(
                            R.string.market_guide_evidence,
                            recommendation.evidence.metrics.joinToString("、") {
                                it.label(requireContext())
                            },
                            recommendation.recommended.size,
                            recommendation.evidence.serviceCount,
                        ),
                    ),
                    topMargin(R.dimen.space_4),
                )
            }
            card.contentDescription = getString(
                R.string.market_guide_card_content,
                scenarioTitle(recommendation.scenario),
                if (recommendation.recommended.isEmpty()) {
                    getString(R.string.market_guide_no_match)
                } else {
                    recommendation.recommended.joinToString("、") { it.displayName }
                },
            )
            binding.guideCards.addView(card)
        }
    }

    private fun renderServices() {
        binding.serviceEntries.removeAllViews()
        addService(
            title = getString(R.string.market_service_pairing_title),
            desc = getString(R.string.market_service_pairing_desc),
            onClick = { (activity as? MainActivity)?.openScan() },
        )
        addService(
            title = getString(R.string.market_service_trouble_title),
            desc = getString(R.string.market_service_trouble_desc),
            onClick = {
                HelpCenterDialog.show(
                    requireContext(),
                    HelpCenterDialog.troubleshooting,
                )
            },
        )
        addService(
            title = getString(R.string.market_service_power_title),
            desc = getString(R.string.market_service_power_desc),
            onClick = { (activity as? MainActivity)?.openProfileCollection() },
        )
    }

    private fun renderAccessories(data: MarketData) {
        bindingSwitch = true
        binding.showAccessoriesSwitch.isChecked = data.showAccessories
        bindingSwitch = false

        binding.accessoriesUnavailable.visibility =
            if (data.showAccessories && data.accessoriesUnavailable) View.VISIBLE else View.GONE
        binding.accessoryList.visibility =
            if (data.showAccessories && data.accessories.isNotEmpty()) View.VISIBLE else View.GONE
        binding.accessoryList.removeAllViews()
        if (!data.showAccessories) return

        data.accessories.forEach { item -> binding.accessoryList.addView(accessoryRow(item)) }
    }

    private fun addService(title: String, desc: String, onClick: () -> Unit) {
        val card = marketCard().apply {
            isClickable = true
            isFocusable = true
            minimumHeight = dimen(R.dimen.touch_min)
            contentDescription = getString(R.string.market_service_content, title, desc)
            setOnClickListener { onClick() }
        }
        val body = cardBody(card)
        body.addView(title(title))
        body.addView(bodyText(desc), topMargin(R.dimen.space_4))
        binding.serviceEntries.addView(card)
    }

    private fun accessoryRow(item: MarketItem): View {
        val card = marketCard()
        val body = cardBody(card)
        body.addView(title(item.title))
        body.addView(
            bodyText(
                getString(
                    R.string.market_accessory_meta,
                    item.vendor.ifBlank { getString(R.string.market_unknown_value) },
                    item.category.ifBlank { getString(R.string.market_unknown_value) },
                ),
            ),
            topMargin(R.dimen.space_4),
        )
        if (item.compatibility.isNotBlank()) {
            body.addView(
                bodyText(getString(R.string.market_accessory_compat, item.compatibility)),
                topMargin(R.dimen.space_4),
            )
        }
        if (item.version.isNotBlank()) {
            body.addView(
                bodyText(getString(R.string.market_accessory_version, item.version)),
                topMargin(R.dimen.space_4),
            )
        }
        card.contentDescription = item.title
        return card
    }

    private fun scenarioTitle(scenario: MarketScenario): String =
        when (scenario) {
            MarketScenario.HEALTH -> getString(R.string.market_guide_scenario_health)
            MarketScenario.ENVIRONMENT -> getString(R.string.market_guide_scenario_environment)
            MarketScenario.MULTI_DEVICE -> getString(R.string.market_guide_scenario_network)
        }

    // ---- 纯代码构建卡片，避免为每种段落新增一套 XML ----

    private fun marketCard(): MaterialCardView = MaterialCardView(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dimen(R.dimen.card_gap) }
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        strokeColor = ContextCompat.getColor(context, R.color.divider)
        strokeWidth = dimen(R.dimen.stroke_1)
        radius = dimen(R.dimen.radius_12).toFloat()
        cardElevation = 0f
        isClickable = false
    }

    private fun cardBody(card: MaterialCardView): LinearLayout =
        LinearLayout(card.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimen(R.dimen.card_padding),
                dimen(R.dimen.card_padding),
                dimen(R.dimen.card_padding),
                dimen(R.dimen.card_padding),
            )
            card.addView(this)
        }

    private fun title(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_App_Title)
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        // 长类型名/建议在 200% 字号下换行，不截断。
        isSingleLine = false
    }

    private fun bodyText(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_App_Body)
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        isSingleLine = false
    }

    private fun caption(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        setTextAppearance(R.style.TextAppearance_App_Caption)
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        isSingleLine = false
    }

    private fun topMargin(resId: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dimen(resId) }

    private fun dimen(resId: Int): Int = resources.getDimensionPixelSize(resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
