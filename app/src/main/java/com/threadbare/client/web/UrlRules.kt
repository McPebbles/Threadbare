package com.threadbare.client.web

/**
 * Every routing decision the app makes, in one place.
 *
 * Free of `android.*` imports so the whole thing can be exercised on a plain
 * JVM.
 *
 * **Why www and not old.reddit.** The first version of this app pinned itself
 * to `old.reddit.com`, on the reasoning that Reddit never shipped its app-promo
 * machinery into the legacy site. That was true and is now irrelevant: Reddit
 * began requiring an account to read old.reddit in July 2026, which makes it
 * useless to a client whose entire purpose is reading without one.
 * `www.reddit.com` is explicitly unaffected by that change and is the only
 * surface that still serves a logged-out reader, so it is the surface this app
 * wraps. The app-promo and 18+ overlays that come with it are dealt with by the
 * three-layer defence in `privacy/XpromoBlock.kt`, the suppression stylesheet
 * and `SiteScripts.XPROMO_SUPPRESSOR` — not here.
 */
object UrlRules {

    const val CANONICAL_HOST = "www.reddit.com"
    const val HOME = "https://www.reddit.com/"

    /** What the caller should do with a URL. */
    enum class Decision {
        /** Load it in the WebView exactly as given. */
        LOAD,

        /** Load [Verdict.url] instead — same destination, corrected. */
        REWRITE,

        /** Hand it to the system (a browser, the mail app). */
        EXTERNAL,

        /** Do nothing at all. Nothing is dispatched anywhere. */
        REFUSE,
    }

    data class Verdict(
        val decision: Decision,
        val url: String? = null,
        val reason: String = "",
    )

    // ---------------------------------------------------------------- hosts

    /**
     * Reddit hosts that serve pages. Every one is normalised to www.
     *
     * `old.` is in this list specifically so that an old.reddit link — of which
     * there are a decade's worth in existing posts and bookmarks — lands on a
     * page the reader can actually see, instead of on a login wall.
     */
    private val PAGE_HOSTS = setOf(
        "reddit.com",
        "www.reddit.com",
        // Reddit's mobile site routes nearly every tap through this host — a
        // redirector that tries the native app first and falls back to web.
        // Two hundred of the links on a single feed page go through it. The
        // path is the ordinary www path, so it normalises like any other host.
        "applink.reddit.com",
        "old.reddit.com",
        "new.reddit.com",
        "sh.reddit.com",
        "m.reddit.com",
        "i.reddit.com",
        "np.reddit.com",
        "amp.reddit.com",
        "ssl.reddit.com",
        "pay.reddit.com",
        "en.reddit.com",
    )

    /**
     * Media and asset hosts. These are loaded in-app untouched — rewriting them
     * to www.reddit.com would break every image and video on the site.
     *
     * Checked *before* the `redd.it` short-link rule, because `i.redd.it` and
     * `v.redd.it` are subdomains of it and must not be treated as short links.
     */
    private val MEDIA_HOSTS = setOf(
        "i.redd.it",
        "v.redd.it",
        "preview.redd.it",
        "external-preview.redd.it",
        "a.thumbs.redditmedia.com",
        "b.thumbs.redditmedia.com",
        "thumbs.redditmedia.com",
        "styles.redditmedia.com",
        "emoji.redditmedia.com",
        "www.redditmedia.com",
        "redditmedia.com",
        "i.redditmedia.com",
        "g.redditmedia.com",
        "www.redditstatic.com",
        "redditstatic.com",
        "reddit-uploaded-media.s3-accelerate.amazonaws.com",
        "reddit-uploaded-video.s3-accelerate.amazonaws.com",
    )

    /** Bare short-link hosts. `redd.it/abc123` is a post id. */
    private val SHORTLINK_HOSTS = setOf("redd.it", "www.redd.it")

    /** Reddit's outbound-click redirector. Unwrapped, never followed. */
    private const val OUTBOUND_HOST = "out.reddit.com"

    /** Reddit's ad-click redirector. Opaque token, destination unknowable. */
    private const val AD_CLICK_HOST = "alb.reddit.com"

    /**
     * AppsFlyer OneLink — the app-store bounce behind every "open in app"
     * control. The web destination rides along as `deep_link_value`, so a
     * Reddit destination can be unwrapped and loaded in place; anything else
     * is the install funnel and is refused.
     */
    private val ONELINK_DOMAINS = setOf("onelink.me", "app.link", "bnc.lt")

    /**
     * Schemes that would hand the user to a native app. Every one is refused
     * outright — not dispatched, not offered, not opened in a browser. This is
     * the second half of requirement 1: even if some "open in app" control
     * survived on a page, there is no path from it into the Reddit app.
     */
    private val APP_SCHEMES = setOf(
        "reddit", "intent", "android-app", "market", "amzn", "samsungapps",
        "fb", "twitter", "snapchat", "instagram", "vnd.youtube",
    )

    /** Schemes the system should handle. */
    private val SYSTEM_SCHEMES = setOf("mailto", "tel", "sms", "smsto")

    /** App-store web pages. Same treatment as the app schemes. */
    private val STORE_HOSTS = setOf(
        "play.google.com",
        "market.android.com",
        "apps.apple.com",
        "itunes.apple.com",
    )

    /**
     * Query parameters stripped from every Reddit URL.
     *
     * The `deep_link` / `branch` / `correlation_id` family is not incidental
     * cruft — those parameters exist to hand the visitor to the native app and
     * to attribute them when it opens. A denylist rather than an allowlist,
     * because Reddit takes a long tail of functional parameters (`sort`,
     * `context`, `depth`, `after`, `count`, `q`, `t`, `restrict_sr`, `dest`)
     * and silently dropping one of those would break navigation.
     */
    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_name", "share_id", "correlation_id", "ref", "ref_source",
        "ref_campaign", "rdt", "rdt_cid", "post_fullname", "chainedPosts",
        "feature", "\$deep_link", "\$android_deeplink_path", "\$ios_deeplink_path",
        "_branch_match_id", "_branch_referrer", "sh_source", "sh_medium",
        // The applink redirector's own attribution: a logged-out visitor id and
        // the "app first" source tag.
        "mweb_loid", "mweb_loid_enc", "mweb_loid_created",
    )

    // ------------------------------------------------------------ decisions

    /**
     * The single entry point. [currentUrl] is only used for logging context;
     * the decision never depends on where you happen to be.
     */
    fun decide(raw: String?, currentUrl: String? = null): Verdict {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return Verdict(Decision.REFUSE, reason = "empty")

        val scheme = schemeOf(url) ?: return Verdict(Decision.REFUSE, reason = "no scheme")

        when (scheme) {
            "http", "https" -> Unit
            "about" -> return if (url == "about:blank") {
                Verdict(Decision.LOAD, url, "blank")
            } else {
                Verdict(Decision.REFUSE, reason = "about:")
            }
            // Video and generated images use these. They are same-document by
            // construction and cannot navigate anywhere.
            "blob", "data" -> return Verdict(Decision.LOAD, url, "inline")
            "javascript" -> return Verdict(Decision.REFUSE, reason = "javascript:")
            in APP_SCHEMES -> return Verdict(Decision.REFUSE, reason = "app link")
            in SYSTEM_SCHEMES -> return Verdict(Decision.EXTERNAL, url, "system scheme")
            else -> return Verdict(Decision.REFUSE, reason = "unknown scheme")
        }

        val host = hostOf(url) ?: return Verdict(Decision.REFUSE, reason = "no host")

        if (host in STORE_HOSTS) {
            return Verdict(Decision.REFUSE, reason = "app store")
        }

        // Reddit's click redirector. Unwrap it rather than following it: the
        // whole point of the host is to log the click.
        if (host == OUTBOUND_HOST) {
            val target = queryValue(url, "url")
            return if (target != null && schemeOf(target) in setOf("http", "https")) {
                Verdict(Decision.EXTERNAL, target, "outbound unwrapped")
            } else {
                Verdict(Decision.REFUSE, reason = "outbound with no target")
            }
        }

        if (host == AD_CLICK_HOST) {
            return Verdict(Decision.REFUSE, reason = "ad click")
        }

        // The app-store bounce. Unwrap a Reddit destination; refuse the rest.
        if (ONELINK_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            val inner = queryValue(url, "deep_link_value")
            val innerHost = inner?.let { hostOf(it) }
            return if (inner != null && innerHost != null && innerHost in PAGE_HOSTS) {
                val canonical = canonical(inner) ?: inner
                Verdict(Decision.REWRITE, canonical, "onelink unwrapped")
            } else {
                Verdict(Decision.REFUSE, reason = "app store")
            }
        }

        if (host in MEDIA_HOSTS) {
            val https = toHttps(url)
            return if (https == url) {
                Verdict(Decision.LOAD, url, "media")
            } else {
                Verdict(Decision.REWRITE, https, "media over http")
            }
        }

        if (host in SHORTLINK_HOSTS) {
            val id = firstPathSegment(url)
            return if (id.isNullOrEmpty()) {
                Verdict(Decision.REWRITE, HOME, "bare short link")
            } else {
                Verdict(Decision.REWRITE, "https://$CANONICAL_HOST/comments/$id", "short link")
            }
        }

        if (host in PAGE_HOSTS) {
            val canonical = canonical(url) ?: return Verdict(Decision.REFUSE, reason = "unparseable")
            return if (canonical == url) {
                Verdict(Decision.LOAD, url, "already canonical")
            } else {
                Verdict(Decision.REWRITE, canonical, "normalised to www")
            }
        }

        // Anything else under reddit.com stays in the app. Subreddit vanity
        // subdomains (`worldnews.reddit.com`), `developers.`, and whatever
        // Reddit adds next all redirect to www on their own; loading them as
        // given and letting doUpdateVisitedHistory canonicalise on arrival is
        // safer than guessing at each one. The user's rule is the simple one:
        // reddit.com is the app, everything else is the browser.
        if (host == "reddit.com" || host.endsWith(".reddit.com")) {
            val https = toHttps(url)
            return if (https == url) {
                Verdict(Decision.LOAD, url, "reddit subdomain")
            } else {
                Verdict(Decision.REWRITE, https, "reddit subdomain over http")
            }
        }

        return Verdict(Decision.EXTERNAL, url, "off-site")
    }

    /** True when this URL belongs in the WebView at all. */
    fun isInApp(raw: String?): Boolean = when (decide(raw).decision) {
        Decision.LOAD, Decision.REWRITE -> true
        else -> false
    }

    /**
     * Rewrite a Reddit page URL to its canonical old.reddit form: correct host,
     * https, tracking parameters gone.
     *
     * Returns null when the input is not a Reddit page URL at all.
     */
    fun canonical(raw: String): String? {
        val host = hostOf(raw) ?: return null
        if (host !in PAGE_HOSTS) return null

        val afterScheme = raw.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val afterAuthority = afterScheme.substringAfter('/', "")
        val fragment = afterAuthority.substringAfter('#', "")
        val noFragment = afterAuthority.substringBefore('#')
        var path = "/" + noFragment.substringBefore('?')
        val query = noFragment.substringAfter('?', "")

        // Everything lands on www. The share-link special case the previous
        // version carried is gone with old.reddit: `/r/x/s/<id>` is a native www
        // URL and needs no detour, and `.compact`/`.mobile` were legacy-site
        // suffixes that no longer exist anywhere.
        val targetHost = CANONICAL_HOST

        var cleaned = stripTracking(query)

        // Search hides adult-flagged communities and posts unless asked. Since
        // the whole point of the app is that the 18+ flag should not hide
        // privacy and security communities, ask.
        if (path.trimEnd('/').endsWith("/search") || path.trimEnd('/') == "/search") {
            if (queryValue("?$cleaned", "include_over_18") == null) {
                cleaned = if (cleaned.isEmpty()) "include_over_18=on" else "$cleaned&include_over_18=on"
            }
        }

        return buildString {
            append("https://").append(targetHost).append(path)
            if (cleaned.isNotEmpty()) append('?').append(cleaned)
            if (fragment.isNotEmpty()) append('#').append(fragment)
        }
    }

    /** Drop every parameter in [TRACKING_PARAMS], preserving the rest in order. */
    fun stripTracking(query: String): String {
        if (query.isEmpty()) return ""
        return query.split('&')
            .filter { pair ->
                if (pair.isEmpty()) return@filter false
                val name = percentDecode(pair.substringBefore('='))
                name.lowercase() !in TRACKING_PARAMS_LOWER
            }
            .joinToString("&")
    }

    private val TRACKING_PARAMS_LOWER = TRACKING_PARAMS.map { it.lowercase() }.toSet()

    // -------------------------------------------------------------- parsing

    /**
     * Host of a URL, lowercased, with userinfo, port, brackets and any trailing
     * dot removed.
     *
     * Written by hand rather than delegating to java.net.URI because the
     * interesting inputs here are hostile ones — `https://old.reddit.com@evil.com/`
     * has to come back as `evil.com`, and `https://reddit.com.evil.com/` must
     * never be mistaken for Reddit. Both are covered in the test suite.
     */
    fun hostOf(raw: String): String? {
        val afterScheme = raw.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        var authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty()) return null

        // Everything before the last '@' is userinfo, however many there are.
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.isEmpty()) return null

        // IPv6 literal.
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            return authority.substring(0, close + 1).lowercase()
        }

        val host = authority.substringBefore(':').trimEnd('.').lowercase()
        return host.ifEmpty { null }
    }

    fun schemeOf(raw: String): String? {
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val scheme = raw.substring(0, colon)
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        if (!scheme.first().isLetter()) return null
        return scheme.lowercase()
    }

    private fun toHttps(raw: String): String =
        if (raw.startsWith("http://", ignoreCase = true)) "https://" + raw.substring(7) else raw

    private fun firstPathSegment(raw: String): String? {
        val afterScheme = raw.substringAfter("://", "")
        val path = afterScheme.substringAfter('/', "")
        val seg = path.substringBefore('?').substringBefore('#').substringBefore('/')
        return seg.ifEmpty { null }
    }

    fun queryValue(raw: String, name: String): String? {
        val query = raw.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val key = percentDecode(pair.substringBefore('='))
            if (key.equals(name, ignoreCase = true)) {
                return percentDecode(pair.substringAfter('=', ""))
            }
        }
        return null
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s && '+' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { out.append(' '); i++ }
                c == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3)
                    val v = hex.toIntOrNull(16)
                    if (v == null) { out.append(c); i++ } else { out.append(v.toChar()); i += 3 }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------- helpers

    /** True for a server-rendered 18+ interstitial path, where one is served. */
    fun isOver18Interstitial(raw: String?): Boolean {
        val url = raw ?: return false
        if (hostOf(url) !in PAGE_HOSTS) return false
        val path = "/" + url.substringAfter("://", "").substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
        return path.trimEnd('/').endsWith("/over18")
    }

    /** True for hosts the suppressor and shims should be injected into. */
    fun isRedditPageHost(host: String?): Boolean = host != null && host in PAGE_HOSTS
}
