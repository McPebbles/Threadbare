package com.threadbare.client.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.threadbare.client.R
import com.threadbare.client.util.SavedItem
import com.threadbare.client.util.SavedKind
import com.threadbare.client.util.SavedStore

/**
 * The saved-bookmarks list.
 *
 * Each row shows the title and nothing else, as asked; the URL rides along on
 * the item and is what the row navigates to. A plain ListView rather than a
 * RecyclerView so this costs no new dependency — the suite keeps its dependency
 * list to AndroidX essentials on purpose.
 */
object SavedDialog {

    fun show(
        activity: Activity,
        kind: SavedKind,
        onOpen: (SavedItem) -> Unit,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_saved, null, false)
        val list: ListView = view.findViewById(R.id.savedList)
        val empty: TextView = view.findViewById(R.id.savedEmpty)

        val items = SavedStore.of(activity, kind).toMutableList()

        val titleRes = if (kind == SavedKind.SUBREDDIT) {
            R.string.saved_subreddits_title
        } else {
            R.string.saved_posts_title
        }
        empty.setText(
            if (kind == SavedKind.SUBREDDIT) R.string.saved_none_subreddits
            else R.string.saved_none_posts
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .create()

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: LayoutInflater.from(activity)
                    .inflate(R.layout.row_saved, parent, false)
                val item = items[position]

                row.findViewById<TextView>(R.id.savedTitle).text = item.title

                row.findViewById<ImageButton>(R.id.savedRemove).setOnClickListener {
                    SavedStore.remove(activity, item.kind, item.url)
                    items.removeAt(position)
                    notifyDataSetChanged()
                    if (items.isEmpty()) {
                        list.visibility = View.GONE
                        empty.visibility = View.VISIBLE
                    }
                }
                return row
            }
        }

        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (position in items.indices) {
                onOpen(items[position])
                dialog.dismiss()
            }
        }

        if (items.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
        }

        dialog.show()
    }
}
