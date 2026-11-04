package com.platojobs.pkotlinapp

import android.app.Application
import com.platojobs.pkotlinapp.di.AppContainer

class PKotlinApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.settings.applyTheme()
    }
}
