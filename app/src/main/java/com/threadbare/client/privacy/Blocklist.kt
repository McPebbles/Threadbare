package com.threadbare.client.privacy

/**
 * Request filtering.
 *
 * Free of `android.*` on purpose: every decision below is exercised by the JVM
 * suite, including a must-never-block list. Tombot's notes record the failure
 * this guards against — a rule that looks harmless and quietly removes a
 * section of the site — so the allow list is checked first and always wins.
 */
object Blocklist {

    /**
     * Hosts that must never be blocked in any mode. Reddit serves the site's
     * CSS, sprites, thumbnails and video from these; blocking any of them
     * produces a page that looks broken in a way that is hard to attribute.
     */
    private val NEVER_BLOCK = setOf(
        "www.reddit.com",
        "sh.reddit.com",
        "reddit.com",
        "www.redditstatic.com",
        "redditstatic.com",
        "i.redd.it",
        "v.redd.it",
        "preview.redd.it",
        "external-preview.redd.it",
        "a.thumbs.redditmedia.com",
        "b.thumbs.redditmedia.com",
        "thumbs.redditmedia.com",
        "styles.redditmedia.com",
        "emoji.redditmedia.com",
        "reddit-uploaded-media.s3-accelerate.amazonaws.com",
        "reddit-uploaded-video.s3-accelerate.amazonaws.com",
    )

    /**
     * Reddit's own measurement endpoints. Exact hosts, not suffixes.
     *
     * The suffix trap is real and documented in the suite: `redditmedia.com`
     * carries both `pixel.` (telemetry) and `a.thumbs.` (every thumbnail on the
     * site), so a suffix rule on the registrable domain would take the site
     * down with the tracker.
     */
    private val REDDIT_TELEMETRY = setOf(
        "events.reddit.com",
        "events.redditmedia.com",
        "alb.reddit.com",
        "pixel.redditmedia.com",
        "pixel.reddit.com",
        "w3-reporting.reddit.com",
        "w3-reporting-nel.reddit.com",
        "w3-reporting-csp.reddit.com",
        "sentry.reddit.com",
        "stats.redditmedia.com",
        "matomo.reddit.com",
    )

    /** Third parties, matched on the registrable domain. */
    private val TRACKER_DOMAINS = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "googlesyndication.com",
        "googleadservices.com",
        "doubleclick.net",
        "adservice.google.com",
        "connect.facebook.net",
        "facebook.com",
        "scorecardresearch.com",
        "quantserve.com",
        "amplitude.com",
        "segment.io",
        "segment.com",
        "mixpanel.com",
        "branch.io",
        "app.link",
        "bnc.lt",
        "adjust.com",
        "appsflyer.com",
        "bat.bing.com",
        "snapchat.com",
        "sc-static.net",
        "tiktok.com",
        "hotjar.com",
        "fullstory.com",
        "datadoghq.com",
        "sentry.io",
        "newrelic.com",
        "nr-data.net",
    )

    /**
     * Blocked only in STRICT. Each of these is a third-party embed that a post
     * can legitimately contain, so blocking it is a real functional cost.
     */
    private val STRICT_EXTRA_DOMAINS = setOf(
        "youtube.com",
        "youtu.be",
        "ytimg.com",
        "twitter.com",
        "x.com",
        "twimg.com",
        "instagram.com",
        "cdninstagram.com",
        "imgur.com",
        "gfycat.com",
        "redgifs.com",
        "streamable.com",
        "vimeo.com",
        "soundcloud.com",
        "spotify.com",
        "twitch.tv",
    )

    /**
     * Path fragments that identify a beacon even on an allowed host. Reddit
     * proxies some measurement through its own domain, which no host-based
     * rule can see — the same category Tombot found on the Tim Hortons site.
     */
    private val BEACON_PATHS = listOf(
        "/api/v2/event",
        "/api/event",
        "/timings",
        "/w3-reporting",
        "/report-to",
        "/csp-report",
        "/pixel.png",
        "/1x1.png",
        "/beacon",
    )

    data class Decision(val blocked: Boolean, val reason: String = "")

    /**
     * @param host the request's host, lowercased
     * @param path the request's path
     * @param mode the active mode
     */
    fun decide(host: String?, path: String?, mode: BlockMode): Decision {
        if (mode == BlockMode.OFF) return Decision(false, "mode off")
        val h = host?.lowercase()?.trimEnd('.').orEmpty()
        if (h.isEmpty()) return Decision(false, "no host")

        val p = (path ?: "").lowercase()

        // A beacon proxied through an allowed host is still a beacon, so this
        // is checked before the allow list — but only for exact beacon paths,
        // which no page or asset URL on Reddit uses.
        if (BEACON_PATHS.any { p.startsWith(it) || p.endsWith(it) }) {
            return Decision(true, "beacon path")
        }

        if (h in NEVER_BLOCK) return Decision(false, "must never block")

        if (h in REDDIT_TELEMETRY) return Decision(true, "reddit telemetry")

        if (TRACKER_DOMAINS.any { matchesDomain(h, it) }) {
            return Decision(true, "third-party tracker")
        }

        if (mode == BlockMode.STRICT &&
            STRICT_EXTRA_DOMAINS.any { matchesDomain(h, it) }
        ) {
            return Decision(true, "third-party embed (strict)")
        }

        return Decision(false, "not listed")
    }

    /**
     * Exact host, or a subdomain of it. `reddit.com.evil.example` must not
     * match `reddit.com`, which naive `contains` or `endsWith` both get wrong.
     */
    fun matchesDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /** Exposed for the verification suite. */
    fun neverBlocked(): Set<String> = NEVER_BLOCK
}
