package com.platojobs.pkotlinapp.ui.profile

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ActivityFeedbackHistoryBinding
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import com.platojobs.pkotlinapp.ui.common.applyBottomInset
import com.platojobs.pkotlinapp.ui.common.applyHorizontalInsets
import com.platojobs.pkotlinapp.ui.common.applyTopInset
import kotlinx.coroutines.launch

/** 我的反馈记录：真实 GET /feedback/mine，下拉刷新 + 游标加载更多。 */
class FeedbackHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeedbackHistoryBinding

    private val viewModel: FeedbackHistoryViewModel by viewModels {
        FeedbackHistoryViewModel.factory(application as PKotlinApplication)
    }

    private lateinit var threeState: ThreeStateHost

    private val adapter = FeedbackHistoryAdapter(onLoadMoreRetry = { viewModel.loadMore() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityFeedbackHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyHorizontalInsets()
        binding.toolbar.applyTopInset()
        binding.feedbackRefresh.applyBottomInset()
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.feedbackSkeleton.configure(rows = 4, rowHeightPx = dp(72))
        threeState = ThreeStateHost(
            content = binding.feedbackContent,
            skeleton = binding.feedbackSkeleton,
            overlay = binding.feedbackEmptyContainer,
        )

        binding.feedbackList.layoutManager = LinearLayoutManager(this)
        binding.feedbackList.adapter = adapter
        binding.feedbackList.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    // 触底时按游标加载下一页；ViewModel 内部防止重复请求。
                    if (!recyclerView.canScrollVertically(1)) {
                        viewModel.loadMore()
                    }
                }
            },
        )
        // 下拉刷新只拉反馈列表，不触发全量同步。
        binding.feedbackRefresh.setOnRefreshListener { viewModel.refresh() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: FeedbackHistoryUi) {
        if (state.items.isEmpty()) {
            when {
                state.loading -> threeState.showLoading()

                state.error -> threeState.showError(
                    message = getString(R.string.feedback_history_error_body),
                    onRetry = { viewModel.refresh() },
                )

                else -> threeState.showEmpty(
                    title = getString(R.string.feedback_history_empty_title),
                    hint = getString(R.string.feedback_history_empty_body),
                    action = getString(R.string.feedback_history_retry),
                    onAction = { viewModel.refresh() },
                )
            }
        } else {
            threeState.showContent()
        }

        binding.feedbackRefresh.isRefreshing = state.refreshing
        adapter.submit(
            items = state.items,
            footer = when {
                state.loadingMore -> FeedbackHistoryAdapter.Footer.LOADING
                state.loadMoreFailed -> FeedbackHistoryAdapter.Footer.FAILED
                else -> FeedbackHistoryAdapter.Footer.NONE
            },
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
