package com.platojobs.pkotlinapp

import android.app.Application
import com.platojobs.pkotlinapp.di.AppContainer

class PKotlinApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}
