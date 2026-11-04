package com.platojobs.pkotlinapp.ui.market

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.FragmentMarketBinding
import com.platojobs.pkotlinapp.domain.model.MarketItem
import com.platojobs.pkotlinapp.ui.common.EmptyStateView
import com.platojobs.pkotlinapp.ui.common.RemoteUiState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MarketFragment : Fragment() {

    private var _binding: FragmentMarketBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MarketViewModel by viewModels {
        MarketViewModel.factory(requireActivity().application as PKotlinApplication)
    }

    private val adapter = MarketAdapter()
    private lateinit var emptyState: EmptyStateView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMarketBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.marketList.layoutManager = LinearLayoutManager(requireContext())
        binding.marketList.adapter = adapter

        emptyState = EmptyStateView(requireContext()).apply {
            setIcon(R.drawable.ic_empty_ble)
        }
        binding.emptyContainer.addView(
            emptyState,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
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
            RemoteUiState.Loading -> {
                binding.progress.visibility = View.VISIBLE
                binding.emptyContainer.visibility = View.GONE
                binding.marketList.visibility = View.GONE
            }

            is RemoteUiState.Success -> {
                binding.progress.visibility = View.GONE
                adapter.submitList(state.items)
                val empty = state.items.isEmpty()
                binding.marketList.visibility = if (empty) View.GONE else View.VISIBLE
                binding.emptyContainer.visibility = if (empty) View.VISIBLE else View.GONE
                if (empty) {
                    emptyState.setTitle(getString(R.string.market_empty_title))
                    emptyState.setHint(getString(R.string.market_empty_hint))
                    emptyState.setAction(getString(R.string.market_retry)) { viewModel.load() }
                }
            }

            is RemoteUiState.Error -> {
                binding.progress.visibility = View.GONE
                binding.marketList.visibility = View.GONE
                binding.emptyContainer.visibility = View.VISIBLE
                emptyState.setTitle(getString(R.string.market_error, state.message.orEmpty()))
                emptyState.setHint(getString(R.string.market_empty_hint))
                emptyState.setAction(getString(R.string.market_retry)) { viewModel.load() }
            }
        }
    }
}
