package com.threadbare.client.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.threadbare.client.util.Prefs
import com.threadbare.client.util.Safely

/**
 * WebView configuration, the UA reroute, cookie seeding and the document-start
 * scripts. Everything that has to happen before a page loads.
 */
object WebViewSetup {

    private const val SUPPRESS_ASSET = "xpromo-suppress.css"

    /** Origins the document-start scripts are scoped to. Nothing else. */
    private val SCRIPT_ORIGINS = setOf(
        "https://www.reddit.com",
        "https://sh.reddit.com",
    )

    @Volatile
    private var cachedCss: String? = null

    // ------------------------------------------------------------------ UA

    /**
     * Mobile by default: the device's own WebView user agent, left exactly as
     * it is.
     *
     * Leaving it alone is the right default twice over. It gets Reddit's native
     * mobile layout, which is the whole reason for wrapping www rather than
     * restyling a desktop page — and an untouched UA is less distinctive than
     * any string this app could invent.
     *
     * Desktop is the opt-in escape hatch; see [Prefs.desktopMode].
     */
    fun applyUserAgent(view: WebView, context: Context) {
        if (!Prefs.desktopMode(context)) {
            view.settings.userAgentString = null // the platform default
            return
        }
        val real = Safely.call({ WebSettings.getDefaultUserAgent(context) }, null)
            ?: view.settings.userAgentString
        val major = SiteScripts.majorVersionOf(real)
        view.settings.userAgentString = SiteScripts.desktopUserAgent(major)
    }

    fun chromeMajor(context: Context): String {
        val real = Safely.call({ WebSettings.getDefaultUserAgent(context) }, null)
        return SiteScripts.majorVersionOf(real)
    }

    // ------------------------------------------------------------ settings

    fun configure(view: WebView, context: Context) {
        val s = view.settings

        s.javaScriptEnabled = true
        s.domStorageEnabled = true

        // Reddit needs neither, and both are attack surface.
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.databaseEnabled = false

        // A reader does not need to open windows at itself.
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false

        // Never ask for location; WebChromeClient refuses anyway.
        s.setGeolocationEnabled(false)

        s.mediaPlaybackRequiresUserGesture = true
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Reddit's mobile site carries its own viewport meta and is
        // responsive, so the app does not impose one. Pinch-zoom stays
        // available because a reader sometimes needs it on a screenshot.
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)

        // Left at the platform default so the system font-size setting is
        // honoured rather than overridden.
        s.textZoom = 100

        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.saveFormData = false

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // Reddit has its own dark theme; this covers the seams around it.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, true)
        }

        applyBackdrop(view, context)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, false)
    }

    /**
     * A white WebView backdrop flashes on every load inside a dark frame.
     * frame-rule.md lists this as one of the seams either side of the frame.
     *
     * Resolved at runtime rather than at inflate, because MainActivity declares
     * `uiMode` in configChanges and so is never recreated on a day/night
     * switch — anything resolved once would stay the daytime colour until the
     * next cold start. Called again from onResume and onConfigurationChanged.
     */
    fun applyBackdrop(view: WebView, context: Context) {
        val colour = Safely.call(
            { androidx.core.content.ContextCompat.getColor(context, com.threadbare.client.R.color.page_backdrop) },
            null,
        ) ?: return
        view.setBackgroundColor(colour)
    }

    // ----------------------------------------------------------- the seeds

    /**
     * Write the preference cookies. Cheap, idempotent, and called on every cold
     * start and immediately after any clear, so neither an expiry nor the app's
     * own clear-on-exit can quietly bring a wall back on the next run.
     */
    fun seedCookies(context: Context) {
        if (!Prefs.bypassAgeGate(context)) return
        val cm = CookieManager.getInstance()
        for (seed in CookieSeed.seeds()) {
            Safely.run { cm.setCookie(CookieSeed.SEED_URL, seed.header) }
        }
        Safely.run { cm.flush() }
    }

    /** Clear everything this session left behind, then re-seed. */
    fun clearSession(context: Context, view: WebView?) {
        val cm = CookieManager.getInstance()
        Safely.run { cm.removeAllCookies(null) }
        Safely.run { cm.flush() }
        Safely.run { WebStorage.getInstance().deleteAllData() }
        view?.let {
            Safely.run { it.clearCache(true) }
            Safely.run { it.clearHistory() }
            Safely.run { it.clearFormData() }
        }
        seedCookies(context)
    }

    // -------------------------------------------------- document-start code

    /**
     * Install the document-start scripts.
     *
     * `DOCUMENT_START_SCRIPT` runs before any of the page's own script and
     * before first paint. That is what stops the takeover appearing at all
     * rather than appearing and then vanishing, and it is what makes the
     * client-hint shim effective in desktop mode.
     *
     * Where the feature is missing the caller falls back to [startupScriptsFor]
     * in `onPageStarted`, which is later — an overlay can flash — but still
     * works. Layer 1 is unaffected either way, since it is enforced on the
     * request rather than in the page.
     */
    fun installDocumentStartScripts(view: WebView, context: Context) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val origins = SCRIPT_ORIGINS.toSet()
        for (script in startupScriptsFor(context, view)) {
            Safely.run { WebViewCompat.addDocumentStartJavaScript(view, script, origins) }
        }
    }

    /**
     * The scripts, in the order they must run: identity first, then privacy
     * signals, then the suppression stylesheet, then the observer that depends
     * on it.
     */
    fun startupScriptsFor(context: Context, view: WebView?): List<String> {
        val out = ArrayList<String>(4)
        if (Prefs.desktopMode(context)) {
            val major = view?.let { SiteScripts.majorVersionOf(it.settings.userAgentString) }
                ?: chromeMajor(context)
            // Client hints are not derived from the UA string, so a desktop UA
            // without this shim still reads as a phone. SupplyChain's lesson.
            out += SiteScripts.desktopShim(major)
        }
        out += PrivacySignals.script
        if (Prefs.suppressXpromo(context)) {
            out += SiteScripts.suppressorStyle(suppressCss(context))
            out += SiteScripts.XPROMO_SUPPRESSOR
        }
        return out
    }

    fun suppressCss(context: Context): String {
        cachedCss?.let { return it }
        val css = Safely.call({
            context.applicationContext.assets.open(SUPPRESS_ASSET).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        }, "") ?: ""
        cachedCss = css
        return css
    }

    /** Drop the cached stylesheet so a settings change takes effect on reload. */
    fun invalidateCssCache() {
        cachedCss = null
    }
}
