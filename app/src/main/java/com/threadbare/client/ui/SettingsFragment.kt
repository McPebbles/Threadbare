package com.threadbare.client.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.threadbare.client.R
import com.threadbare.client.web.WebViewSetup

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }

    override fun onPause() {
        super.onPause()
        // The stylesheet is cached; drop it so a toggled suppressor is picked
        // up on the next page load.
        WebViewSetup.invalidateCssCache()
    }
}
