package com.threadbare.client.ui

import android.app.Activity
import android.content.Intent
import com.threadbare.client.util.Safely

/**
 * Restart the app so settings read at WebView-construction time take effect.
 *
 * The process is deliberately NOT killed. Ending it would skip
 * `MainActivity.onDestroy`, and that is where an ephemeral session clears its
 * cookies and storage — a restart that silently stopped doing the thing the
 * privacy setting promises would be a poor trade for a tidier relaunch.
 * Finishing the task and starting it again gives a fresh WebView, which is all
 * that was needed.
 */
object AppRestart {

    fun restart(activity: Activity) {
        val intent = Safely.call({
            activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        }, null) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        Safely.run { activity.finishAffinity() }
        Safely.run { activity.startActivity(intent) }
    }
}
