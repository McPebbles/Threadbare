package com.threadbare.client.util

import java.util.Base64

/**
 * Saved subreddits and posts — the app's own bookmarks.
 *
 * Reddit's own "save" needs an account, which is the one thing this app is
 * built to avoid, so the list lives on the device and nothing about it ever
 * leaves it.
 *
 * The encoding and every rule about the list live in [SavedCodec], free of
 * `android.*` so they are exercised on the JVM; [SavedStore] is the thin
 * SharedPreferences wrapper around them.
 */
enum class SavedKind(val key: String) {
    SUBREDDIT("s"),
    POST("p");

    companion object {
        fun fromKey(k: String?): SavedKind? = entries.firstOrNull { it.key == k }
    }
}

data class SavedItem(
    val kind: SavedKind,
    /** What the menu shows. */
    val title: String,
    /** What it navigates to. Also the identity of the item. */
    val url: String,
    val savedAt: Long,
)

object SavedCodec {

    /**
     * Enough for anyone, and a bound on how large the preferences blob can get.
     * The oldest item is dropped when a new one would exceed it.
     */
    const val MAX_PER_KIND = 200

    private const val FIELD = ''
    private const val RECORD = '\n'

    /**
     * Title and URL are Base64'd rather than escaped.
     *
     * A post title can contain anything at all — newlines, separators, the
     * escape character itself — and hand-rolled escaping is exactly the sort of
     * thing that works until someone saves a post with an unusual title and the
     * whole bookmark list silently truncates. `java.util.Base64` is on both the
     * JVM and Android since API 26 (this app is minSdk 30), so it costs nothing
     * to be certain instead.
     */
    fun encode(items: List<SavedItem>): String = items.joinToString(RECORD.toString()) { item ->
        listOf(
            item.kind.key,
            b64(item.title),
            b64(item.url),
            item.savedAt.toString(),
        ).joinToString(FIELD.toString())
    }

    /**
     * Anything unparseable is skipped rather than throwing. A corrupt line
     * should cost the user one bookmark, not the whole list.
     */
    fun decode(raw: String?): List<SavedItem> {
        val text = raw.orEmpty()
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<SavedItem>()
        for (line in text.split(RECORD)) {
            if (line.isBlank()) continue
            val parts = line.split(FIELD)
            if (parts.size < 4) continue
            val kind = SavedKind.fromKey(parts[0]) ?: continue
            val title = unb64(parts[1]) ?: continue
            val url = unb64(parts[2]) ?: continue
            val at = parts[3].toLongOrNull() ?: continue
            if (url.isEmpty()) continue
            out += SavedItem(kind, title, url, at)
        }
        return out
    }

    /**
     * Add or update, newest first, de-duplicated by URL.
     *
     * Re-saving a post that is already saved refreshes its title and moves it
     * to the top rather than creating a second entry — the same post reached
     * from a different link is the same bookmark.
     */
    fun add(existing: List<SavedItem>, item: SavedItem): List<SavedItem> {
        val rest = existing.filterNot { it.kind == item.kind && it.url == item.url }
        val merged = (listOf(item) + rest)
        val sameKind = merged.filter { it.kind == item.kind }.take(MAX_PER_KIND)
        val otherKinds = merged.filter { it.kind != item.kind }
        return sameKind + otherKinds
    }

    fun remove(existing: List<SavedItem>, kind: SavedKind, url: String): List<SavedItem> =
        existing.filterNot { it.kind == kind && it.url == url }

    fun contains(existing: List<SavedItem>, kind: SavedKind, url: String): Boolean =
        existing.any { it.kind == kind && it.url == url }

    fun of(existing: List<SavedItem>, kind: SavedKind): List<SavedItem> =
        existing.filter { it.kind == kind }

    private fun b64(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun unb64(s: String): String? = try {
        String(Base64.getUrlDecoder().decode(s), Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }
}
