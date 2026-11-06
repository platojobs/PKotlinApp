package com.platojobs.pkotlinapp.ui.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ItemFeedbackFooterBinding
import com.platojobs.pkotlinapp.databinding.ItemFeedbackHistoryBinding
import com.platojobs.pkotlinapp.domain.feedback.FeedbackItem
import com.platojobs.pkotlinapp.domain.feedback.FeedbackKind
import com.platojobs.pkotlinapp.domain.feedback.FeedbackStatus
import com.platojobs.pkotlinapp.ui.common.TimeFormat

/** 反馈记录列表：真实服务端状态 + 时间；底部为游标加载更多。 */
class FeedbackHistoryAdapter(
    private val onLoadMoreRetry: () -> Unit,
) : ListAdapter<FeedbackHistoryAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    fun submit(items: List<FeedbackItem>, footer: Footer) {
        submitList(
            buildList(items.size + 1) {
                items.forEach { add(Row.Item(it)) }
                if (footer != Footer.NONE) add(Row.FooterRow(footer))
            },
        )
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is Row.FooterRow) TYPE_FOOTER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOOTER) {
            FooterHolder(ItemFeedbackFooterBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemFeedbackHistoryBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Item -> (holder as ItemHolder).bind(row.item)
            is Row.FooterRow -> (holder as FooterHolder).bind(row.footer, onLoadMoreRetry)
        }
    }

    enum class Footer { NONE, LOADING, FAILED }

    /** 条目与尾部加载态都进 DiffUtil，稳定 ID 保证尾部状态变化不重绑条目。 */
    sealed interface Row {
        val stableId: Long

        data class Item(val item: FeedbackItem) : Row {
            override val stableId: Long get() = item.feedbackId.hashCode().toLong()
        }

        data class FooterRow(val footer: Footer) : Row {
            override val stableId: Long get() = FOOTER_ID
        }
    }

    private class ItemHolder(
        private val binding: ItemFeedbackHistoryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FeedbackItem) {
            val context = binding.root.context
            val kind = context.getString(
                when (item.kind) {
                    FeedbackKind.BUG -> R.string.feedback_category_bug
                    FeedbackKind.FEATURE -> R.string.feedback_category_feature
                    FeedbackKind.EXPERIENCE -> R.string.feedback_category_experience
                    FeedbackKind.OTHER -> R.string.feedback_category_other
                },
            )
            val status = context.getString(
                when (item.status) {
                    FeedbackStatus.SUBMITTED -> R.string.feedback_status_submitted
                    FeedbackStatus.RECEIVED -> R.string.feedback_status_received
                    FeedbackStatus.CLOSED -> R.string.feedback_status_closed
                    FeedbackStatus.UNKNOWN -> R.string.feedback_status_unknown
                },
            )
            val time = TimeFormat.date(context, item.createdAt)
            binding.feedbackKind.text = kind
            binding.feedbackStatus.text = status
            binding.feedbackBody.text = item.description
            binding.feedbackTime.text = context.getString(
                R.string.feedback_history_row_meta,
                status,
                time,
            )
            // 整行可无障碍朗读：类型、状态与时间。
            binding.root.contentDescription = context.getString(
                R.string.feedback_history_row_meta,
                "$kind · $status",
                time,
            )
        }
    }

    private class FooterHolder(
        private val binding: ItemFeedbackFooterBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(footer: Footer, onLoadMoreRetry: () -> Unit) {
            val context = binding.root.context
            when (footer) {
                Footer.LOADING -> {
                    binding.feedbackFooter.setText(R.string.feedback_history_loading_more)
                    binding.feedbackFooter.isClickable = false
                    binding.feedbackFooter.isFocusable = false
                }

                Footer.FAILED -> {
                    binding.feedbackFooter.setText(R.string.feedback_history_load_more_failed)
                    binding.feedbackFooter.contentDescription =
                        context.getString(R.string.feedback_history_load_more_failed)
                    binding.feedbackFooter.isClickable = true
                    binding.feedbackFooter.isFocusable = true
                    binding.feedbackFooter.setOnClickListener { onLoadMoreRetry() }
                }

                Footer.NONE -> Unit
            }
            binding.feedbackFooter.visibility =
                if (footer == Footer.NONE) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val TYPE_ITEM = 0
        const val TYPE_FOOTER = 1
        const val FOOTER_ID = Long.MIN_VALUE

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    }
}
