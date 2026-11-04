package com.platojobs.pkotlinapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.platojobs.pkotlinapp.domain.model.Todo

@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val note: String,
    val isDone: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

fun TodoEntity.toDomain(): Todo = Todo(
    id = id,
    title = title,
    note = note,
    isDone = isDone,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
