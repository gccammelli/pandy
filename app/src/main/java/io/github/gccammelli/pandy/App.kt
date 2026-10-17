package io.github.gccammelli.pandy

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(androidStorage(this))
    }
}
