package com.threadbare.client

import com.threadbare.client.web.CookieSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 18+ defeat is two cookies. If these strings are wrong the whole feature
 * is silently absent, and the only symptom is a wall that should not be there.
 */
class CookieSeedTest {

    @Test
    fun `over18 is set on the registrable domain so every reddit host sees it`() {
        val seed = CookieSeed.seeds().first { it.name == "over18" }
        // `true` is what Reddit's own confirmation button writes.
        assertEquals("true", seed.value)
        assertTrue(seed.header.startsWith("over18=true;"))
        assertTrue(seed.header.contains("Domain=.reddit.com"))
        assertTrue(seed.header.contains("Path=/"))
        assertTrue(seed.header.contains("Secure"))
        assertTrue(seed.header.contains("SameSite=Lax"))
    }

    @Test
    fun `options carries both gate opt-ins, url-encoded`() {
        val seed = CookieSeed.seeds().first { it.name == "_options" }
        assertTrue(seed.value.contains("pref_gated_sr_optin"))
        assertTrue(seed.value.contains("pref_quarantine_optin"))
        // Braces, quotes, colons and spaces must all be encoded: an unencoded
        // one truncates the cookie at the first separator and the flag is lost.
        assertTrue(seed.value.contains("%7B"))
        assertTrue(seed.value.contains("%22"))
        assertTrue(seed.value.contains("%3A"))
        assertTrue(seed.value.contains("%20"))
        for (raw in listOf("{", "}", "\"", " ", ",")) {
            assertTrue("raw $raw leaked into the cookie value",
                !seed.value.contains(raw))
        }
    }

    @Test
    fun `a space never becomes a plus`() {
        // URLEncoder would produce '+', which Reddit reads back as a literal
        // plus and the JSON no longer parses.
        assertEquals("a%20b", CookieSeed.percentEncode("a b"))
    }

    @Test
    fun `unreserved characters are left alone`() {
        assertEquals("Az09-_.~", CookieSeed.percentEncode("Az09-_.~"))
    }

    @Test
    fun `non-ascii is encoded as utf-8 bytes`() {
        assertEquals("%C3%A9", CookieSeed.percentEncode("é"))
    }

    @Test
    fun `the seed url is an https reddit origin`() {
        assertTrue(CookieSeed.SEED_URL.startsWith("https://"))
        assertTrue(CookieSeed.SEED_URL.contains("reddit.com"))
    }

    @Test
    fun `both seeds are long lived`() {
        for (seed in CookieSeed.seeds()) {
            assertTrue(seed.header.contains("Max-Age=${CookieSeed.MAX_AGE_SECONDS}"))
        }
    }
}
