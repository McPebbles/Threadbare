package com.threadbare.client

import com.threadbare.client.web.SiteScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteScriptsTest {

    // ------------------------------------------------- the suppressor shape

    /**
     * `paint-group="xpromo"` is Reddit's own grouping attribute for the whole
     * app-promotion family — app selector, top button, bottom bar, and the
     * NSFW blocking modal. Matching on it survives a bundle being renamed,
     * which an enumerated list of bundlenames does not, so it must be present.
     */
    @Test
    fun `the suppressor keys on reddit's own paint-group attribute`() {
        assertTrue(SiteScripts.XPROMO_SUPPRESSOR.contains("paint-group=\\\"xpromo\\\"") ||
            SiteScripts.XPROMO_SUPPRESSOR.contains("paint-group=\"xpromo\""))
    }

    @Test
    fun `the suppressor covers every member of the family seen in the wild`() {
        val js = SiteScripts.XPROMO_SUPPRESSOR
        for (token in listOf(
            "xpromo-bottom-sheet", "xpromo-bottom-bar", "xpromo-app-selector",
            "xpromo-nsfw-blocking-container", "nsfw_blocking", "blocking-modal",
            "nsfw-qr-dialog", "smart-banner",
        )) {
            assertTrue("missing $token", js.contains(token))
        }
    }

    /**
     * The takeover freezes the page as well as covering it. Removing the
     * overlay without releasing the lock leaves a page that cannot be
     * scrolled, which is a worse outcome than the overlay.
     */
    @Test
    fun `the suppressor releases both known scroll locks`() {
        val js = SiteScripts.XPROMO_SUPPRESSOR
        assertTrue(js.contains("rpl-scroll-lock"))
        assertTrue(js.contains("scroll-disabled"))
    }

    /** The one job CSS provably cannot do. */
    @Test
    fun `the suppressor reaches into the shadow root`() {
        assertTrue(SiteScripts.XPROMO_SUPPRESSOR.contains("shadowRoot"))
    }

    /**
     * The community scripts this was derived from poll every 300ms for ten
     * seconds. An observer fires when something changes and costs nothing when
     * nothing does; a timer on a phone is a battery cost that runs forever.
     */
    @Test
    fun `the suppressor observes rather than polls`() {
        val js = SiteScripts.XPROMO_SUPPRESSOR
        assertTrue(js.contains("MutationObserver"))
        assertFalse(js.contains("setInterval"))
    }

    @Test
    fun `the suppressor coalesces work and can disconnect`() {
        val js = SiteScripts.XPROMO_SUPPRESSOR
        assertTrue(js.contains("requestAnimationFrame"))
        assertTrue(js.contains("disconnect"))
    }

    // ------------------------------------------------ the UA escape hatch

    @Test
    fun `the chrome major version is read from the real engine, never hard-coded`() {
        val ua = "Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.7390.54 Mobile Safari/537.36"
        assertEquals("141", SiteScripts.majorVersionOf(ua))
        assertTrue(SiteScripts.desktopUserAgent("141").contains("Chrome/141.0.0.0"))
    }

    @Test
    fun `a user agent with no chrome token falls back rather than throwing`() {
        assertEquals("140", SiteScripts.majorVersionOf("something else"))
        assertEquals("140", SiteScripts.majorVersionOf(null))
    }

    @Test
    fun `the desktop user agent claims windows and not android`() {
        val ua = SiteScripts.desktopUserAgent("140")
        assertTrue(ua.contains("Windows NT 10.0"))
        assertFalse(ua.contains("Android"))
        assertFalse(ua.contains("Mobile"))
    }

    /**
     * Client hints are not derived from the UA string. If these two disagree,
     * Reddit still sees a phone and still serves the promotion machinery —
     * which is SupplyChain's recorded lesson, not a hypothetical.
     */
    @Test
    fun `the shim agrees with the user agent string`() {
        val shim = SiteScripts.desktopShim("140")
        assertTrue(shim.contains("mobile: false"))
        assertTrue(shim.contains("'Windows'"))
        assertTrue(shim.contains("'Win32'"))
        assertTrue(shim.contains("\"140\""))
    }

    /** Claiming no touch on a phone produces a page that expects hover. */
    @Test
    fun `touch is never spoofed`() {
        assertFalse(SiteScripts.desktopShim("140").contains("maxTouchPoints"))
    }

    // ------------------------------------------------------------ escaping

    @Test
    fun `js string escaping closes the injection holes`() {
        val out = SiteScripts.jsString("</script><x>\"a\\b ")
        assertFalse(out.contains("</script>"))
        assertTrue(out.contains("\\u003C"))
        assertTrue(out.contains("\\u003E"))
        assertTrue(out.contains("\\\""))
        assertTrue(out.contains("\\\\"))
        assertTrue(out.contains("\\u2028"))
    }

    @Test
    fun `the style template embeds the stylesheet under a stable id`() {
        val js = SiteScripts.suppressorStyle(".x{color:red}")
        assertTrue(js.contains(".x{color:red}"))
        assertTrue(js.contains("tb-suppress"))
    }
}
