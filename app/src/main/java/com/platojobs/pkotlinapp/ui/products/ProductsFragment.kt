package com.platojobs.pkotlinapp.ui.products

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.FragmentProductsBinding
import com.platojobs.pkotlinapp.domain.product.ProductCategory
import com.platojobs.pkotlinapp.ui.MainActivity
import com.platojobs.pkotlinapp.ui.common.RemoteUiState
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class ProductsFragment : Fragment(R.layout.fragment_products) {

    private var _binding: FragmentProductsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProductsViewModel by viewModels {
        ProductsViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private lateinit var adapter: ProductAdapter
    private lateinit var threeState: ThreeStateHost

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentProductsBinding.bind(view)

        adapter = ProductAdapter(
            deviceCounts = { viewModel.state.value.devicesByType.mapValues { it.value.size } },
            onClick = { product ->
                ProductDetailActivity.start(requireContext(), product.deviceTypeKey)
            },
        )
        binding.productsList.layoutManager = LinearLayoutManager(requireContext())
        binding.productsList.adapter = adapter

        binding.skeletonProducts.configure(rows = 4, rowHeightPx = dp(88))
        threeState = ThreeStateHost(
            content = binding.productsList,
            skeleton = binding.skeletonProducts,
            overlay = binding.emptyContainer,
        )

        (activity as? MainActivity)?.attachHeaderScroll(binding.productsList)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    override fun onDestroyView() {
        binding.productsList.adapter = null
        binding.productCategories.removeAllViews()
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: ProductsUiState) {
        renderCategories(state)
        when (val catalog = state.catalog) {
            RemoteUiState.Loading -> threeState.showLoading()

            is RemoteUiState.Success -> {
                adapter.submitList(state.visibleProducts)
                if (state.visibleProducts.isEmpty()) {
                    threeState.showEmpty(
                        title = getString(R.string.products_empty_title),
                        hint = getString(R.string.products_empty_hint),
                        action = getString(R.string.products_retry),
                        onAction = { viewModel.load() },
                    )
                } else {
                    threeState.showContent()
                }
            }

            is RemoteUiState.Error -> threeState.showError(
                message = getString(R.string.products_error, catalog.message.orEmpty()),
                onRetry = { viewModel.load() },
            )
        }
    }

    /** 分类导航：设备类型说明里的真实分组 + 数量徽标；空分类不渲染。 */
    private fun renderCategories(state: ProductsUiState) {
        val counts = state.categoryCounts
        val selected = state.selectedCategory
        binding.productCategories.removeAllViews()
        if (state.catalog !is RemoteUiState.Success) {
            binding.productCategories.visibility = View.GONE
            return
        }
        val total = state.catalog.items.size
        binding.productCategories.visibility = View.VISIBLE
        addCategoryChip(null, getString(R.string.product_category_all), total, selected == null)
        ProductCategory.navigable.forEach { category ->
            val count = counts[category] ?: 0
            if (count == 0) return@forEach
            addCategoryChip(
                category,
                getString(category.labelRes()),
                count,
                selected == category,
            )
        }
    }

    private fun addCategoryChip(
        category: ProductCategory?,
        label: String,
        count: Int,
        checked: Boolean,
    ) {
        val chip = Chip(requireContext()).apply {
            text = getString(R.string.product_category_chip, label, count)
            isCheckable = true
            isChecked = checked
            minHeight = resources.getDimensionPixelSize(R.dimen.touch_min)
            setOnClickListener { viewModel.selectCategory(category) }
            contentDescription = text
        }
        chip.setTextAppearance(R.style.TextAppearance_App_Caption)
        binding.productCategories.addView(chip)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
