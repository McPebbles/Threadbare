package com.threadbare.client.util

/**
 * Which settings only take effect when the app starts again, and whether a
 * given one is currently out of step with the running process.
 *
 * Three of this app's switches are read when the WebView is built — the
 * document-start scripts are attached then and cannot be attached to a page
 * that is already loading — so toggling one changes the stored value and
 * nothing the reader can see. That has already cost a round of confusion in
 * this project's history: a setting was toggled, the page did not change, and
 * the conclusion drawn was about Reddit rather than about the app.
 *
 * The rule the user asked for, stated precisely:
 *
 * - changing one of these offers a restart;
 * - declining keeps the new value and marks it as not yet in effect;
 * - returning it to **the value it had when this process started** clears the
 *   mark, because at that point the running app already matches the setting.
 *
 * That last clause is why the baseline is a snapshot taken at launch rather
 * than the previous value: two toggles that cancel out leave nothing pending,
 * and no note should be left behind claiming otherwise.
 *
 * Deliberately free of `android.*` so the comparison is unit-testable.
 */
object RestartGate {

    /**
     * Read in [com.threadbare.client.web.WebViewSetup.startupScripts] and so
     * fixed for the life of the WebView.
     */
    val KEYS: List<String> = listOf(
        Prefs.KEY_SUPPRESS_XPROMO,
        Prefs.KEY_REVEAL_ADULT,
        Prefs.KEY_BLOCK_BUNDLES,
        Prefs.KEY_DIAGNOSTICS,
        Prefs.KEY_BYPASS_AGE_GATE,
    )

    fun guards(key: String?): Boolean = key != null && key in KEYS

    /**
     * The keys whose current value differs from the launch snapshot.
     *
     * A key absent from [launch] is treated as unchanged: it was not read at
     * launch, so nothing about the running process depends on it.
     */
    fun pending(launch: Map<String, Boolean>, current: Map<String, Boolean>): Set<String> {
        val out = LinkedHashSet<String>()
        for (key in KEYS) {
            val was = launch[key] ?: continue
            val now = current[key] ?: continue
            if (was != now) out += key
        }
        return out
    }

    /** True when this one key is out of step, which is what the note hangs on. */
    fun isPending(key: String, launch: Map<String, Boolean>, current: Map<String, Boolean>): Boolean =
        pending(launch, current).contains(key)
}
