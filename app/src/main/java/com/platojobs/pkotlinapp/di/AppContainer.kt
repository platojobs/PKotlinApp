package com.platojobs.pkotlinapp.di

import android.content.Context
import com.platojobs.pkotlinapp.data.local.TodoDatabase
import com.platojobs.pkotlinapp.data.repository.TodoRepositoryImpl
import com.platojobs.pkotlinapp.domain.repository.TodoRepository

/**
 * 手动依赖注入容器：应用级单例都在这里组装，业务替换时只需要改这一处。
 */
class AppContainer(context: Context) {

    private val database = TodoDatabase.getInstance(context)

    val todoRepository: TodoRepository = TodoRepositoryImpl(database.todoDao())
}
