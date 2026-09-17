package com.threadbare.client.util

/**
 * The last few requests this app refused, for the diagnostics report.
 *
 * The DOM report says what the page ended up looking like. It cannot say what
 * the app stopped from arriving, and that turned out to be the other half of
 * the story: a post page whose gate was revealed, whose frame was the right
 * size, and whose media had simply never been fetched. One list of refusals
 * would have said so immediately.
 *
 * A fixed-size ring in memory, never written to disk, and cleared on every
 * navigation so what it shows belongs to the page in front of the reader. It
 * holds URLs the app refused, which are Reddit's own endpoints rather than
 * anything the reader typed — but they are still browsing history, so the ring
 * is small, it is only recorded when the diagnostics switch is on, and it dies
 * with the process.
 */
object RefusalLog {

    private const val CAPACITY = 40

    data class Entry(val url: String, val reason: String)

    private val entries = ArrayDeque<Entry>()

    /** Set from [enabled]; recording is off unless diagnostics are on. */
    @Volatile
    var enabled: Boolean = false

    @Synchronized
    fun record(url: String, reason: String) {
        if (!enabled) return
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(Entry(url.take(300), reason))
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /**
     * Compact JSON, appended to the report. Hand-built rather than pulled in
     * from a library: the whole value here is being able to read it in a
     * dialog on a phone.
     */
    @Synchronized
    fun asJson(): String {
        if (entries.isEmpty()) return "[]"
        val out = StringBuilder("[\n")
        for ((i, e) in entries.withIndex()) {
            out.append("  {\"reason\": \"").append(escape(e.reason))
                .append("\", \"url\": \"").append(escape(e.url)).append("\"}")
            if (i < entries.size - 1) out.append(',')
            out.append('\n')
        }
        return out.append(']').toString()
    }

    private fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append(' ') else out.append(ch)
            }
        }
        return out.toString()
    }
}
