package com.threadbare.client.privacy

/**
 * Layer 1 of the xpromo defence: stop the overlay being fetched at all.
 *
 * ## How the overlay actually arrives (from a captured DOM, not a guess)
 *
 * The page carries an empty
 * `<faceplate-partial name="ActivateExperience_…" loading="programmatic"
 *  src="/svc/shreddit/partial/<id>/activate-experience?…">`.
 * After the trigger fires — thirty seconds on a feed, immediately on an
 * adult-flagged community — Reddit fetches that partial and slots its response
 * into the page. The response *is* the overlay: a `<configured-xpromo-modal>`
 * wrapping an `<rpl-bottom-sheet blocking>` or `<rpl-dialog blocking>`.
 *
 * So the single most effective thing this app can do is refuse that one
 * request. No response, no element, no modal dialog, nothing to fight in the
 * DOM. [PARTIAL_PATTERNS] does that. The component chunks themselves are
 * opaque hashes (`concat:Bexg8Kzcx2,…`), so the chunk-name rule below them is
 * a harmless no-op on the current build and is kept only for older markup.
 *
 * **This is the layer that justifies building an app instead of installing a
 * userscript.** A userscript can only delete an overlay after the page has
 * constructed it; a WebView owns `shouldInterceptRequest` and can refuse the
 * code that constructs it. An element whose implementation never arrives cannot
 * lock the scroll, cannot render a prompt into a shadow root, and cannot come
 * back on the next route change.
 *
 * Reddit lazy-loads these through `<shreddit-async-loader>` elements carrying
 * `paint-group="xpromo"` and a `bundlename` — `app_selector`, `top_button`,
 * `bottom_bar_xpromo`, `nsfw_blocking_modal`. The loader fetches a chunk per
 * bundle, and Reddit's build has historically put the bundle name in the chunk
 * filename.
 *
 * **The honest caveat, because it decides how this is written.** No Reddit URL
 * was observable while this was built, so the exact chunk filenames are not
 * known. The rule is therefore deliberately conservative: a request is refused
 * only when its path contains one of the tokens below, every one of which names
 * the promotion machinery and nothing else. If Reddit's chunks turn out to be
 * opaque hashes with no bundle name in them, every pattern here simply never
 * matches and this layer is a no-op — layers 2 and 3 carry the defence and
 * nothing breaks. That asymmetry is the whole design: this layer can only help.
 *
 * What must never appear in [PATTERNS] is a generic token (`modal`, `sheet`,
 * `banner`, `promo` on its own, a hash fragment). Blocking a shared chunk would
 * take out parts of the site with no visible cause, which is a far worse
 * failure than an overlay that still shows.
 *
 * Free of `android.*` so the matching is exercised on the JVM.
 */
object XpromoBlock {

    /**
     * Server partials that deliver a promotion "experience". Matched against
     * the path on Reddit's own hosts, any resource type.
     *
     * `activate-experience` is Reddit's own name for the endpoint that returns
     * whichever nag the experiment framework has chosen for this visitor. It
     * has no other job on a page a logged-out reader is looking at.
     */
    private val PARTIAL_PATTERNS = listOf(
        "/activate-experience",
    )

    /**
     * Path substrings that identify a promotion bundle. Lowercased before
     * comparison. Each one names the app-promotion system explicitly.
     */
    private val PATTERNS = listOf(
        "xpromo",
        "nsfw_blocking",
        "nsfw-blocking",
        "bottom_bar_xpromo",
        "app_selector",
        "smartbanner",
        "smart_banner",
    )

    /**
     * Tokens that must never be treated as a promotion bundle even if one of
     * the patterns appears alongside them. A guard against a future edit
     * widening [PATTERNS] into something that breaks the site.
     */
    private val NEVER = listOf(
        "shreddit-app",
        "polyfill",
        "runtime",
        "vendor",
    )

    /** Hosts the chunks are served from. Nothing off these is considered. */
    private val BUNDLE_HOSTS = setOf(
        "www.redditstatic.com",
        "redditstatic.com",
        "www.reddit.com",
        "sh.reddit.com",
        "reddit.com",
    )

    data class Decision(val blocked: Boolean, val pattern: String = "")

    /**
     * @param host request host, any case
     * @param path request path, any case
     */
    fun decide(host: String?, path: String?): Decision {
        val h = host?.lowercase()?.trimEnd('.').orEmpty()
        val p = path?.lowercase().orEmpty()
        if (h.isEmpty() || p.isEmpty()) return Decision(false)
        if (h !in BUNDLE_HOSTS) return Decision(false)

        // The experience partial: the request whose response is the overlay.
        if (p.contains("/svc/shreddit/partial/")) {
            val hit = PARTIAL_PATTERNS.firstOrNull { p.contains(it) }
            if (hit != null) return Decision(true, hit)
            return Decision(false)
        }

        // Otherwise only script chunks. Refusing a document or an image by
        // accident would be a confusing failure; refusing a script is the point.
        if (!p.endsWith(".js") && !p.contains(".js?")) return Decision(false)

        if (NEVER.any { p.contains(it) }) return Decision(false)

        val hit = PATTERNS.firstOrNull { p.contains(it) } ?: return Decision(false)
        return Decision(true, hit)
    }

    /** Exposed for the verification suite. */
    fun patterns(): List<String> = PATTERNS

    fun partialPatterns(): List<String> = PARTIAL_PATTERNS

    fun bundleHosts(): Set<String> = BUNDLE_HOSTS
}
