package com.platojobs.pkotlinapp.ui.profile

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
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
import com.platojobs.pkotlinapp.domain.feedback.FeedbackAlreadyClosedException
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDetail
import com.platojobs.pkotlinapp.domain.feedback.FeedbackItem
import com.platojobs.pkotlinapp.domain.feedback.FeedbackStatus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.platojobs.pkotlinapp.ui.common.ThreeStateHost
import com.platojobs.pkotlinapp.ui.common.TimeFormat
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

    private val adapter = FeedbackHistoryAdapter(
        onLoadMoreRetry = { viewModel.loadMore() },
        onItemClick = { showDetail(it) },
    )

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

    /** 点进详情：展示真实状态与状态说明；未关闭的反馈可在此关闭。 */
    private fun showDetail(item: FeedbackItem) {
        lifecycleScope.launch {
            val detail = try {
                viewModel.detail(item.feedbackId)
            } catch (_: Exception) {
                Snackbar.make(
                    binding.root,
                    R.string.feedback_detail_load_failed,
                    Snackbar.LENGTH_LONG,
                ).show()
                return@launch
            }
            renderDetailDialog(detail)
        }
    }

    private fun renderDetailDialog(detail: FeedbackDetail) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        container.addView(TextView(this).apply { text = statusText(detail.status) })
        container.addView(
            TextView(this).apply {
                text = detail.statusMessage
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.feedback_detail_no_status_message)
            },
        )
        container.addView(
            TextView(this).apply {
                text = getString(R.string.feedback_detail_updated, TimeFormat.date(this@FeedbackHistoryActivity, detail.updatedAt))
            },
        )
        container.addView(
            TextView(this).apply {
                text = detail.description
                setPadding(0, dp(12), 0, 0)
            },
        )

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.feedback_detail_title)
            .setView(container)
            .setNegativeButton(R.string.action_close, null)
        if (detail.status != FeedbackStatus.CLOSED) {
            builder.setPositiveButton(R.string.feedback_detail_close_action) { _, _ ->
                confirmClose(detail.feedbackId)
            }
        }
        builder.show()
    }

    private fun confirmClose(feedbackId: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.feedback_detail_close_title)
            .setMessage(R.string.feedback_detail_close_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.feedback_detail_close_action) { _, _ ->
                lifecycleScope.launch {
                    val message = try {
                        viewModel.close(feedbackId)
                        viewModel.applyClosed(feedbackId)
                        R.string.feedback_detail_close_success
                    } catch (_: FeedbackAlreadyClosedException) {
                        // 409 是明确的业务状态，不能用「未知错误」糊过去。
                        viewModel.applyClosed(feedbackId)
                        R.string.feedback_detail_already_closed
                    } catch (_: Exception) {
                        R.string.feedback_detail_close_failed
                    }
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun statusText(status: FeedbackStatus): String = getString(
        R.string.feedback_detail_status,
        getString(
            when (status) {
                FeedbackStatus.SUBMITTED -> R.string.feedback_status_submitted
                FeedbackStatus.RECEIVED -> R.string.feedback_status_received
                FeedbackStatus.CLOSED -> R.string.feedback_status_closed
                FeedbackStatus.UNKNOWN -> R.string.feedback_status_unknown
            },
        ),
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
