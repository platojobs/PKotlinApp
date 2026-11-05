package com.platojobs.pkotlinapp.ui.market

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.FragmentMarketBinding
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.common.RemoteUiState
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MarketFragment : Fragment(R.layout.fragment_market) {

    private var _binding: FragmentMarketBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MarketViewModel by viewModels {
        MarketViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private val adapter = MarketAdapter()
    private lateinit var threeState: ThreeStateHost

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMarketBinding.bind(view)

        binding.marketList.layoutManager = LinearLayoutManager(requireContext())
        binding.marketList.adapter = adapter
        // 两组筛选对读屏用户有明确语义，避免只读出「全部」而不知道筛的是什么。
        binding.vendorFilters.contentDescription =
            getString(R.string.market_filter_vendor_group)
        binding.compatibilityFilters.contentDescription =
            getString(R.string.market_filter_compatibility_group)

        binding.skeletonMarket.configure(rows = 4, rowHeightPx = dp(88))
        threeState = ThreeStateHost(
            content = binding.marketList,
            skeleton = binding.skeletonMarket,
            overlay = binding.emptyContainer,
        )

        (activity as? MainActivity)?.attachHeaderScroll(binding.marketList)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { state -> render(state) } }
                launch { viewModel.filters.collect { filter -> renderFilters(filter) } }
            }
        }
    }

    override fun onDestroyView() {
        binding.marketList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: RemoteUiState<MarketItem>) {
        when (state) {
            RemoteUiState.Loading -> threeState.showLoading()

            is RemoteUiState.Success -> {
                adapter.submitList(state.items)
                if (state.items.isEmpty()) {
                    if (viewModel.filters.value.hasActiveFilter) {
                        threeState.showEmpty(
                            title = getString(R.string.market_filter_empty_title),
                            hint = getString(R.string.market_filter_empty_hint),
                            action = getString(R.string.market_filter_reset),
                            onAction = { viewModel.clearFilters() },
                        )
                    } else {
                        threeState.showEmpty(
                            title = getString(R.string.market_empty_title),
                            hint = getString(R.string.market_empty_hint),
                            action = getString(R.string.market_retry),
                            onAction = { viewModel.load() },
                        )
                    }
                } else {
                    threeState.showContent()
                }
            }

            is RemoteUiState.Error -> threeState.showError(
                message = getString(R.string.market_error, state.message.orEmpty()),
                onRetry = { viewModel.load() },
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderFilters(filter: MarketFilterState) {
        binding.vendorFilters.visibility =
            if (filter.vendors.isEmpty()) View.GONE else View.VISIBLE
        binding.compatibilityFilters.visibility =
            if (filter.compatibilities.isEmpty()) View.GONE else View.VISIBLE

        binding.vendorFilters.removeAllViews()
        if (filter.vendors.isNotEmpty()) {
            addFilterChip(
                group = binding.vendorFilters,
                label = getString(R.string.market_filter_all),
                checked = filter.selectedVendor == null,
            ) { viewModel.selectVendor(null) }
            filter.vendors.forEach { vendor ->
                addFilterChip(
                    group = binding.vendorFilters,
                    label = vendor,
                    checked = filter.selectedVendor == vendor,
                ) { viewModel.selectVendor(vendor) }
            }
        }

        binding.compatibilityFilters.removeAllViews()
        if (filter.compatibilities.isNotEmpty()) {
            addFilterChip(
                group = binding.compatibilityFilters,
                label = getString(R.string.market_filter_all),
                checked = filter.selectedCompatibility == null,
            ) { viewModel.selectCompatibility(null) }
            filter.compatibilities.forEach { tag ->
                addFilterChip(
                    group = binding.compatibilityFilters,
                    label = tag,
                    checked = filter.selectedCompatibility == tag,
                ) { viewModel.selectCompatibility(tag) }
            }
        }
    }

    private fun addFilterChip(
        group: ChipGroup,
        label: String,
        checked: Boolean,
        onClick: () -> Unit,
    ) {
        val chip = Chip(requireContext()).apply {
            text = label
            isCheckable = true
            isChecked = checked
            // 200% 字号下允许换行，禁用单行省略，避免筛选词被截断。
            isSingleLine = false
            maxLines = 2
            ellipsize = null
            // 读屏先读筛选值，再读选中状态；点击后由 ViewModel 重绘 Chip 自动更新。
            contentDescription = label
            ViewCompat.setStateDescription(
                this,
                getString(
                    if (checked) {
                        R.string.market_filter_selected
                    } else {
                        R.string.market_filter_not_selected
                    },
                ),
            )
            setOnClickListener { onClick() }
        }
        group.addView(chip)
    }
}
