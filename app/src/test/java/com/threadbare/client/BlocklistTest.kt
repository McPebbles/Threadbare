package com.threadbare.client

import com.threadbare.client.privacy.BlockMode
import com.threadbare.client.privacy.Blocklist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {

    private fun blocked(host: String, path: String = "/x", mode: BlockMode = BlockMode.BALANCED) =
        Blocklist.decide(host, path, mode).blocked

    /**
     * The most important test in the file. A blocklist that takes the site down
     * with the tracker is worse than no blocklist, because the user cannot tell
     * which of the two happened.
     */
    @Test
    fun `nothing on the must-never-block list is ever blocked, in any mode`() {
        for (mode in BlockMode.entries) {
            for (host in Blocklist.neverBlocked()) {
                assertFalse("$host in $mode", blocked(host, "/assets/x.css", mode))
            }
        }
    }

    @Test
    fun `reddit telemetry is blocked`() {
        for (h in listOf("events.reddit.com", "alb.reddit.com", "pixel.redditmedia.com",
                         "w3-reporting.reddit.com")) {
            assertTrue(h, blocked(h))
        }
    }

    /**
     * The suffix trap. redditmedia.com carries both pixel. (telemetry) and
     * a.thumbs. (every thumbnail on the site), so a rule on the registrable
     * domain would take the site down with the tracker.
     */
    @Test
    fun `thumbnails survive while the pixel on the same domain does not`() {
        assertFalse(blocked("a.thumbs.redditmedia.com", "/t.jpg"))
        assertFalse(blocked("b.thumbs.redditmedia.com", "/t.jpg"))
        assertTrue(blocked("pixel.redditmedia.com", "/pixel"))
    }

    @Test
    fun `third-party trackers are blocked in balanced`() {
        for (h in listOf("www.google-analytics.com", "googletagmanager.com",
                         "connect.facebook.net", "app.link", "api2.branch.io")) {
            assertTrue(h, blocked(h))
        }
    }

    @Test
    fun `embeds are only blocked in strict`() {
        assertFalse(blocked("www.youtube.com", "/embed/x", BlockMode.BALANCED))
        assertTrue(blocked("www.youtube.com", "/embed/x", BlockMode.STRICT))
        assertFalse(blocked("i.imgur.com", "/a.png", BlockMode.BALANCED))
        assertTrue(blocked("i.imgur.com", "/a.png", BlockMode.STRICT))
    }

    @Test
    fun `off blocks nothing at all`() {
        assertFalse(blocked("events.reddit.com", "/api/v2/event", BlockMode.OFF))
        assertFalse(blocked("googletagmanager.com", "/gtm.js", BlockMode.OFF))
    }

    /**
     * A beacon proxied through an allowed host is invisible to a host-based
     * rule. Tombot found the same category on the Tim Hortons site.
     */
    @Test
    fun `a beacon path is blocked even on an allowed host`() {
        assertTrue(blocked("www.reddit.com", "/api/v2/event"))
        assertTrue(blocked("old.reddit.com", "/timings"))
    }

    @Test
    fun `ordinary reddit paths are not mistaken for beacons`() {
        for (p in listOf("/r/GrapheneOS/", "/api/vote", "/comments/abc/title/",
                         "/static/reddit.css", "/search")) {
            assertFalse(p, blocked("old.reddit.com", p))
        }
    }

    @Test
    fun `a lookalike domain does not inherit an allow or a block`() {
        assertFalse(Blocklist.matchesDomain("reddit.com.evil.example", "reddit.com"))
        assertFalse(Blocklist.matchesDomain("notgoogle-analytics.com", "google-analytics.com"))
        assertTrue(Blocklist.matchesDomain("www.google-analytics.com", "google-analytics.com"))
        assertTrue(Blocklist.matchesDomain("google-analytics.com", "google-analytics.com"))
    }

    @Test
    fun `a missing host is not blocked`() {
        assertFalse(Blocklist.decide(null, "/x", BlockMode.STRICT).blocked)
        assertFalse(Blocklist.decide("", "/x", BlockMode.STRICT).blocked)
    }

    @Test
    fun `host matching ignores case and a trailing dot`() {
        assertTrue(blocked("EVENTS.Reddit.com."))
    }
}
