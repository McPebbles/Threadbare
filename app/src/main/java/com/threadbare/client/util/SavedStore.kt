package com.threadbare.client.util

import android.content.Context

/**
 * The SharedPreferences side of the saved list. All the rules live in
 * [SavedCodec]; this only reads and writes.
 */
object SavedStore {

    private const val KEY = "saved_items"

    fun all(context: Context): List<SavedItem> =
        SavedCodec.decode(Prefs.of(context).getString(KEY, null))

    fun of(context: Context, kind: SavedKind): List<SavedItem> =
        SavedCodec.of(all(context), kind)

    fun contains(context: Context, kind: SavedKind, url: String): Boolean =
        SavedCodec.contains(all(context), kind, url)

    fun add(context: Context, item: SavedItem) {
        write(context, SavedCodec.add(all(context), item))
    }

    fun remove(context: Context, kind: SavedKind, url: String) {
        write(context, SavedCodec.remove(all(context), kind, url))
    }

    private fun write(context: Context, items: List<SavedItem>) {
        Prefs.of(context).edit().putString(KEY, SavedCodec.encode(items)).apply()
    }
}
