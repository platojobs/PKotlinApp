package com.platojobs.pkotlinapp.domain.repository

import com.platojobs.pkotlinapp.domain.model.Todo
import kotlinx.coroutines.flow.Flow

/**
 * 待办数据仓库接口。UI 层只面向这个接口，换成网络或其它存储时无需改 UI。
 */
interface TodoRepository {

    fun observeTodos(): Flow<List<Todo>>

    suspend fun add(title: String, note: String): Long

    suspend fun edit(id: Long, title: String, note: String)

    suspend fun setDone(id: Long, done: Boolean)

    suspend fun delete(id: Long)
}
