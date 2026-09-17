package com.threadbare.client.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewFeature
import com.threadbare.client.privacy.Blocklist
import com.threadbare.client.privacy.XpromoBlock
import com.threadbare.client.util.Prefs
import com.threadbare.client.util.RefusalLog
import com.threadbare.client.util.Safely
import java.io.ByteArrayInputStream

/**
 * The boundary. Three jobs: keep the WebView on www.reddit.com, refuse the
 * app-promotion bundles before they load, and drop the requests that exist only
 * to measure the reader.
 */
class AppWebViewClient(private val host: WebHost) : WebViewClient() {

    /** Empty 204, so a blocked beacon fails fast rather than hanging. */
    private fun blocked(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    /**
     * Layer 1 of the xpromo defence lives here, and it is the reason this is an
     * app rather than a userscript: a userscript can only delete the overlay
     * after the page has built it, while this can refuse the code that builds
     * it. An implementation that never arrives cannot lock the scroll, cannot
     * render into a shadow root, and cannot return on the next route change.
     */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val uri = request.url ?: return null
        val context = view.context

        if (Prefs.blockXpromoBundles(context)) {
            val xpromo = XpromoBlock.decide(uri.host, uri.path, uri.query)
            if (xpromo.blocked) {
                RefusalLog.record(uri.toString(), xpromo.pattern)
                return blocked()
            }
        }

        val decision = Blocklist.decide(uri.host, uri.path, Prefs.blockMode(context))
        if (decision.blocked) {
            RefusalLog.record(uri.toString(), decision.reason)
            return blocked()
        }
        return null
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val target = request.url?.toString() ?: return true
        return route(view, target, request.isForMainFrame)
    }

    @Deprecated("Kept for completeness; the WebResourceRequest form is what runs on API 30+.")
    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
        return route(view, url ?: return true, true)
    }

    /**
     * @return true when the WebView must not proceed with the load as given.
     */
    private fun route(view: WebView, target: String, isMainFrame: Boolean): Boolean {
        // Subresources are the blocklist's business, not the router's.
        if (!isMainFrame) return false

        val verdict = UrlRules.decide(target, view.url)
        return when (verdict.decision) {
            UrlRules.Decision.LOAD -> false

            UrlRules.Decision.REWRITE -> {
                verdict.url?.let { host.loadInApp(it) }
                true
            }

            UrlRules.Decision.EXTERNAL -> {
                host.openExternally(target)
                true
            }

            // Nothing is dispatched. This is the path an "open in the app" link
            // takes, and the whole point is that it ends here.
            UrlRules.Decision.REFUSE -> {
                host.onRefused(target, verdict.reason)
                true
            }
        }
    }

    /**
     * A site can move itself with `pushState` without ever consulting
     * shouldOverrideUrlLoading. SupplyChain's allowlist had to be enforced here
     * too for exactly that reason, and the same applies here: a redirect that
     * lands on a non-canonical Reddit host must be corrected on arrival.
     */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        val current = url ?: return
        val verdict = UrlRules.decide(current, null)
        if (verdict.decision == UrlRules.Decision.REWRITE && verdict.url != null) {
            // Guard against a rewrite that produces the same URL, which would
            // spin. UrlRules only returns REWRITE when the strings differ, but
            // this costs nothing and a loop here would be miserable to debug.
            if (verdict.url != current) host.loadInApp(verdict.url)
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        host.onPageStarted(view, url)

        // What the app refused belongs to the page in front of the reader, not
        // to the one before it.
        RefusalLog.enabled = Prefs.diagnostics(view.context)
        RefusalLog.clear()

        // Fallback path for a WebView without DOCUMENT_START_SCRIPT. Later than
        // document-start, so an overlay can flash before it goes, but the
        // alternative is an overlay that stays.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val hostName = url?.let { UrlRules.hostOf(it) }
            if (UrlRules.isRedditPageHost(hostName)) {
                for (script in WebViewSetup.startupScriptsFor(view.context, view)) {
                    Safely.run { view.evaluateJavascript(script, null) }
                }
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        host.onPageFinished(view, url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            host.onMainFrameError(view, request.url?.toString(), error?.description?.toString())
        }
    }

    /**
     * Never proceed past a certificate problem. `usesCleartextTraffic` is
     * false, the app talks to exactly one site, and there is no scenario where
     * a reader should click through a bad certificate.
     */
    override fun onReceivedSslError(
        view: WebView,
        handler: android.webkit.SslErrorHandler,
        error: android.net.http.SslError,
    ) {
        handler.cancel()
        host.onMainFrameError(view, error.url, "TLS")
    }

    companion object {
        /** Exposed for the settings screen's "open in browser" item. */
        fun externalUri(url: String): Uri = Uri.parse(url)
    }
}
