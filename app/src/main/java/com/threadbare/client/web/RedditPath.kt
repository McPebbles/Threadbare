package com.threadbare.client.web

/**
 * What kind of Reddit page a URL points at, and the pieces worth pulling out of
 * it. Free of `android.*` so every case is exercised on the JVM.
 *
 * Used for two things: deciding which "save" item the overflow menu should
 * offer, and validating what the subreddit box is allowed to navigate to.
 */
object RedditPath {

    enum class Kind { FRONT, SUBREDDIT, POST, USER, SEARCH, OTHER }

    /**
     * Subreddit names Reddit itself accepts: 2–21 of letters, digits and
     * underscore. Multireddits join several with `+`.
     *
     * This is an allowlist, not a sanitiser, and that is the point — it is what
     * makes the navigation box structurally incapable of being a URL bar. A
     * name cannot contain `.`, `/`, `:`, `%`, whitespace or anything else that
     * could steer a load off Reddit, because those characters are not in the
     * set at all.
     */
    private val NAME = Regex("^[A-Za-z0-9_]{2,21}$")

    /** Maximum parts in a multireddit, to keep a pasted mess from becoming a URL. */
    private const val MAX_MULTI_PARTS = 12

    fun kind(url: String?): Kind {
        val path = pathOf(url) ?: return Kind.OTHER
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Kind.FRONT
        return when {
            parts[0].equals("r", true) && parts.size >= 4 &&
                parts[2].equals("comments", true) -> Kind.POST
            parts[0].equals("comments", true) && parts.size >= 2 -> Kind.POST
            parts[0].equals("r", true) && parts.size >= 2 -> Kind.SUBREDDIT
            parts[0].equals("user", true) || parts[0].equals("u", true) -> Kind.USER
            parts[0].equals("search", true) -> Kind.SEARCH
            else -> Kind.OTHER
        }
    }

    /** The subreddit a URL sits in, without the `r/`, or null. */
    fun subredditOf(url: String?): String? {
        val path = pathOf(url) ?: return null
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.size >= 2 && parts[0].equals("r", true)) {
            return parts[1].takeIf { isValidName(it) }
        }
        return null
    }

    /**
     * The canonical permalink for a post, with the slug and any trailing
     * segments (a linked comment, `?context=`) dropped, so saving the same post
     * twice from different entry points saves one item.
     */
    fun postPermalink(url: String?): String? {
        val path = pathOf(url) ?: return null
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        val sub: String
        val id: String
        when {
            parts.size >= 4 && parts[0].equals("r", true) &&
                parts[2].equals("comments", true) -> {
                sub = parts[1]; id = parts[3]
            }
            parts.size >= 2 && parts[0].equals("comments", true) -> {
                return "https://${UrlRules.CANONICAL_HOST}/comments/${parts[1]}/"
            }
            else -> return null
        }
        if (!isValidName(sub) || id.isEmpty()) return null
        return "https://${UrlRules.CANONICAL_HOST}/r/$sub/comments/$id/"
    }

    /** The canonical URL for a subreddit page. */
    fun subredditUrl(name: String): String? {
        val clean = normaliseName(name) ?: return null
        return "https://${UrlRules.CANONICAL_HOST}/r/$clean/"
    }

    /**
     * Accept what a person would plausibly type or paste for a subreddit and
     * return the bare name, or null if it is not one.
     *
     * Forgiving about the wrapping (`r/x`, `/r/x/`, stray spaces, a whole
     * reddit.com URL pasted in) and strict about the result: whatever comes
     * back has passed [NAME]. Anything that is not a Reddit subreddit — a bare
     * domain, a path, a scheme — returns null rather than being coerced into
     * something loadable.
     */
    fun normaliseName(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null

        // A pasted URL is a common and harmless case: take its subreddit and
        // discard everything else. Note this reads the subreddit out of the
        // URL — it never navigates to the URL itself.
        if (s.contains("://")) {
            return subredditOf(s)
        }

        s = s.trimStart('/')
        if (s.startsWith("r/", true)) s = s.substring(2)
        s = s.trim('/').trim()
        if (s.isEmpty()) return null

        if (s.contains('+')) {
            val parts = s.split('+').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.size > MAX_MULTI_PARTS) return null
            if (!parts.all { isValidName(it) }) return null
            return parts.joinToString("+")
        }

        return s.takeIf { isValidName(it) }
    }

    fun isValidName(name: String): Boolean = NAME.matches(name)

    /**
     * Reddit's page titles read `Post title : r/subreddit`, sometimes with an
     * unread count in front. Neither belongs in a saved bookmark's label.
     */
    fun cleanPageTitle(title: String?, fallback: String): String {
        var t = title?.trim().orEmpty()
        if (t.isEmpty()) return fallback

        t = t.replace(Regex("^\\(\\d+\\)\\s*"), "")
        t = t.replace(Regex("\\s*:\\s*r/[A-Za-z0-9_]+\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*[-–]\\s*Reddit\\s*$", RegexOption.IGNORE_CASE), "")
        t = t.trim()

        return t.ifEmpty { fallback }
    }

    // ------------------------------------------------------------- internals

    private fun pathOf(url: String?): String? {
        val u = url ?: return null
        if (!u.contains("://")) return null
        val host = UrlRules.hostOf(u) ?: return null
        if (!UrlRules.isRedditPageHost(host)) return null
        val afterAuthority = u.substringAfter("://", "").substringAfter('/', "")
        return "/" + afterAuthority.substringBefore('?').substringBefore('#')
    }
}
