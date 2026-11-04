package com.platojobs.pkotlinapp.domain.model

/**
 * 领域模型：UI 与业务逻辑只依赖这个类型，不直接依赖 Room 实体。
 */
data class Todo(
    val id: Long,
    val title: String,
    val note: String,
    val isDone: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
