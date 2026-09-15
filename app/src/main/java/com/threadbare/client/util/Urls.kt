package com.threadbare.client.util

/**
 * Small presentation helpers for URLs. Routing decisions live in
 * `web/UrlRules.kt`; nothing here changes where a load goes.
 */
object Urls {

    /** "www.reddit.com/r/GrapheneOS" -> "r/GrapheneOS", for the title bar. */
    fun shortLabel(url: String?): String {
        val u = url ?: return ""
        val afterHost = u.substringAfter("://", "").substringAfter('/', "")
        val path = afterHost.substringBefore('?').substringBefore('#').trim('/')
        if (path.isEmpty()) return "reddit"
        val parts = path.split('/')
        return when {
            parts.size >= 2 && parts[0] == "r" -> "r/${parts[1]}"
            parts.size >= 2 && parts[0] == "user" -> "u/${parts[1]}"
            else -> parts.first()
        }
    }

    fun isHttp(url: String?): Boolean =
        url != null && (url.startsWith("https://") || url.startsWith("http://"))
}
