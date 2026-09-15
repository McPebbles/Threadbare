package com.threadbare.client

import com.threadbare.client.util.SavedCodec
import com.threadbare.client.util.SavedItem
import com.threadbare.client.util.SavedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCodecTest {

    private fun item(kind: SavedKind, title: String, url: String, at: Long = 1L) =
        SavedItem(kind, title, url, at)

    @Test
    fun `a round trip preserves everything`() {
        val items = listOf(
            item(SavedKind.SUBREDDIT, "r/privacy", "https://www.reddit.com/r/privacy/"),
            item(SavedKind.POST, "A post", "https://www.reddit.com/r/x/comments/a/", 2L),
        )
        assertEquals(items, SavedCodec.decode(SavedCodec.encode(items)))
    }

    /**
     * The reason titles are Base64'd rather than escaped. A post title can
     * contain literally anything, and hand-rolled escaping works right up until
     * someone saves the post that breaks it and the whole list truncates.
     */
    @Test
    fun `a hostile title survives`() {
        val nasty = "line\nbreak\ttab  sep  rec \\ back \"quotes\" ünicode"
        val items = listOf(item(SavedKind.POST, nasty, "https://www.reddit.com/r/x/comments/a/"))
        val decoded = SavedCodec.decode(SavedCodec.encode(items))
        assertEquals(1, decoded.size)
        assertEquals(nasty, decoded[0].title)
    }

    @Test
    fun `a corrupt line costs one bookmark, not the list`() {
        val good = SavedCodec.encode(
            listOf(
                item(SavedKind.SUBREDDIT, "r/a", "https://www.reddit.com/r/a/"),
                item(SavedKind.SUBREDDIT, "r/b", "https://www.reddit.com/r/b/"),
            ),
        )
        val corrupted = good.split("\n").toMutableList()
            .also { it.add(1, "nonsense!!") }
            .joinToString("\n")
        assertEquals(2, SavedCodec.decode(corrupted).size)
    }

    @Test
    fun `empty input decodes to nothing rather than throwing`() {
        assertTrue(SavedCodec.decode(null).isEmpty())
        assertTrue(SavedCodec.decode("").isEmpty())
        assertTrue(SavedCodec.decode("\n\n").isEmpty())
    }

    @Test
    fun `re-saving updates in place and moves to the top`() {
        val a = item(SavedKind.POST, "old title", "https://www.reddit.com/r/x/comments/a/", 1L)
        val b = item(SavedKind.POST, "other", "https://www.reddit.com/r/x/comments/b/", 2L)
        val again = item(SavedKind.POST, "new title", "https://www.reddit.com/r/x/comments/a/", 3L)

        val list = SavedCodec.add(SavedCodec.add(listOf(a), b), again)
        assertEquals(2, list.size)
        assertEquals("new title", list[0].title)
        assertEquals("https://www.reddit.com/r/x/comments/a/", list[0].url)
    }

    @Test
    fun `the two kinds do not collide`() {
        val url = "https://www.reddit.com/r/x/"
        var list = SavedCodec.add(emptyList(), item(SavedKind.SUBREDDIT, "r/x", url))
        list = SavedCodec.add(list, item(SavedKind.POST, "post", url))
        assertEquals(2, list.size)
        assertTrue(SavedCodec.contains(list, SavedKind.SUBREDDIT, url))
        assertTrue(SavedCodec.contains(list, SavedKind.POST, url))

        list = SavedCodec.remove(list, SavedKind.POST, url)
        assertTrue(SavedCodec.contains(list, SavedKind.SUBREDDIT, url))
        assertFalse(SavedCodec.contains(list, SavedKind.POST, url))
    }

    @Test
    fun `the list is capped per kind and drops the oldest`() {
        var list = emptyList<SavedItem>()
        for (i in 1..SavedCodec.MAX_PER_KIND + 20) {
            list = SavedCodec.add(
                list,
                item(
                    SavedKind.POST,
                    "post $i",
                    "https://www.reddit.com/r/x/comments/$i/",
                    i.toLong(),
                ),
            )
        }
        val posts = SavedCodec.of(list, SavedKind.POST)
        assertEquals(SavedCodec.MAX_PER_KIND, posts.size)
        assertEquals("post ${SavedCodec.MAX_PER_KIND + 20}", posts.first().title)
    }

    @Test
    fun `capping one kind does not evict the other`() {
        var list = SavedCodec.add(
            emptyList(),
            item(SavedKind.SUBREDDIT, "r/keep", "https://www.reddit.com/r/keep/"),
        )
        for (i in 1..SavedCodec.MAX_PER_KIND + 5) {
            list = SavedCodec.add(
                list,
                item(SavedKind.POST, "p$i", "https://www.reddit.com/r/x/comments/$i/", i.toLong()),
            )
        }
        assertEquals(1, SavedCodec.of(list, SavedKind.SUBREDDIT).size)
    }
}
