package com.threadbare.client.ui

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.threadbare.client.App
import com.threadbare.client.R
import com.threadbare.client.util.Prefs
import com.threadbare.client.util.RestartGate
import com.threadbare.client.web.BrowserChoice
import com.threadbare.client.web.BrowserLauncher
import com.threadbare.client.web.WebViewSetup

class SettingsFragment : PreferenceFragmentCompat() {

    /** Each gated preference's own summary, so the note can be added and taken away. */
    private val baseSummaries = HashMap<String, CharSequence?>()

    private val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (RestartGate.guards(key)) onGatedChange(key!!)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        populateBrowsers()
        for (key in RestartGate.KEYS) {
            baseSummaries[key] = findPreference<Preference>(key)?.summary
        }
        refreshNotes()
    }

    override fun onResume() {
        super.onResume()
        Prefs.of(requireContext()).registerOnSharedPreferenceChangeListener(watcher)
        refreshNotes()
    }

    // ------------------------------------------------ settings that need a restart

    /**
     * Three switches are read when the WebView is built, so toggling one
     * changes nothing on screen until the app starts again. Saying so is the
     * whole point: a setting that appears to do nothing is worse than one that
     * asks for a restart.
     */
    private fun onGatedChange(key: String) {
        refreshNotes()
        val context = context ?: return
        val current = Prefs.snapshot(context, RestartGate.KEYS)
        // Toggled back to what the running app already does — nothing to
        // restart for, and the note has just been cleared by refreshNotes().
        if (!RestartGate.isPending(key, App.launchSnapshot, current)) return

        AlertDialog.Builder(context)
            .setTitle(R.string.restart_title)
            .setMessage(R.string.restart_message)
            .setPositiveButton(R.string.restart_now) { _, _ ->
                activity?.let { AppRestart.restart(it) }
            }
            .setNegativeButton(R.string.restart_later, null)
            .show()
    }

    /** Add or remove the "not in effect yet" note on each gated preference. */
    private fun refreshNotes() {
        val context = context ?: return
        val current = Prefs.snapshot(context, RestartGate.KEYS)
        val pending = RestartGate.pending(App.launchSnapshot, current)
        for (key in RestartGate.KEYS) {
            val pref = findPreference<Preference>(key) ?: continue
            val base = baseSummaries[key] ?: pref.summary
            pref.summary = if (key in pending) {
                getString(R.string.restart_pending, base ?: "")
            } else {
                base
            }
        }
    }

    /**
     * The browser picker's entries come from the device, so they cannot live in
     * `arrays.xml`. `tools/verify_resources.py` knows this preference is filled
     * in here and fails the build if that stops being true — a ListPreference
     * with no entries from either source is an empty dialog.
     *
     * Each browser is labelled with what it can actually do about privacy,
     * because this list is where someone decides which browser to trust with
     * links they did not choose to follow. Those labels are claims, so
     * "always private" is only ever attached to a browser whose every session
     * is disposable — see [BrowserChoice.noteFor].
     */
    private fun populateBrowsers() {
        val pref = findPreference<ListPreference>(BROWSER_KEY) ?: return
        val context = pref.context

        val installed = BrowserLauncher.installedBrowsers(context)

        val labels = ArrayList<CharSequence>(installed.size + 2)
        val values = ArrayList<CharSequence>(installed.size + 2)

        labels += getString(R.string.pref_link_browser_system)
        values += BrowserChoice.SYSTEM_DEFAULT

        for (browser in installed) {
            labels += when (BrowserChoice.noteFor(browser.packageName)) {
                BrowserChoice.PrivacyNote.ALWAYS_PRIVATE ->
                    getString(R.string.browser_always_private, browser.label)
                BrowserChoice.PrivacyNote.CAN_OPEN_PRIVATE ->
                    getString(R.string.browser_can_private, browser.label)
                BrowserChoice.PrivacyNote.NO_PRIVATE -> browser.label
            }
            values += browser.packageName
        }

        // A browser that was chosen and has since been uninstalled is kept in
        // the list, marked. Dropping it would leave the setting showing nothing
        // with no explanation of why the choice stopped being honoured.
        val stale = BrowserChoice.resolve(pref.value, installed).stalePackage
        if (stale != null) {
            labels += getString(R.string.browser_not_installed, stale)
            values += stale
        }

        pref.entries = labels.toTypedArray()
        pref.entryValues = values.toTypedArray()
        pref.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    }

    override fun onPause() {
        super.onPause()
        Prefs.of(requireContext()).unregisterOnSharedPreferenceChangeListener(watcher)
        // The stylesheet is cached; drop it so a toggled suppressor is picked
        // up on the next page load.
        WebViewSetup.invalidateCssCache()
    }

    private companion object {
        const val BROWSER_KEY = "link_browser"
    }
}
