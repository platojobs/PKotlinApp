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
import com.platojobs.pkotlinapp.ui.catalog.CatalogUiState
import com.platojobs.pkotlinapp.ui.catalog.catalogStatusText
import com.platojobs.pkotlinapp.ui.catalog.failureText
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import com.platojobs.pkotlinapp.ui.common.applyBottomInsetPadding
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
                ProductDetailActivity.start(requireContext(), product.type)
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

        binding.productsList.applyBottomInsetPadding()
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
        val catalog = state.catalog
        val status = (catalog as? CatalogUiState.Content)?.let { content ->
            catalogStatusText(requireContext(), content)
        }
        binding.catalogStatus.text = status.orEmpty()
        binding.catalogStatus.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE

        when (catalog) {
            CatalogUiState.Loading -> threeState.showLoading()

            is CatalogUiState.Content -> {
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

            is CatalogUiState.Error -> threeState.showError(
                message = catalog.failure?.let { failureText(requireContext(), it) }
                    ?: getString(R.string.catalog_error_unknown),
                onRetry = { viewModel.load() },
            )
        }
    }

    /** 分类导航：设备类型说明里的真实分组 + 数量徽标；空分类不渲染。 */
    private fun renderCategories(state: ProductsUiState) {
        val counts = state.categoryCounts
        val selected = state.selectedCategory
        binding.productCategories.removeAllViews()
        if (state.catalog !is CatalogUiState.Content) {
            binding.productCategories.visibility = View.GONE
            return
        }
        val total = state.totalCount
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
