package com.platojobs.pkotlinapp.data.repository

import com.platojobs.pkotlinapp.data.local.TodoDao
import com.platojobs.pkotlinapp.data.local.TodoEntity
import com.platojobs.pkotlinapp.data.local.toDomain
import com.platojobs.pkotlinapp.domain.model.Todo
import com.platojobs.pkotlinapp.domain.repository.TodoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TodoRepositoryImpl(private val dao: TodoDao) : TodoRepository {

    override fun observeTodos(): Flow<List<Todo>> =
        dao.observeAll().map { entities -> entities.map(TodoEntity::toDomain) }

    override suspend fun add(title: String, note: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            TodoEntity(
                title = title,
                note = note,
                isDone = false,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    override suspend fun edit(id: Long, title: String, note: String) {
        val current = dao.findById(id) ?: return
        dao.update(
            current.copy(
                title = title,
                note = note,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun setDone(id: Long, done: Boolean) {
        val current = dao.findById(id) ?: return
        dao.update(
            current.copy(
                isDone = done,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}
