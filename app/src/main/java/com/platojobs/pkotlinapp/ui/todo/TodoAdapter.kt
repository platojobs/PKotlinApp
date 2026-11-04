package com.platojobs.pkotlinapp.ui.todo

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.platojobs.pkotlinapp.databinding.ItemTodoBinding
import com.platojobs.pkotlinapp.domain.model.Todo

class TodoAdapter(
    private val onToggle: (Todo, Boolean) -> Unit,
    private val onEdit: (Todo) -> Unit,
    private val onDelete: (Todo) -> Unit,
) : ListAdapter<Todo, TodoAdapter.TodoViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TodoViewHolder =
        TodoViewHolder(
            ItemTodoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: TodoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TodoViewHolder(
        private val binding: ItemTodoBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: Todo? = null

        init {
            binding.checkboxDone.setOnClickListener {
                current?.let { todo -> onToggle(todo, binding.checkboxDone.isChecked) }
            }
            binding.root.setOnClickListener {
                current?.let(onEdit)
            }
            binding.deleteButton.setOnClickListener {
                current?.let(onDelete)
            }
        }

        fun bind(todo: Todo) {
            current = todo
            binding.titleText.text = todo.title
            binding.noteText.text = todo.note
            binding.noteText.isVisible = todo.note.isNotBlank()
            binding.checkboxDone.isChecked = todo.isDone

            val strikeThrough = Paint.STRIKE_THRU_TEXT_FLAG
            binding.titleText.paintFlags = if (todo.isDone) {
                binding.titleText.paintFlags or strikeThrough
            } else {
                binding.titleText.paintFlags and strikeThrough.inv()
            }
            binding.titleText.alpha = if (todo.isDone) 0.6f else 1f
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Todo>() {
            override fun areItemsTheSame(oldItem: Todo, newItem: Todo): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Todo, newItem: Todo): Boolean =
                oldItem == newItem
        }
    }
}
