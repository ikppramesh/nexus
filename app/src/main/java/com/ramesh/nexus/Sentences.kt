package com.ramesh.nexus

/** Splits text into sentence-sized pieces so speech can start before the whole text is generated. */
object Sentences {
    private const val MAX_CHARS = 280
    private val boundary = Regex("(?<=[.!?…])[\"')\\]]*\\s+|\\n+")

    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in forSpeech(text).split(boundary)) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (s.length <= MAX_CHARS) out += s else out += splitLong(s)
        }
        return mergeShort(out)
    }

    private val listMarker = Regex("""(?m)^\s*(?:[-*•–—]+|\d+[.)]|#+)\s*""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val emoji = Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]")

    /** Strips markdown and emoji that would be read out literally (bullets, numbering, bold, links). */
    private fun forSpeech(text: String): String = text
        .replace(link, "$1")
        .replace(emoji, "")
        .replace(listMarker, "")
        .replace("**", "").replace("__", "").replace("`", "")

    /** Breaks an over-long sentence at commas, then at spaces. */
    private fun splitLong(s: String): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > MAX_CHARS) {
            val window = rest.substring(0, MAX_CHARS)
            val cut = maxOf(window.lastIndexOfAny(charArrayOf(',', ';', ':')), window.lastIndexOf(' '))
                .takeIf { it > MAX_CHARS / 3 } ?: MAX_CHARS
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** Joins fragments like "Ok." onto the next sentence so the voice doesn't sound choppy. */
    private fun mergeShort(parts: List<String>): List<String> {
        val out = mutableListOf<String>()
        var pending = ""
        for (p in parts) {
            pending = if (pending.isEmpty()) p else "$pending $p"
            if (pending.length >= 24) {
                out += pending
                pending = ""
            }
        }
        if (pending.isNotEmpty()) out += pending
        return out
    }

    /** Collects streamed text and hands back each sentence as soon as it is complete. */
    class Streamer {
        private val buffer = StringBuilder()

        fun add(delta: String): List<String> {
            buffer.append(delta)
            val text = buffer.toString()
            val last = boundary.findAll(text).lastOrNull() ?: return emptyList()
            val done = text.substring(0, last.range.last + 1)
            // Wait for a bit more text before speaking a tiny opening fragment on its own.
            if (done.trim().length < 24) return emptyList()
            buffer.delete(0, last.range.last + 1)
            return split(done)
        }

        fun flush(): List<String> = split(buffer.toString()).also { buffer.clear() }
    }
}
