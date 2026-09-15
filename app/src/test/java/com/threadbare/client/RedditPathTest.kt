package com.threadbare.client

import com.threadbare.client.web.RedditPath
import com.threadbare.client.web.RedditPath.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditPathTest {

    // ------------------------------------------------------ classification

    @Test
    fun `page kinds are recognised`() {
        assertEquals(Kind.FRONT, RedditPath.kind("https://www.reddit.com/"))
        assertEquals(Kind.SUBREDDIT, RedditPath.kind("https://www.reddit.com/r/privacy/"))
        assertEquals(Kind.SUBREDDIT, RedditPath.kind("https://www.reddit.com/r/privacy/new/"))
        assertEquals(
            Kind.POST,
            RedditPath.kind("https://www.reddit.com/r/privacy/comments/abc123/some_title/"),
        )
        assertEquals(Kind.USER, RedditPath.kind("https://www.reddit.com/user/someone/"))
        assertEquals(Kind.SEARCH, RedditPath.kind("https://www.reddit.com/search?q=x"))
    }

    @Test
    fun `a post inside a subreddit is a post, not a subreddit`() {
        // The overflow offers a different action for each, so this ordering
        // matters more than it looks.
        val url = "https://www.reddit.com/r/privacy/comments/abc123/title/"
        assertEquals(Kind.POST, RedditPath.kind(url))
        // ...but the subreddit is still extractable, so both save options work.
        assertEquals("privacy", RedditPath.subredditOf(url))
    }

    @Test
    fun `nothing off reddit is classified`() {
        assertEquals(Kind.OTHER, RedditPath.kind("https://example.org/r/privacy/"))
        assertEquals(Kind.OTHER, RedditPath.kind(null))
        assertNull(RedditPath.subredditOf("https://reddit.com.evil.example/r/x/"))
    }

    // ---------------------------------------------------------- permalinks

    @Test
    fun `a permalink drops the slug and trailing segments`() {
        val expected = "https://www.reddit.com/r/privacy/comments/abc123/"
        for (url in listOf(
            "https://www.reddit.com/r/privacy/comments/abc123/some_long_slug/",
            "https://www.reddit.com/r/privacy/comments/abc123/some_long_slug/def456/",
            "https://www.reddit.com/r/privacy/comments/abc123/some_long_slug/?context=3",
            "https://old.reddit.com/r/privacy/comments/abc123/some_long_slug/",
        )) {
            assertEquals("permalink of $url", expected, RedditPath.postPermalink(url))
        }
    }

    @Test
    fun `the same post reached two ways saves as one bookmark`() {
        assertEquals(
            RedditPath.postPermalink("https://www.reddit.com/r/x/comments/a1/title/"),
            RedditPath.postPermalink("https://sh.reddit.com/r/x/comments/a1/other/?sort=new"),
        )
    }

    @Test
    fun `a non-post has no permalink`() {
        assertNull(RedditPath.postPermalink("https://www.reddit.com/r/privacy/"))
        assertNull(RedditPath.postPermalink("https://www.reddit.com/"))
    }

    // ------------------------------------------- the subreddit box's input

    /**
     * The whole "it is not a URL bar" claim lives or dies here. Every input
     * below is something a user could type or paste into the box; none of them
     * may produce anything that navigates off Reddit.
     */
    @Test
    fun `nothing that is not a subreddit name is accepted`() {
        for (bad in listOf(
            "example.com",
            "https://example.com",
            "http://evil.example/r/privacy",
            "//evil.example",
            "evil.example/r/x",
            "../../etc/passwd",
            "..",
            ".",
            "r/../../x",
            "javascript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "file:///sdcard",
            "reddit.com@evil.example",
            "x y",
            "privacy?q=1",
            "privacy#frag",
            "privacy%2F..",
            "%2e%2e%2f",
            "пример",
            "a".repeat(22),
            "a",
            "",
            "   ",
            "+",
            "++",
        )) {
            assertNull("must reject: $bad", RedditPath.normaliseName(bad))
        }
    }

    @Test
    fun `ordinary names are accepted`() {
        assertEquals("privacy", RedditPath.normaliseName("privacy"))
        assertEquals("GrapheneOS", RedditPath.normaliseName("GrapheneOS"))
        assertEquals("privacy", RedditPath.normaliseName("r/privacy"))
        assertEquals("privacy", RedditPath.normaliseName("/r/privacy/"))
        assertEquals("privacy", RedditPath.normaliseName("  R/privacy  "))
        assertEquals("a_b", RedditPath.normaliseName("a_b"))
        assertEquals("aa", RedditPath.normaliseName("aa"))
    }

    @Test
    fun `multireddits are accepted, within reason`() {
        assertEquals("privacy+GrapheneOS", RedditPath.normaliseName("privacy+GrapheneOS"))
        // A pasted mess of pluses is not a multireddit.
        assertNull(RedditPath.normaliseName((1..20).joinToString("+") { "sub$it" }))
        assertNull(RedditPath.normaliseName("privacy+evil.example"))
    }

    /**
     * Pasting a Reddit URL is the obvious thing to try, so it is supported —
     * but by reading the subreddit *out* of the URL, never by navigating to the
     * URL itself. A non-Reddit URL yields nothing.
     */
    @Test
    fun `a pasted reddit url yields its subreddit and nothing else`() {
        assertEquals(
            "privacy",
            RedditPath.normaliseName("https://www.reddit.com/r/privacy/comments/a/b/"),
        )
        assertEquals("privacy", RedditPath.normaliseName("https://old.reddit.com/r/privacy/"))
        assertNull(RedditPath.normaliseName("https://evil.example/r/privacy/"))
        assertNull(RedditPath.normaliseName("https://www.reddit.com/"))
    }

    @Test
    fun `an accepted name always builds a url on the canonical host`() {
        for (name in listOf("privacy", "GrapheneOS", "a_b", "privacy+GrapheneOS")) {
            val url = RedditPath.subredditUrl(name)!!
            assertTrue(url, url.startsWith("https://www.reddit.com/r/"))
        }
        assertNull(RedditPath.subredditUrl("evil.example"))
    }

    // -------------------------------------------------------------- titles

    @Test
    fun `reddit's title suffix is stripped for a bookmark label`() {
        assertEquals(
            "Hardening a workstation",
            RedditPath.cleanPageTitle("Hardening a workstation : r/privacy", "fallback"),
        )
        assertEquals(
            "Hardening a workstation",
            RedditPath.cleanPageTitle("(3) Hardening a workstation : r/privacy", "fallback"),
        )
        assertEquals("Some post", RedditPath.cleanPageTitle("Some post - Reddit", "fallback"))
    }

    @Test
    fun `an empty or missing title falls back`() {
        assertEquals("fb", RedditPath.cleanPageTitle(null, "fb"))
        assertEquals("fb", RedditPath.cleanPageTitle("   ", "fb"))
        assertEquals("fb", RedditPath.cleanPageTitle(": r/privacy", "fb"))
    }
}
