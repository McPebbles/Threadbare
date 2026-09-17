package com.threadbare.client

import com.threadbare.client.privacy.XpromoBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer 1: refusing the promotion bundles at the network.
 *
 * The asymmetry these tests enforce is the whole design. A pattern that fails
 * to match costs nothing — layers 2 and 3 still remove the overlay. A pattern
 * that over-matches takes out a shared chunk and breaks part of the site with
 * no visible cause, which is far worse than an overlay that still shows. So the
 * negative cases below matter more than the positive ones.
 */
class XpromoBlockTest {

    private fun blocked(host: String, path: String) =
        XpromoBlock.decide(host, path).blocked

    /**
     * The request whose response IS the overlay, from a captured DOM. Refusing
     * it is the whole of layer 1 on the current build; the chunk-name rules
     * below are no-ops there because the chunks are opaque hashes.
     */
    @Test
    fun `the experience-activation partial is refused`() {
        assertTrue(blocked("www.reddit.com",
            "/svc/shreddit/partial/Zfxklh/activate-experience"))
        assertTrue(blocked("www.reddit.com",
            "/svc/shreddit/partial/Zfxklh/activate-experience?query=%7B%7D"))
    }

    @Test
    fun `other partials are not touched`() {
        // These deliver real UI. Blocking them would break the site invisibly.
        for (p in listOf(
            "/svc/shreddit/partial/SZvQJM/user-drawer-menu",
            "/svc/shreddit/partial/pQqdVY/theme-switcher-modal",
            "/svc/shreddit/partial/fKScTR/login-step",
            "/svc/shreddit/partial/abc/comments-page",
        )) {
            assertFalse(p, blocked("www.reddit.com", p))
        }
    }

    @Test
    fun `promotion bundles are refused`() {
        assertTrue(blocked("www.redditstatic.com", "/shreddit/en-US/xpromo.abc123.js"))
        assertTrue(blocked("www.redditstatic.com", "/shreddit/nsfw_blocking_modal-9f2.js"))
        assertTrue(blocked("www.redditstatic.com", "/shreddit/bottom_bar_xpromo.js"))
        assertTrue(blocked("www.redditstatic.com", "/shreddit/app_selector.chunk.js"))
    }

    @Test
    fun `ordinary chunks are untouched`() {
        for (p in listOf(
            "/shreddit/en-US/main.abc.js",
            "/shreddit/comments.js",
            "/shreddit/post-feed.chunk.js",
            "/shreddit/en-US/subreddit_page.js",
            "/desktop2x/reddit.js",
        )) {
            assertFalse(p, blocked("www.redditstatic.com", p))
        }
    }

    /**
     * The guard list. If someone widens the patterns later, a shared chunk with
     * one of these tokens in it must still load — a broken runtime is not a
     * tradeoff anyone would choose over a visible banner.
     */
    @Test
    fun `never-block tokens win over a matching pattern`() {
        assertFalse(blocked("www.redditstatic.com", "/shreddit/runtime.xpromo.js"))
        assertFalse(blocked("www.redditstatic.com", "/shreddit/vendor-xpromo.js"))
        assertFalse(blocked("www.redditstatic.com", "/shreddit/polyfill.xpromo.js"))
        assertFalse(blocked("www.redditstatic.com", "/shreddit/shreddit-app.xpromo.js"))
    }

    @Test
    fun `only scripts are refused`() {
        // Refusing a document or an image by accident is a confusing failure;
        // refusing a script is the point.
        assertFalse(blocked("www.redditstatic.com", "/shreddit/xpromo.css"))
        assertFalse(blocked("www.redditstatic.com", "/img/xpromo-banner.png"))
        assertFalse(blocked("www.reddit.com", "/r/xpromo/"))
        assertTrue(blocked("www.redditstatic.com", "/shreddit/xpromo.js?v=2"))
    }

    @Test
    fun `nothing off a reddit bundle host is considered`() {
        assertFalse(blocked("cdn.example.org", "/xpromo.js"))
        assertFalse(blocked("i.redd.it", "/xpromo.js"))
    }

    @Test
    fun `a lookalike host is not a bundle host`() {
        assertFalse(blocked("www.redditstatic.com.evil.example", "/xpromo.js"))
    }

    @Test
    fun `case and a trailing dot do not evade the rule`() {
        assertTrue(blocked("WWW.RedditStatic.com.", "/Shreddit/XPromo.JS"))
    }

    @Test
    fun `missing host or path is not blocked`() {
        assertFalse(XpromoBlock.decide(null, "/xpromo.js").blocked)
        assertFalse(XpromoBlock.decide("www.redditstatic.com", null).blocked)
        assertFalse(XpromoBlock.decide("", "").blocked)
    }

    @Test
    fun `no pattern is a generic word that could match a shared chunk`() {
        val tooGeneric = setOf("modal", "sheet", "banner", "promo", "app", "js", "bundle")
        for (p in XpromoBlock.patterns()) {
            assertFalse("pattern '$p' is too generic to be safe", p in tooGeneric)
            assertTrue("pattern '$p' is suspiciously short", p.length >= 6)
        }
    }

    // ------------------------------------------------- the post-page carve-out

    /**
     * A device report settled this one: on a post page the experience partial's
     * response carries the post as well as the wall, so refusing it leaves a
     * hole where the media should be. The wall on a post page falls to the
     * observer instead, which no longer removes anything holding content.
     */
    @Test
    fun `the experience partial is allowed on a post page`() {
        val query = "params=prefix%3Dr%26subreddit%3DUkraineWarVideoReport" +
            "%26postId%3D14tgoyg%26slug%3Dnsfw_close_quarters&query=%7B%7D"
        val d = XpromoBlock.decide(
            "www.reddit.com", "/svc/shreddit/partial/Zfxklh/activate-experience", query,
        )
        assertFalse("a post page must keep its media", d.blocked)
    }

    @Test
    fun `the experience partial is still refused on a feed and a subreddit`() {
        val feed = XpromoBlock.decide(
            "www.reddit.com", "/svc/shreddit/partial/Zfxklh/activate-experience",
            "query=%7B%22rdt%22%3A%2251510%22%7D",
        )
        assertTrue("the 30-second feed takeover is the case layer 1 exists for", feed.blocked)

        val sub = XpromoBlock.decide(
            "www.reddit.com", "/svc/shreddit/partial/Zfxklh/activate-experience",
            "params=prefix%3Dr%26subredditName%3Dprivacy%26sig%3Dv1.abc",
        )
        assertTrue("an adult-flagged subreddit still gets no wall", sub.blocked)
    }

    @Test
    fun `an unrecognisable partial request keeps the old behaviour`() {
        // Failing towards the previous behaviour rather than towards a new one.
        for (q in listOf(null, "", "foo=bar")) {
            assertTrue(
                "query=$q",
                XpromoBlock.decide(
                    "www.reddit.com",
                    "/svc/shreddit/partial/Zfxklh/activate-experience", q,
                ).blocked,
            )
        }
    }

    @Test
    fun `postId is recognised encoded or plain, in any case`() {
        assertTrue(XpromoBlock.isPostPage("params=postId%3Dabc"))
        assertTrue(XpromoBlock.isPostPage("POSTID=abc"))
        assertTrue(XpromoBlock.isPostPage("x=1&postid=abc"))
        assertFalse(XpromoBlock.isPostPage("subredditName=privacy"))
        assertFalse(XpromoBlock.isPostPage(null))
    }
}
