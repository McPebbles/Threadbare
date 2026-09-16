package com.threadbare.client.ui

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.threadbare.client.R
import com.threadbare.client.web.BrowserChoice
import com.threadbare.client.web.BrowserLauncher
import com.threadbare.client.web.WebViewSetup

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        populateBrowsers()
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
        // The stylesheet is cached; drop it so a toggled suppressor is picked
        // up on the next page load.
        WebViewSetup.invalidateCssCache()
    }

    private companion object {
        const val BROWSER_KEY = "link_browser"
    }
}
