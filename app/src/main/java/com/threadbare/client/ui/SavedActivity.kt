package com.threadbare.client.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.threadbare.client.MainActivity
import com.threadbare.client.R
import com.threadbare.client.shell.Frame
import com.threadbare.client.util.SavedItem
import com.threadbare.client.util.SavedKind
import com.threadbare.client.util.SavedStore

/**
 * Saved communities or saved posts, full screen.
 *
 * The first version of this was an AlertDialog over the WebView. Two things
 * were wrong with it, both reported from the device:
 *
 *  - A bookmark list is a place you go, not a prompt. It now has its own
 *    screen, with the same [Frame] as every other screen so the audit in
 *    `tools/verify_frame.py` covers it.
 *  - Rows did not respond to taps. A focusable child inside a ListView row —
 *    the remove button — takes the click, and `onItemClick` never fires. The
 *    row layout now blocks descendant focus; see `row_saved.xml`.
 *
 * Tapping a row hands the URL to [MainActivity] through an explicit VIEW
 * intent. MainActivity is `singleTask`, so this reaches its `onNewIntent`,
 * which goes through UrlRules like every other URL and loads in the app —
 * never the browser.
 */
class SavedActivity : AppCompatActivity() {

    private lateinit var frame: Frame
    private lateinit var kind: SavedKind
    private val items = ArrayList<SavedItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved)

        kind = SavedKind.fromKey(intent?.getStringExtra(EXTRA_KIND)) ?: SavedKind.SUBREDDIT

        frame = Frame(
            activity = this,
            barWrapper = findViewById(R.id.topBarWrapper),
            barRow = findViewById(R.id.topBarRow),
            content = findViewById(R.id.savedHost),
        )
        frame.install()
        applyThemeColours()

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<TextView>(R.id.savedHeading).setText(
            if (kind == SavedKind.SUBREDDIT) R.string.saved_subreddits_title
            else R.string.saved_posts_title
        )
        findViewById<TextView>(R.id.savedEmpty).setText(
            if (kind == SavedKind.SUBREDDIT) R.string.saved_none_subreddits
            else R.string.saved_none_posts
        )

        val list: ListView = findViewById(R.id.savedList)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { open(it) }
        }
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(SavedStore.of(this, kind))
        adapter.notifyDataSetChanged()
        val empty = items.isEmpty()
        findViewById<View>(R.id.savedList).visibility = if (empty) View.GONE else View.VISIBLE
        findViewById<View>(R.id.savedEmpty).visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun open(item: SavedItem) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(item.url)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(this@SavedActivity)
                .inflate(R.layout.row_saved, parent, false)
            val item = items[position]
            row.findViewById<TextView>(R.id.savedTitle).text = item.title
            row.findViewById<ImageButton>(R.id.savedRemove).setOnClickListener {
                SavedStore.remove(this@SavedActivity, item.kind, item.url)
                reload()
            }
            return row
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

    companion object {
        const val EXTRA_KIND = "kind"

        fun intent(from: android.content.Context, kind: SavedKind): Intent =
            Intent(from, SavedActivity::class.java).putExtra(EXTRA_KIND, kind.key)
    }
}
