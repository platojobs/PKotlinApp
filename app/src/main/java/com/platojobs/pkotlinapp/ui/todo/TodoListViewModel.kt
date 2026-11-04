package com.platojobs.pkotlinapp.ui.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.platojobs.pkotlinapp.domain.model.Todo
import com.platojobs.pkotlinapp.domain.repository.TodoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodoListViewModel(private val repository: TodoRepository) : ViewModel() {

    val uiState: StateFlow<TodoUiState> = repository.observeTodos()
        .map { todos -> TodoUiState(todos = todos, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TodoUiState(),
        )

    fun addTodo(title: String, note: String) {
        viewModelScope.launch { repository.add(title, note) }
    }

    fun editTodo(id: Long, title: String, note: String) {
        viewModelScope.launch { repository.edit(id, title, note) }
    }

    fun setDone(id: Long, done: Boolean) {
        viewModelScope.launch { repository.setDone(id, done) }
    }

    fun deleteTodo(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    companion object {
        fun factory(repository: TodoRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { TodoListViewModel(repository) }
        }
    }
}

data class TodoUiState(
    val todos: List<Todo> = emptyList(),
    val isLoading: Boolean = true,
) {
    val totalCount: Int get() = todos.size
    val doneCount: Int get() = todos.count { it.isDone }
    val isEmpty: Boolean get() = !isLoading && todos.isEmpty()
}
