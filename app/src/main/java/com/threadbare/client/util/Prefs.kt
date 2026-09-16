package com.threadbare.client.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.threadbare.client.privacy.BlockMode
import com.threadbare.client.web.BrowserChoice
import com.threadbare.client.web.UrlRules

/**
 * Every stored setting, with its default, in one place.
 *
 * Defaults are materialised once, on first run, and recorded with a schema
 * version. **That is exactly why changing a default is not enough**: on a device
 * that has already run an earlier version, the stored value wins and the new
 * default is never seen.
 *
 * This bit the app for real. Version 1.0.0 shipped `start_page` pointing at
 * `https://old.reddit.com/`. 1.1.0 moved the whole app to www — Reddit put
 * old.reddit behind a login in July 2026 — but left SCHEMA_VERSION at 1, so an
 * upgraded device kept the old stored start page, loaded old.reddit on launch,
 * and the first thing the user saw was "log in to use old Reddit". A fresh
 * install was fine, which is what makes this class of bug so easy to miss.
 *
 * SupplyChain 1.1.0 needed a migration for the identical reason and it is
 * written up in `claude/supplychain.md`. The rule, restated so the next person
 * does not have to rediscover it a third time:
 *
 *   **Changing a default is a schema change. Bump SCHEMA_VERSION and write the
 *   migration, or the change only reaches devices that never ran the old build.**
 */
object Prefs {

    /**
     * 2 — start_page rehomed from old.reddit to www, and the dead keys from the
     *     old.reddit skin removed.
     * 3 — private_tab_mode added.
     * 4 — link_browser added.
     *
     * Note 3 exists at all only because adding a key with a default is the same
     * schema change as altering one: without the bump, `materialiseDefaults`
     * returns early on every existing install and the new preference is absent
     * rather than defaulted. That is the 1.0.0 bug in its other form.
     */
    const val SCHEMA_VERSION = 4
    private const val KEY_SCHEMA = "schema_version"

    /** Keys that existed in 1.0.0 and no longer mean anything. */
    private val RETIRED_KEYS = listOf("skin_enabled", "text_scale")

    const val KEY_START_PAGE = "start_page"
    const val KEY_SUPPRESS_XPROMO = "suppress_xpromo"
    const val KEY_BLOCK_BUNDLES = "block_xpromo_bundles"
    const val KEY_UA_MODE = "ua_mode"
    const val KEY_BLOCK_MODE = "block_mode"
    const val KEY_EPHEMERAL = "ephemeral_session"
    const val KEY_BYPASS_AGE_GATE = "bypass_age_gate"
    const val KEY_OPEN_EXTERNAL = "open_external_in_browser"
    const val KEY_PRIVATE_TAB = "private_tab_mode"
    const val KEY_LINK_BROWSER = "link_browser"

    const val DEFAULT_START_PAGE = "https://www.reddit.com/"

    const val UA_MOBILE = "mobile"
    const val UA_DESKTOP = "desktop"

    const val PRIVATE_NEVER = "never"
    const val PRIVATE_ASK = "ask"
    const val PRIVATE_ALWAYS = "always"

    fun of(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /**
     * Write defaults once, and migrate anything an earlier version stored.
     *
     * The `contains` guards below mean a value the user chose is never
     * overwritten; the migration above them is the part that has to run even
     * when a value is present, because the *stored* value is what is wrong.
     */
    fun materialiseDefaults(context: Context) {
        val p = of(context)
        val stored = p.getInt(KEY_SCHEMA, 0)
        if (stored >= SCHEMA_VERSION) return

        p.edit().apply {
            if (stored in 1 until SCHEMA_VERSION) {
                // A start page saved by 1.0.0 points at a host that now answers
                // with a login wall. Rehome it rather than leaving the user on
                // a dead surface with no clue why.
                val page = p.getString(KEY_START_PAGE, null)
                val migrated = migratedStartPage(page)
                if (migrated != null && migrated != page) putString(KEY_START_PAGE, migrated)

                for (dead in RETIRED_KEYS) if (p.contains(dead)) remove(dead)
            }

            if (!p.contains(KEY_START_PAGE)) putString(KEY_START_PAGE, DEFAULT_START_PAGE)
            if (!p.contains(KEY_SUPPRESS_XPROMO)) putBoolean(KEY_SUPPRESS_XPROMO, true)
            if (!p.contains(KEY_BLOCK_BUNDLES)) putBoolean(KEY_BLOCK_BUNDLES, true)
            if (!p.contains(KEY_UA_MODE)) putString(KEY_UA_MODE, UA_MOBILE)
            if (!p.contains(KEY_BLOCK_MODE)) putString(KEY_BLOCK_MODE, BlockMode.BALANCED.key)
            if (!p.contains(KEY_EPHEMERAL)) putBoolean(KEY_EPHEMERAL, true)
            if (!p.contains(KEY_BYPASS_AGE_GATE)) putBoolean(KEY_BYPASS_AGE_GATE, true)
            if (!p.contains(KEY_OPEN_EXTERNAL)) putBoolean(KEY_OPEN_EXTERNAL, true)
            if (!p.contains(KEY_PRIVATE_TAB)) putString(KEY_PRIVATE_TAB, PRIVATE_ASK)
            if (!p.contains(KEY_LINK_BROWSER)) {
                putString(KEY_LINK_BROWSER, BrowserChoice.SYSTEM_DEFAULT)
            }
            putInt(KEY_SCHEMA, SCHEMA_VERSION)
        }.apply()
    }

    /**
     * Rehome a stored start page onto the canonical host.
     *
     * Free of `android.*` so the migration itself is testable on the JVM — the
     * bug this exists to fix was invisible precisely because nothing tested it.
     *
     * Returns null when there is nothing stored; returns the input unchanged
     * when it is already fine or is not a Reddit URL at all (a user who typed
     * their own start page keeps it).
     */
    fun migratedStartPage(stored: String?): String? {
        val raw = stored?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return UrlRules.canonical(raw) ?: raw
    }

    /**
     * Canonicalised on the way out as well as on migration.
     *
     * Belt and braces on purpose: the migration fixes what is stored, and this
     * makes a stale or hand-edited value harmless even if the migration never
     * ran. Two cheap guards, because the failure they prevent is the app opening
     * on a login wall.
     */
    fun startPage(context: Context): String {
        val stored = of(context).getString(KEY_START_PAGE, DEFAULT_START_PAGE)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_START_PAGE
        return UrlRules.canonical(stored) ?: stored
    }

    /** Layers 2 and 3: the stylesheet and the observer. */
    fun suppressXpromo(context: Context): Boolean =
        of(context).getBoolean(KEY_SUPPRESS_XPROMO, true)

    /** Layer 1: refuse the promotion bundles at the network. */
    fun blockXpromoBundles(context: Context): Boolean =
        of(context).getBoolean(KEY_BLOCK_BUNDLES, true)

    /**
     * The manual escape hatch. Mobile is the device's own WebView UA, untouched
     * — which is both the native layout and the least distinctive thing to send.
     * Desktop is what to reach for if a future Reddit build outruns the
     * suppressor, since xpromo is cross-promotion to the app and a desktop
     * client should never be offered it.
     */
    fun desktopMode(context: Context): Boolean =
        of(context).getString(KEY_UA_MODE, UA_MOBILE) == UA_DESKTOP

    fun blockMode(context: Context): BlockMode =
        BlockMode.fromKey(of(context).getString(KEY_BLOCK_MODE, BlockMode.BALANCED.key))

    /** Clear cookies, storage and cache on exit. The default. */
    fun ephemeralSession(context: Context): Boolean =
        of(context).getBoolean(KEY_EPHEMERAL, true)

    fun bypassAgeGate(context: Context): Boolean =
        of(context).getBoolean(KEY_BYPASS_AGE_GATE, true)

    fun openExternalInBrowser(context: Context): Boolean =
        of(context).getBoolean(KEY_OPEN_EXTERNAL, true)

    /**
     * never / ask / always. Defaults to ask, because "always" cannot be honoured
     * on every device — most Chromium browsers refuse a third-party private
     * launch outright — and a default that silently fails is worse than one
     * that puts the choice in front of the user. See `web/PrivateTabs.kt`.
     */
    fun privateTabMode(context: Context): String =
        of(context).getString(KEY_PRIVATE_TAB, PRIVATE_ASK) ?: PRIVATE_ASK

    /**
     * Which browser gets this app's external links, as a package name, or
     * [BrowserChoice.SYSTEM_DEFAULT] for "whatever Android would do".
     *
     * Separate from the system default on purpose: a reader may well want their
     * ordinary browser for everything else and a disposable one for links a
     * Reddit thread happened to point at. Resolution — including the case where
     * that browser has since been uninstalled — is [BrowserLauncher.chosen].
     */
    fun linkBrowser(context: Context): String =
        of(context).getString(KEY_LINK_BROWSER, BrowserChoice.SYSTEM_DEFAULT)
            ?: BrowserChoice.SYSTEM_DEFAULT
}
