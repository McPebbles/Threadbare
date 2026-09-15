package com.threadbare.client

import com.threadbare.client.web.UrlRules
import com.threadbare.client.web.UrlRules.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing suite.
 *
 * UrlRules normalises every Reddit host to www.reddit.com — the only surface
 * that still serves a logged-out reader since old.reddit was put behind a login
 * in July 2026 — and refuses every route into the native app.
 *
 * Run with `./gradlew testDebugUnitTest`. As of writing this has never been
 * executed — no Kotlin compiler was reachable from the environment the app was
 * built in — so it is the first real check to run.
 */
class UrlRulesTest {

    private fun decide(url: String) = UrlRules.decide(url, null)

    private fun assertRewrite(from: String, to: String) {
        val v = decide(from)
        assertEquals("decision for $from", Decision.REWRITE, v.decision)
        assertEquals("rewrite of $from", to, v.url)
    }

    // ------------------------------------------------- the old.reddit pin

    @Test
    fun `every reddit page host is normalised to www`() {
        val hosts = listOf(
            "reddit.com", "sh.reddit.com", "new.reddit.com", "m.reddit.com",
            "i.reddit.com", "np.reddit.com", "amp.reddit.com",
        )
        for (h in hosts) {
            assertRewrite("https://$h/r/GrapheneOS/", "https://www.reddit.com/r/GrapheneOS/")
        }
    }

    @Test
    fun `www is already canonical and loads untouched`() {
        assertEquals(Decision.LOAD, decide("https://www.reddit.com/r/GrapheneOS/").decision)
    }

    /**
     * The reason old.reddit is still in the host table at all. It is login-walled
     * now, so following one would show a wall rather than a page — but there is a
     * decade of old.reddit links in posts and bookmarks, and sending them to www
     * is the difference between those links working and not.
     */
    @Test
    fun `an old reddit link is rerouted to www rather than hitting the login wall`() {
        assertRewrite(
            "https://old.reddit.com/r/GrapheneOS/comments/abc/title/",
            "https://www.reddit.com/r/GrapheneOS/comments/abc/title/",
        )
    }

    @Test
    fun `http is upgraded along with the host`() {
        assertRewrite("http://old.reddit.com/r/privacy/", "https://www.reddit.com/r/privacy/")
    }

    @Test
    fun `paths query and fragment survive the rewrite`() {
        assertRewrite(
            "https://sh.reddit.com/r/x/comments/abc/title/?sort=top&context=3#c1",
            "https://www.reddit.com/r/x/comments/abc/title/?sort=top&context=3#c1",
        )
    }

    // --------------------------------------------------- app links refused

    @Test
    fun `app schemes are refused and never dispatched`() {
        for (url in listOf(
            "reddit://reddit/r/GrapheneOS",
            "intent://reddit.com/r/x#Intent;package=com.reddit.frontpage;end",
            "market://details?id=com.reddit.frontpage",
            "android-app://com.reddit.frontpage",
        )) {
            assertEquals("refusing $url", Decision.REFUSE, decide(url).decision)
        }
    }

    @Test
    fun `app store web pages are refused too`() {
        assertEquals(Decision.REFUSE,
            decide("https://play.google.com/store/apps/details?id=com.reddit.frontpage").decision)
        assertEquals(Decision.REFUSE,
            decide("https://apps.apple.com/app/reddit/id1064216828").decision)
    }

    @Test
    fun `javascript urls are refused`() {
        assertEquals(Decision.REFUSE, decide("javascript:alert(1)").decision)
    }

    // ------------------------------------------------- tracking parameters

    @Test
    fun `deep link and attribution parameters are stripped`() {
        assertRewrite(
            "https://www.reddit.com/r/x/comments/abc/?utm_source=share&utm_medium=android_app" +
                "&utm_name=androidcss&correlation_id=deadbeef&ref=share&ref_source=link",
            "https://www.reddit.com/r/x/comments/abc/",
        )
    }

    @Test
    fun `functional parameters are preserved`() {
        assertRewrite(
            "https://sh.reddit.com/r/x/comments/abc/?sort=new&context=3&depth=5&limit=500",
            "https://www.reddit.com/r/x/comments/abc/?sort=new&context=3&depth=5&limit=500",
        )
    }

    @Test
    fun `an over18 dest parameter is preserved where one is served`() {
        assertRewrite(
            "https://old.reddit.com/over18?dest=https%3A%2F%2Fwww.reddit.com%2Fr%2Fx%2F",
            "https://www.reddit.com/over18?dest=https%3A%2F%2Fwww.reddit.com%2Fr%2Fx%2F",
        )
    }

    @Test
    fun `search asks for adult-flagged results, which is the point of the app`() {
        val v = decide("https://www.reddit.com/search?q=grapheneos")
        assertEquals(Decision.REWRITE, v.decision)
        assertTrue(v.url!!.contains("include_over_18=on"))
        assertTrue(v.url!!.contains("q=grapheneos"))
    }

    @Test
    fun `search does not add the flag twice`() {
        val once = UrlRules.canonical("https://www.reddit.com/r/x/search?q=a&include_over_18=on")
        assertEquals(1, Regex("include_over_18").findAll(once!!).count())
    }

    // ------------------------------------------------------- hostile hosts

    @Test
    fun `a lookalike host is not reddit`() {
        val v = decide("https://reddit.com.evil.example/r/x/")
        assertEquals(Decision.EXTERNAL, v.decision)
    }

    @Test
    fun `userinfo cannot disguise the real host`() {
        assertEquals("evil.example", UrlRules.hostOf("https://www.reddit.com@evil.example/x"))
        assertEquals(Decision.EXTERNAL, decide("https://www.reddit.com@evil.example/x").decision)
    }

    @Test
    fun `multiple at signs still resolve to the last authority`() {
        assertEquals("evil.example", UrlRules.hostOf("https://a@b@evil.example/x"))
    }

    @Test
    fun `port and trailing dot are removed from the host`() {
        assertEquals("www.reddit.com", UrlRules.hostOf("https://www.reddit.com.:443/r/x"))
    }

    @Test
    fun `case is normalised`() {
        assertRewrite("https://OLD.Reddit.COM/r/X/", "https://www.reddit.com/r/X/")
    }

    // ------------------------------------------------------ media and links

    @Test
    fun `media hosts load in app and are not rewritten`() {
        for (h in listOf("i.redd.it", "v.redd.it", "preview.redd.it",
                         "a.thumbs.redditmedia.com", "www.redditstatic.com")) {
            val v = decide("https://$h/thing.png")
            assertEquals("media host $h", Decision.LOAD, v.decision)
        }
    }

    @Test
    fun `i redd it is media, not a short link`() {
        // The ordering trap: i.redd.it is a subdomain of redd.it, so a short-link
        // rule checked first would rewrite every image into a comments page.
        val v = decide("https://i.redd.it/abc123.png")
        assertEquals(Decision.LOAD, v.decision)
    }

    @Test
    fun `bare redd it short links become comments pages on old`() {
        assertRewrite("https://redd.it/abc123", "https://www.reddit.com/comments/abc123")
    }

    @Test
    fun `share links need no special case now that www is the surface`() {
        // These are native www URLs. The previous version had to route them
        // around old.reddit, which 404s on them; that detour is gone with it.
        assertEquals(Decision.LOAD,
            decide("https://www.reddit.com/r/GrapheneOS/s/AbCdEf123/").decision)
        assertRewrite("https://sh.reddit.com/r/x/s/AbCdEf/",
            "https://www.reddit.com/r/x/s/AbCdEf/")
    }

    @Test
    fun `the outbound redirector is unwrapped rather than followed`() {
        val v = decide("https://out.reddit.com/t3_abc?url=https%3A%2F%2Fexample.org%2Fa&token=x")
        assertEquals(Decision.EXTERNAL, v.decision)
        assertEquals("https://example.org/a", v.url)
    }

    // --------------------------- what a real page links to (captured DOM)

    /**
     * Reddit's mobile site sends nearly every tap through `applink.reddit.com`
     * — two hundred of them on one feed page. Treating that host as external
     * put the "leaves Reddit" prompt on every post, which is the bug reported
     * from the device.
     */
    @Test
    fun `applink is the same page on a redirector host, and stays in the app`() {
        assertRewrite(
            "https://applink.reddit.com/r/privacy/comments/abc/title/?utm_source=app_first_navigation&mweb_loid=t2_x",
            "https://www.reddit.com/r/privacy/comments/abc/title/",
        )
        assertRewrite("https://applink.reddit.com/r/privacy/?utm_source=app_first_navigation",
            "https://www.reddit.com/r/privacy/")
    }

    @Test
    fun `the onelink app-store bounce is unwrapped to its reddit destination`() {
        val v = decide("https://reddit.onelink.me/MRHZ?deep_link_value=https%3A%2F%2Fwww.reddit.com%2Fr%2Fprivacy%2F" +
            "&af_dp=reddit%3A%2F%2Freddit%2Fr%2Fprivacy%2F&pid=xpromo&af_channel=xpromo")
        assertEquals(Decision.REWRITE, v.decision)
        assertEquals("https://www.reddit.com/r/privacy/", v.url)
    }

    @Test
    fun `a onelink with no reddit destination is the install funnel and is refused`() {
        assertEquals(Decision.REFUSE, decide("https://reddit.onelink.me/MRHZ?pid=xpromo").decision)
        assertEquals(Decision.REFUSE,
            decide("https://reddit.onelink.me/MRHZ?deep_link_value=https%3A%2F%2Fevil.example%2F").decision)
    }

    @Test
    fun `an ad click is refused rather than followed or prompted`() {
        assertEquals(Decision.REFUSE, decide("https://alb.reddit.com/cr?za=opaque&zp=1").decision)
    }

    /** The user's rule: reddit.com is the app, everything else is the browser. */
    @Test
    fun `any other reddit subdomain stays in the app`() {
        for (h in listOf("worldnews.reddit.com", "news.reddit.com", "developers.reddit.com",
                         "chat.reddit.com", "something-new.reddit.com")) {
            val v = decide("https://$h/some/path")
            assertTrue("$h should stay in app, got ${v.decision}",
                v.decision == Decision.LOAD || v.decision == Decision.REWRITE)
        }
    }

    @Test
    fun `reddit-owned but not reddit-com is still external`() {
        assertEquals(Decision.EXTERNAL, decide("https://www.redditinc.com/policies/privacy-policy").decision)
        assertEquals(Decision.EXTERNAL, decide("https://www.reddithelp.com/hc/").decision)
    }

    @Test
    fun `off-site links go to a browser`() {
        assertEquals(Decision.EXTERNAL, decide("https://grapheneos.org/features").decision)
    }

    @Test
    fun `mail and telephone links are handed to the system`() {
        assertEquals(Decision.EXTERNAL, decide("mailto:someone@example.org").decision)
    }

    @Test
    fun `blob and data urls load, because video needs them`() {
        assertEquals(Decision.LOAD, decide("blob:https://www.reddit.com/uuid").decision)
    }

    // ---------------------------------------------------------- interstitial

    @Test
    fun `the over18 interstitial is recognised on any reddit host`() {
        assertTrue(UrlRules.isOver18Interstitial("https://www.reddit.com/r/x/over18"))
        assertTrue(UrlRules.isOver18Interstitial("https://www.reddit.com/over18/"))
        assertFalse(UrlRules.isOver18Interstitial("https://www.reddit.com/r/over18stuff/"))
        assertFalse(UrlRules.isOver18Interstitial("https://evil.example/over18"))
    }

    // ------------------------------------------------------------- no loops

    @Test
    fun `canonical is idempotent, so doUpdateVisitedHistory cannot spin`() {
        val once = UrlRules.canonical("https://sh.reddit.com/r/x/?utm_source=share")!!
        val twice = UrlRules.canonical(once)!!
        assertEquals(once, twice)
        assertEquals(Decision.LOAD, decide(once).decision)
    }

    @Test
    fun `canonical returns null for a non-reddit url`() {
        assertNull(UrlRules.canonical("https://example.org/"))
    }

    @Test
    fun `empty and malformed input is refused, not crashed on`() {
        assertEquals(Decision.REFUSE, UrlRules.decide(null, null).decision)
        assertEquals(Decision.REFUSE, decide("").decision)
        assertEquals(Decision.REFUSE, decide("not a url").decision)
        assertEquals(Decision.REFUSE, decide("://").decision)
    }
}
