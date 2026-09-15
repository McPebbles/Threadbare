package com.threadbare.client.web

import android.webkit.WebView

/**
 * What [AppWebViewClient] and [AppChromeClient] are allowed to ask of the
 * activity. Deliberately narrow, and every callback carries the WebView so the
 * host can attribute the event if it ever runs more than one.
 */
interface WebHost {
    fun loadInApp(url: String)
    fun openExternally(url: String)
    fun onRefused(url: String, reason: String)
    fun onPageStarted(view: WebView, url: String?)
    fun onPageFinished(view: WebView, url: String?)
    fun onMainFrameError(view: WebView, url: String?, description: String?)
    fun onProgress(view: WebView, progress: Int)
}
