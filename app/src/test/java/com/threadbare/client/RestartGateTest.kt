package com.threadbare.client

import com.threadbare.client.util.Prefs
import com.threadbare.client.util.RestartGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note on a settings screen is a claim about the app's current behaviour,
 * so it is asserted rather than assumed.
 *
 * The subtle clause is the one about reverting: the baseline is what the
 * setting was when the process started, not what it was a moment ago. Two
 * toggles that cancel out must leave nothing pending, or the app tells the user
 * to restart for a change it has already got.
 */
class RestartGateTest {

    private fun map(vararg pairs: Pair<String, Boolean>) = linkedMapOf(*pairs)

    @Test
    fun `the gated keys are the ones read when the WebView is built`() {
        assertEquals(
            listOf(Prefs.KEY_SUPPRESS_XPROMO, Prefs.KEY_REVEAL_ADULT, Prefs.KEY_BLOCK_BUNDLES),
            RestartGate.KEYS,
        )
    }

    @Test
    fun `an ordinary preference is not gated`() {
        assertFalse(RestartGate.guards(Prefs.KEY_LINK_BROWSER))
        assertFalse(RestartGate.guards(Prefs.KEY_START_PAGE))
        assertFalse(RestartGate.guards(null))
        assertTrue(RestartGate.guards(Prefs.KEY_REVEAL_ADULT))
    }

    @Test
    fun `nothing is pending when the settings match the running process`() {
        val launch = map(Prefs.KEY_SUPPRESS_XPROMO to true, Prefs.KEY_REVEAL_ADULT to true)
        assertTrue(RestartGate.pending(launch, launch).isEmpty())
    }

    @Test
    fun `a changed setting is pending, and only that one`() {
        val launch = map(Prefs.KEY_SUPPRESS_XPROMO to true, Prefs.KEY_REVEAL_ADULT to true)
        val current = map(Prefs.KEY_SUPPRESS_XPROMO to true, Prefs.KEY_REVEAL_ADULT to false)
        assertEquals(setOf(Prefs.KEY_REVEAL_ADULT), RestartGate.pending(launch, current))
        assertTrue(RestartGate.isPending(Prefs.KEY_REVEAL_ADULT, launch, current))
        assertFalse(RestartGate.isPending(Prefs.KEY_SUPPRESS_XPROMO, launch, current))
    }

    /** The clause the user asked for in as many words. */
    @Test
    fun `toggling back to the launch value clears the note`() {
        val launch = map(Prefs.KEY_REVEAL_ADULT to true)
        val off = map(Prefs.KEY_REVEAL_ADULT to false)
        assertTrue(RestartGate.pending(launch, off).isNotEmpty())
        assertTrue(RestartGate.pending(launch, launch).isEmpty())
    }

    @Test
    fun `a key the process never read is never pending`() {
        // Nothing about the running app depends on it, so demanding a restart
        // for it would be a lie.
        assertTrue(RestartGate.pending(emptyMap(), map(Prefs.KEY_REVEAL_ADULT to false)).isEmpty())
    }

    @Test
    fun `several changes are all reported, in a stable order`() {
        val launch = map(
            Prefs.KEY_SUPPRESS_XPROMO to true,
            Prefs.KEY_REVEAL_ADULT to true,
            Prefs.KEY_BLOCK_BUNDLES to true,
        )
        val current = map(
            Prefs.KEY_SUPPRESS_XPROMO to false,
            Prefs.KEY_REVEAL_ADULT to true,
            Prefs.KEY_BLOCK_BUNDLES to false,
        )
        assertEquals(
            listOf(Prefs.KEY_SUPPRESS_XPROMO, Prefs.KEY_BLOCK_BUNDLES),
            RestartGate.pending(launch, current).toList(),
        )
    }
}
