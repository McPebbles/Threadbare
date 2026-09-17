package com.threadbare.client

import android.app.Application
import com.threadbare.client.util.Prefs
import com.threadbare.client.util.RestartGate

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.materialiseDefaults(this)
        // After the defaults, not before: a key this process never read cannot
        // be out of step with it, and a snapshot of absent values would make
        // every switch look pending on a fresh install.
        launchSnapshot = Prefs.snapshot(this, RestartGate.KEYS)
    }

    companion object {
        /**
         * What the restart-gated settings were when this process started.
         *
         * Process-scoped on purpose: a real restart takes the app through
         * [onCreate] again, which is precisely when the note should clear.
         */
        var launchSnapshot: Map<String, Boolean> = emptyMap()
            private set
    }
}
