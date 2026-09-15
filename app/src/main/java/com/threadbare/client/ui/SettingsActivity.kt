package com.threadbare.client.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.threadbare.client.R
import com.threadbare.client.shell.Frame

/**
 * Settings.
 *
 * This screen is the one Tombot got wrong: it was left on a stock ActionBar
 * theme with no edge-to-edge and no inset handling while the main screen was
 * carefully fixed, and the review that "fixed the frame" never opened it. So
 * this activity uses the same [Frame] as MainActivity, with a real bar in its
 * own layout, and `tools/verify_frame.py` asserts as much.
 *
 * The bar is brand orange here rather than the neutral surface the main screen
 * uses: settings belong to the app, not the site. The white system icons come
 * from Frame's luminance rule with nothing hard-coded.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var frame: Frame

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.settingsHost),
        )
        frame.install()
        applyThemeColours()

        findViewById<android.widget.ImageButton>(R.id.buttonBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsHost, SettingsFragment())
                .commit()
        }
    }

    private fun applyThemeColours() {
        frame.applyBarColour(ContextCompat.getColor(this, R.color.accent))
    }

    override fun onResume() {
        super.onResume()
        applyThemeColours()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemeColours()
        frame.requestInsets()
    }
}
