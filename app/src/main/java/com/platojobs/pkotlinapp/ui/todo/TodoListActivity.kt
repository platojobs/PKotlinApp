package com.platojobs.pkotlinapp.ui.todo

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.platojobs.pkotlinapp.PKotlinApplication
import com.platojobs.pkotlinapp.R
import com.platojobs.pkotlinapp.databinding.ActivityTodoListBinding
import com.platojobs.pkotlinapp.domain.model.Todo
import com.platojobs.pkotlinapp.ui.widget.EmptyStateView
import kotlinx.coroutines.launch

class TodoListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTodoListBinding
    private lateinit var emptyStateView: EmptyStateView
    private lateinit var todoAdapter: TodoAdapter

    private val viewModel: TodoListViewModel by viewModels {
        TodoListViewModel.factory((application as PKotlinApplication).container.todoRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTodoListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupList()
        setupEmptyState()
        setupActions()
        observeState()
    }

    private fun setupList() {
        todoAdapter = TodoAdapter(
            onToggle = { todo, done -> viewModel.setDone(todo.id, done) },
            onEdit = { todo -> showEditor(todo) },
            onDelete = { todo -> confirmDelete(todo) },
        )
        binding.todoList.apply {
            layoutManager = LinearLayoutManager(this@TodoListActivity)
            adapter = todoAdapter
        }
    }

    private fun setupEmptyState() {
        // 空状态完全由 Kotlin 代码构建，不使用 XML 布局
        emptyStateView = EmptyStateView(this).apply {
            setMessage(
                getString(R.string.todo_empty_title),
                getString(R.string.todo_empty_hint),
            )
            onActionClick = { showEditor(null) }
        }
        binding.contentFrame.addView(
            emptyStateView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun setupActions() {
        binding.addTodoButton.setOnClickListener { showEditor(null) }

        supportFragmentManager.setFragmentResultListener(
            TodoEditorDialog.RESULT_KEY,
            this,
        ) { _, result ->
            val id = result.getLong(TodoEditorDialog.KEY_ID, TodoEditorDialog.NEW_ID)
            val title = result.getString(TodoEditorDialog.KEY_TITLE).orEmpty()
            val note = result.getString(TodoEditorDialog.KEY_NOTE).orEmpty()
            if (id == TodoEditorDialog.NEW_ID) {
                viewModel.addTodo(title, note)
            } else {
                viewModel.editTodo(id, title, note)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    todoAdapter.submitList(state.todos)
                    emptyStateView.isVisible = state.isEmpty
                    binding.progressCard.isVisible = !state.isEmpty
                    binding.progressView.setProgress(state.doneCount, state.totalCount)
                }
            }
        }
    }

    private fun showEditor(todo: Todo?) {
        TodoEditorDialog.newInstance(todo)
            .show(supportFragmentManager, TodoEditorDialog.TAG)
    }

    private fun confirmDelete(todo: Todo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.todo_delete_title)
            .setMessage(getString(R.string.todo_delete_message, todo.title))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteTodo(todo.id) }
            .show()
    }
}
