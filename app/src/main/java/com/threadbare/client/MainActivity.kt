package com.threadbare.client

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.threadbare.client.shell.Frame
import com.threadbare.client.shell.TopBar
import com.threadbare.client.ui.SettingsActivity
import com.threadbare.client.util.Prefs
import com.threadbare.client.util.Safely
import com.threadbare.client.util.SavedItem
import com.threadbare.client.util.SavedKind
import com.threadbare.client.util.SavedStore
import com.threadbare.client.util.Sites
import com.threadbare.client.ui.SavedActivity
import com.threadbare.client.web.AppChromeClient
import com.threadbare.client.web.BrowserLauncher
import com.threadbare.client.web.PrivateTabs
import com.threadbare.client.web.RedditPath
import com.threadbare.client.web.AppWebViewClient
import com.threadbare.client.web.PrivacySignals
import com.threadbare.client.web.UrlRules
import com.threadbare.client.web.WebHost
import com.threadbare.client.web.WebViewSetup

class MainActivity : AppCompatActivity(), WebHost, TopBar.Callbacks {

    private lateinit var web: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var topBar: TopBar
    private lateinit var frame: Frame

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // Requirement 2 of the frame rule: before setContentView, always.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Prefs.materialiseDefaults(this)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webView)
        refresh = findViewById(R.id.swipeRefresh)
        progress = findViewById(R.id.progress)
        topBar = TopBar(this, findViewById(R.id.root), this)

        frame = Frame(
            activity = this,
            barWrapper = topBar.wrapper,
            barRow = topBar.row,
            content = findViewById(R.id.contentHost),
        )
        frame.install()

        // Seed the preference cookies before the first load, so the very first
        // subreddit the user opens is already past the wall.
        WebViewSetup.seedCookies(this)

        WebViewSetup.applyUserAgent(web, this)
        WebViewSetup.configure(web, this)
        WebViewSetup.installDocumentStartScripts(web, this)

        web.webViewClient = AppWebViewClient(this)
        web.webChromeClient = AppChromeClient(this)

        refresh.setOnRefreshListener { web.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBackOrExit()
        })

        applyThemeColours()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            loadInApp(intentUrl() ?: Prefs.startPage(this))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentUrl()?.let { loadInApp(it) }
    }

    private fun intentUrl(): String? {
        val data: Uri = intent?.data ?: return null
        val raw = data.toString()
        val verdict = UrlRules.decide(raw, null)
        return when (verdict.decision) {
            UrlRules.Decision.LOAD -> raw
            UrlRules.Decision.REWRITE -> verdict.url
            else -> null
        }
    }

    // ------------------------------------------------------------- theming

    /**
     * Resolved at runtime, from onCreate, onResume and onConfigurationChanged.
     *
     * `uiMode` is in this activity's configChanges, so it is never recreated on
     * a day/night switch — anything resolved once at inflate would stay the
     * daytime colour until the next cold start. frame-rule.md calls this out
     * specifically.
     */
    private fun applyThemeColours() {
        val bar = ContextCompat.getColor(this, R.color.top_bar)
        frame.applyBarColour(bar)
        WebViewSetup.applyBackdrop(web, this)
        // The refresh spinner's disc is white by default — another of the seams
        // either side of the frame.
        refresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.surface)
        )
        refresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemeColours()
        frame.requestInsets()
    }

    override fun onResume() {
        super.onResume()
        Safely.run { web.onResume() }
        applyThemeColours()
        // A settings change (suppressor off, desktop mode on) takes effect on
        // the next load rather than needing a cold start.
        WebViewSetup.invalidateCssCache()
    }

    // --------------------------------------------------------- WebHost impl

    /**
     * The single place a URL enters the WebView, and it canonicalises.
     *
     * Every other route into the app — a tapped link, an incoming intent — was
     * already going through UrlRules. The app's *own* loads were not, which is
     * how a stale `start_page` in SharedPreferences put the user on old.reddit
     * and straight into a login wall on launch. Normalising here makes that
     * class of bug impossible rather than fixing the one instance of it.
     *
     * `canonical` returns null for anything that is not a Reddit page URL — a
     * media host, a data: URL — and those load unchanged.
     */
    override fun loadInApp(url: String) {
        val target = UrlRules.canonical(url) ?: url
        Safely.run { web.loadUrl(target, PrivacySignals.headers) }
    }

    /**
     * Everything that is not Reddit leaves the app.
     *
     * The private-tab path is deliberately unforgiving about honesty: if the
     * user asked for private and no installed browser will honour it, they are
     * told, rather than being handed an ordinary tab that looks like a private
     * one. `web/PrivateTabs.kt` has the reasoning and the evidence.
     */
    override fun openExternally(url: String) {
        if (!Prefs.openExternalInBrowser(this)) {
            toast(getString(R.string.external_blocked))
            return
        }
        when (Prefs.privateTabMode(this)) {
            Prefs.PRIVATE_NEVER -> openNormally(url)
            Prefs.PRIVATE_ALWAYS -> openPrivatelyOrExplain(url)
            else -> askHowToOpen(url)
        }
    }

    private fun openNormally(url: String) {
        if (!BrowserLauncher.openNormally(this, url)) toast(getString(R.string.no_browser))
    }

    /**
     * "Always": go private if a browser will honour it; otherwise put the choice
     * in front of the user rather than quietly opening a normal tab. The prompt
     * says why, and "open in browser anyway" is on it.
     */
    private fun openPrivatelyOrExplain(url: String) {
        val recipe = BrowserLauncher.privateOption(this)
        if (recipe != null && BrowserLauncher.openPrivately(this, url, recipe)) return
        showExternalDialog(url, recipe = null, note = getString(R.string.external_no_private))
    }

    private fun askHowToOpen(url: String) {
        val recipe = BrowserLauncher.privateOption(this)
        showExternalDialog(
            url,
            recipe,
            note = if (recipe == null) getString(R.string.external_no_private) else null,
        )
    }

    /**
     * A custom view rather than AlertDialog's setMessage + setItems: AlertDialog
     * cannot show both, and with a message set it silently drops the list. The
     * first version of this prompt therefore explained that no private browser
     * was available and offered nothing else — the bug reported from the device.
     * Every option here is a real button.
     */
    private fun showExternalDialog(url: String, recipe: PrivateTabs.Recipe?, note: String?) {
        val view = layoutInflater.inflate(R.layout.dialog_external, null, false)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.external_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        view.findViewById<android.widget.TextView>(R.id.externalUrl).text = url
        view.findViewById<android.widget.TextView>(R.id.externalNote).apply {
            if (note != null) { text = note; visibility = View.VISIBLE }
        }
        view.findViewById<android.widget.Button>(R.id.externalOpen).apply {
            setText(if (note != null) R.string.external_open_anyway else R.string.external_open)
            setOnClickListener { dialog.dismiss(); openNormally(url) }
        }
        view.findViewById<android.widget.Button>(R.id.externalPrivate).apply {
            if (recipe != null) {
                text = getString(R.string.external_private, recipe.label)
                visibility = View.VISIBLE
                setOnClickListener {
                    dialog.dismiss()
                    if (!BrowserLauncher.openPrivately(this@MainActivity, url, recipe)) {
                        showExternalDialog(url, null, getString(R.string.external_private_unavailable))
                    }
                }
            }
        }
        view.findViewById<android.widget.Button>(R.id.externalCopy).setOnClickListener {
            dialog.dismiss(); copyLink(url)
        }
        dialog.show()
    }

    private fun copyLink(url: String) {
        if (BrowserLauncher.copyToClipboard(this, url)) {
            toast(getString(R.string.link_copied))
        }
    }

    /**
     * An app link, an app-store link, or a scheme the app does not speak.
     *
     * Nothing is dispatched anywhere. This is where "open in the app" ends, and
     * the toast is deliberate: silence here would look like a dead tap.
     */
    override fun onRefused(url: String, reason: String) {
        when (reason) {
            "app link", "app store" -> toast(getString(R.string.app_link_refused))
            "ad click" -> toast(getString(R.string.ad_link_refused))
        }
    }

    override fun onPageStarted(view: WebView, url: String?) {
        progress.visibility = View.VISIBLE
        topBar.setLabel(url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        progress.visibility = View.GONE
        refresh.isRefreshing = false
        topBar.setLabel(url)
    }

    override fun onMainFrameError(view: WebView, url: String?, description: String?) {
        progress.visibility = View.GONE
        refresh.isRefreshing = false
        Safely.run {
            view.loadDataWithBaseURL(
                null,
                com.threadbare.client.web.ErrorPage.html(this, url, description),
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    override fun onProgress(view: WebView, progressValue: Int) {
        progress.progress = progressValue
        if (progressValue >= 100) progress.visibility = View.GONE
    }

    // ------------------------------------------------------- TopBar impl

    override fun onBack() = goBackOrExit()

    override fun onReload() {
        Safely.run { web.reload() }
    }

    override fun onHome() = loadInApp(Prefs.startPage(this))

    override fun onShare() {
        val url = currentUrl() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            // The canonical www URL, with Reddit's attribution parameters
            // already stripped by UrlRules — sharing from this app does not
            // carry a tracking tail.
            putExtra(Intent.EXTRA_TEXT, UrlRules.canonical(url) ?: url)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.action_share)))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_share_target))
        }
    }

    override fun onOpenExternally() {
        currentUrl()?.let { openExternally(it) }
    }

    /**
     * The subreddit box. The name has already been through
     * `RedditPath.normaliseName`, so the only thing that can arrive here is a
     * valid community name — the URL is built by the app, never supplied.
     *
     * Whether the community exists is left to Reddit to answer. Checking first
     * would mean the app making a request of its own, and "no request the app
     * makes on its own" is a property worth more than a slightly nicer error.
     */
    override fun onGoToSubreddit(name: String) {
        RedditPath.subredditUrl(name)?.let { loadInApp(it) }
    }

    override fun onBadSubreddit(typed: String) {
        toast(getString(R.string.subreddit_bad))
    }

    override fun onCopyLink() {
        currentUrl()?.let { copyLink(UrlRules.canonical(it) ?: it) }
    }

    /**
     * Bookmarks are the app's own, not Reddit's — Reddit's save needs an
     * account, which is the thing this app exists to do without.
     */
    override fun onToggleSaved(kind: SavedKind) {
        val url = currentUrl() ?: return
        val target = when (kind) {
            SavedKind.SUBREDDIT -> RedditPath.subredditOf(url)?.let { RedditPath.subredditUrl(it) }
            SavedKind.POST -> RedditPath.postPermalink(url)
        } ?: return

        if (SavedStore.contains(this, kind, target)) {
            SavedStore.remove(this, kind, target)
            toast(getString(R.string.saved_removed))
            return
        }

        val title = when (kind) {
            SavedKind.SUBREDDIT -> "r/" + (RedditPath.subredditOf(url) ?: "")
            // Reddit's page title is the post's title with " : r/sub" appended;
            // the slug is the fallback when the title has not loaded yet.
            SavedKind.POST -> RedditPath.cleanPageTitle(currentTitle(), target)
        }
        SavedStore.add(this, SavedItem(kind, title, target, System.currentTimeMillis()))
        toast(getString(R.string.saved_added))
    }

    override fun onShowSaved(kind: SavedKind) {
        startActivity(SavedActivity.intent(this, kind))
    }

    override fun currentTitle(): String? = web.title

    override fun onSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * Signing in and staying anonymous are mutually exclusive, so say so once
     * rather than letting the ephemeral session silently log the user out on
     * every exit and look like a bug.
     */
    override fun onSignIn() {
        if (!Prefs.ephemeralSession(this)) {
            loadInApp(Sites.LOGIN)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sign_in_title)
            .setMessage(R.string.sign_in_message)
            .setPositiveButton(R.string.sign_in_keep_session) { _, _ ->
                Prefs.of(this).edit().putBoolean(Prefs.KEY_EPHEMERAL, false).apply()
                loadInApp(Sites.LOGIN)
            }
            .setNeutralButton(R.string.sign_in_anyway) { _, _ -> loadInApp(Sites.LOGIN) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onClearSession() {
        WebViewSetup.clearSession(this, web)
        toast(getString(R.string.session_cleared))
        loadInApp(Prefs.startPage(this))
    }

    override fun currentUrl(): String? = web.url

    // ------------------------------------------------------------ back/exit

    /**
     * One implementation for both the button and the system gesture, so the two
     * can never disagree. Walks WebView history first — which includes any
     * in-page navigation — then leaves.
     */
    fun goBackOrExit() {
        if (web.canGoBack()) {
            web.goBack()
        } else {
            finish()
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Safely.run { web.saveState(outState) }
    }

    override fun onPause() {
        super.onPause()
        Safely.run { web.onPause() }
    }

    override fun onDestroy() {
        // The default. Everything this session touched goes with it.
        if (Prefs.ephemeralSession(this) && isFinishing) {
            WebViewSetup.clearSession(this, web)
        }
        Safely.run { web.destroy() }
        super.onDestroy()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
