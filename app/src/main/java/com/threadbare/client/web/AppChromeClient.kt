package com.threadbare.client.web

import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Every permission the page can ask for is refused, unconditionally.
 *
 * There is no branch here and no preference to loosen it. A logged-out reader
 * has no reason to hand a page the camera, the microphone, the user's location
 * or a MIDI device, and "ask the user" is not better than "no" when the answer
 * is always no.
 */
class AppChromeClient(private val host: WebHost) : WebChromeClient() {

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        host.onProgress(view, newProgress)
    }

    /**
     * Multiple windows are disabled in WebSettings, so this should never fire.
     * Refusing explicitly means a page that tries anyway gets nothing rather
     * than an unparented WebView.
     */
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean = false
}
