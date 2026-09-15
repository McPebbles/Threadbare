package com.threadbare.client

import com.threadbare.client.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration that 1.1.0 should have shipped with.
 *
 * 1.0.0 stored `start_page = https://old.reddit.com/`. 1.1.0 moved the app to
 * www but left SCHEMA_VERSION at 1, so `materialiseDefaults` returned early on
 * any device that had run 1.0.0, the stored value won, and the app opened on
 * Reddit's "log in to use old Reddit" wall. A fresh install was fine — which is
 * exactly what makes this class of bug survive review.
 *
 * These tests exist because nothing tested the stored-value path at all.
 */
class PrefsMigrationTest {

    @Test
    fun `the schema version was bumped, or the migration never runs`() {
        // If someone changes a default again without bumping this, the new
        // default reaches fresh installs only. That is the whole bug.
        assertTrue("SCHEMA_VERSION must be > 1 for the 1.1.0 migration to run",
            Prefs.SCHEMA_VERSION > 1)
    }

    @Test
    fun `a start page stored by 1_0_0 is rehomed to www`() {
        assertEquals("https://www.reddit.com/",
            Prefs.migratedStartPage("https://old.reddit.com/"))
        assertEquals("https://www.reddit.com/r/popular/",
            Prefs.migratedStartPage("https://old.reddit.com/r/popular/"))
        assertEquals("https://www.reddit.com/r/all/",
            Prefs.migratedStartPage("https://old.reddit.com/r/all/"))
    }

    @Test
    fun `a start page that is already canonical is left alone`() {
        assertEquals("https://www.reddit.com/",
            Prefs.migratedStartPage("https://www.reddit.com/"))
    }

    @Test
    fun `every other reddit host is rehomed too`() {
        for (h in listOf("sh.reddit.com", "m.reddit.com", "np.reddit.com", "reddit.com")) {
            assertEquals("https://www.reddit.com/r/privacy/",
                Prefs.migratedStartPage("https://$h/r/privacy/"))
        }
    }

    @Test
    fun `a start page the user set themselves is not hijacked`() {
        // Not a Reddit URL, so not ours to rewrite.
        assertEquals("https://example.org/feed",
            Prefs.migratedStartPage("https://example.org/feed"))
    }

    @Test
    fun `nothing stored means nothing to migrate`() {
        assertNull(Prefs.migratedStartPage(null))
        assertNull(Prefs.migratedStartPage(""))
        assertNull(Prefs.migratedStartPage("   "))
    }

    @Test
    fun `the default start page is on the canonical host`() {
        assertTrue(Prefs.DEFAULT_START_PAGE.startsWith("https://www.reddit.com/"))
    }
}
