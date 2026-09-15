package com.threadbare.client.web

/**
 * Reddit's own preference cookies, seeded before the first load.
 *
 * On the legacy site these were the entire 18+ defeat: the wall was a
 * server-rendered page gated on `over18`, so setting the cookie meant the page
 * was never generated. That surface is gone — Reddit began requiring an account
 * to read old.reddit in July 2026 — and on www the 18+ block is a client-side
 * overlay belonging to the app-promotion system instead.
 *
 * They are kept now that the app wraps www rather than old.reddit, because
 * they cost one header each and Reddit has historically honoured them across
 * hosts. They are NOT the primary defence any more: on www the 18+ block is
 * served as part of the app-promotion system, and the three layers in
 * `privacy/XpromoBlock.kt`, `assets/xpromo-suppress.css` and
 * `SiteScripts.XPROMO_SUPPRESSOR` are what remove it. See DESIGN.md.
 *
 * Deliberately free of `android.*` imports: the header strings are built here
 * and handed to CookieManager by [WebViewSetup], so they can be asserted on the
 * plain JVM.
 */
object CookieSeed {

    /** The URL the cookies are written against. */
    const val SEED_URL = "https://www.reddit.com/"

    /** Ten years. Reddit's own over18 cookie is long-lived too. */
    const val MAX_AGE_SECONDS: Long = 10L * 365 * 24 * 3600

    data class Seed(
        val name: String,
        val value: String,
        val header: String,
        val why: String,
    )

    /**
     * `_options` is stored by Reddit as URL-encoded JSON. Both flags are set:
     *
     * - `pref_gated_sr_optin` answers the newer "this community may contain
     *   mature content" gate.
     * - `pref_quarantine_optin` is included because it costs nothing, but see
     *   the honest limit in DESIGN.md: a quarantine is not the adult flag, and
     *   lifting one genuinely requires a signed-in account with a verified
     *   email. If a quarantined community still refuses, that is expected.
     *
     * Neither cookie was observable against a live Reddit while this was
     * written, so treat both as best-effort. The overlay suppression is what
     * the app actually relies on.
     */
    private const val OPTIONS_JSON =
        """{"pref_quarantine_optin": true, "pref_gated_sr_optin": true}"""

    fun seeds(maxAgeSeconds: Long = MAX_AGE_SECONDS): List<Seed> = listOf(
        // The value is `true`, not `1`, because that is what Reddit's own
        // "Yes, I'm Over 18" button writes — from a captured DOM:
        //   <ac-set-cookie name="over18" value="true" options='{"expires":365}'>
        // followed by a reload. Seeding the identical cookie before the first
        // load means the server never selects the 18+ experience at all.
        seed(
            name = "over18",
            value = "true",
            maxAge = maxAgeSeconds,
            why = "the exact cookie Reddit's own over-18 confirmation sets",
        ),
        seed(
            name = "_options",
            value = percentEncode(OPTIONS_JSON),
            maxAge = maxAgeSeconds,
            why = "gated-community prompts; quarantine opt-in is best-effort only",
        ),
    )

    private fun seed(name: String, value: String, maxAge: Long, why: String): Seed {
        // Domain is the registrable domain with a leading dot so the cookie is
        // sent to old., www. and any other Reddit host the session touches.
        // SameSite=Lax rather than None: nothing here is a cross-site request,
        // and None would require a third-party cookie, which the app turns off.
        val header = "$name=$value; Domain=.reddit.com; Path=/; " +
            "Max-Age=$maxAge; Secure; SameSite=Lax"
        return Seed(name = name, value = value, header = header, why = why)
    }

    /**
     * Percent-encode for a cookie value: everything outside the unreserved set
     * plus a few characters cookies tolerate. Written out rather than using
     * `java.net.URLEncoder` so the result is exact and does not turn spaces
     * into `+`, which Reddit would read back as a literal plus.
     */
    fun percentEncode(s: String): String {
        val out = StringBuilder(s.length * 3)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            val unreserved = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '-' || c == '_' || c == '.' || c == '~'
            if (unreserved) {
                out.append(c)
            } else {
                out.append('%').append(HEX[(v shr 4) and 0xF]).append(HEX[v and 0xF])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
