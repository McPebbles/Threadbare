package com.threadbare.client.util

/**
 * The one site this app wraps, and the handful of shortcuts the overflow menu
 * offers. Kept separate from UrlRules so the routing logic has no opinion about
 * which pages are interesting.
 */
object Sites {

    const val HOME = "https://www.reddit.com/"
    const val POPULAR = "https://www.reddit.com/r/popular/"
    const val ALL = "https://www.reddit.com/r/all/"
    const val SAVED = "https://www.reddit.com/saved/"
    const val LOGIN = "https://www.reddit.com/login"

    /** Build a subreddit URL from user input like "r/GrapheneOS", "GrapheneOS". */
    fun subredditUrl(raw: String): String? {
        val name = raw.trim()
            .removePrefix("/")
            .removePrefix("r/")
            .removePrefix("R/")
            .trim('/')
        if (name.isEmpty()) return null
        if (!name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '+' }) return null
        return "https://www.reddit.com/r/$name/"
    }
}
