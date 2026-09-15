package com.threadbare.client

import android.app.Application
import com.threadbare.client.util.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.materialiseDefaults(this)
    }
}
