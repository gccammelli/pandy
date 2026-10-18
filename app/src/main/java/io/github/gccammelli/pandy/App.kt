package io.github.gccammelli.pandy

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        Repo.init(androidStorage(this))
        Repo.notifier = AndroidNotifier(this)
    }
}
