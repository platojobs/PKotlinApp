package com.platojobs.pkotlinapp.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.data.feedback.FeedbackStore
import com.platojobs.pkotlinapp.data.remote.ApiLimits
import com.platojobs.pkotlinapp.domain.feedback.FeedbackDetail
import com.platojobs.pkotlinapp.domain.feedback.FeedbackItem
import com.platojobs.pkotlinapp.domain.feedback.FeedbackStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 我的反馈列表页状态。
 * [error] 只代表「没有内容可展示且加载失败」；已有内容时刷新失败不覆盖列表。
 */
data class FeedbackHistoryUi(
    val items: List<FeedbackItem> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Boolean = false,
    val loadMoreFailed: Boolean = false,
)

/** `GET /feedback/mine` 倒序游标分页；下拉刷新回第一页，加载更多透传 nextCursor。 */
class FeedbackHistoryViewModel(
    private val feedbackStore: FeedbackStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedbackHistoryUi())
    val state: StateFlow<FeedbackHistoryUi> = _state.asStateFlow()

    private var nextCursor: String? = null
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        load(reset = true)
    }

    /** `GET /feedback/{id}`：详情含状态说明。 */
    suspend fun detail(id: String): FeedbackDetail = feedbackStore.detail(id)

    /** `PATCH /feedback/{id}`：关闭本人反馈；409 由数据层抛 [FeedbackAlreadyClosedException]。 */
    suspend fun close(id: String): FeedbackDetail = feedbackStore.close(id)

    /** 关闭成功后本地列表立即反映新状态，避免等下一次刷新才更新。 */
    fun applyClosed(id: String) {
        _state.update { current ->
            current.copy(
                items = current.items.map { item ->
                    if (item.feedbackId == id) item.copy(status = FeedbackStatus.CLOSED) else item
                },
            )
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || current.loading || !current.hasMore) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        loadJob?.cancel()
        _state.update {
            if (reset) {
                it.copy(
                    loading = it.items.isEmpty(),
                    refreshing = it.items.isNotEmpty(),
                    loadingMore = false,
                    error = false,
                    loadMoreFailed = false,
                )
            } else {
                it.copy(loadingMore = true, loadMoreFailed = false)
            }
        }
        val cursor = if (reset) null else nextCursor
        loadJob = viewModelScope.launch {
            try {
                val page = feedbackStore.loadPage(cursor, PAGE_SIZE)
                nextCursor = page.nextCursor
                _state.update { current ->
                    val merged = if (reset) {
                        page.items
                    } else {
                        val known = current.items.mapTo(mutableSetOf()) { it.feedbackId }
                        current.items + page.items.filterNot { known.contains(it.feedbackId) }
                    }
                    current.copy(
                        items = merged,
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        hasMore = page.hasMore,
                        error = false,
                        loadMoreFailed = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { current ->
                    if (reset) {
                        current.copy(
                            loading = false,
                            refreshing = false,
                            error = current.items.isEmpty(),
                        )
                    } else {
                        current.copy(loadingMore = false, loadMoreFailed = true)
                    }
                }
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20

        init {
            // 上限来自契约；避免误改到超限值。
            require(PAGE_SIZE <= ApiLimits.FEEDBACK_PAGE_MAX)
        }

        fun factory(app: PKotlinApplication) = viewModelFactory {
            initializer {
                FeedbackHistoryViewModel(feedbackStore = app.container.feedbackStore)
            }
        }
    }
}
