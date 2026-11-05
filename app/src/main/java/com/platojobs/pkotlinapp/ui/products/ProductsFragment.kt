package com.platojobs.pkotlinapp.ui.products

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.FragmentProductsBinding
import com.platojobs.pkotlinapp.domain.model.ProductModel
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

    private val adapter = ProductAdapter()
    private lateinit var threeState: ThreeStateHost

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentProductsBinding.bind(view)

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
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: RemoteUiState<ProductModel>) {
        when (state) {
            RemoteUiState.Loading -> threeState.showLoading()

            is RemoteUiState.Success -> {
                adapter.submitList(state.items)
                if (state.items.isEmpty()) {
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
                message = getString(R.string.products_error, state.message.orEmpty()),
                onRetry = { viewModel.load() },
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
