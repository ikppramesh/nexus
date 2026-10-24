package com.ramesh.nexus

import org.json.JSONObject
import java.io.File

/**
 * GPT-2 byte-level BPE, matching Chatterbox Turbo's tokenizer.json (including its added tags such as
 * "[laugh]" and the two trailing <|endoftext|> tokens its post-processor appends).
 */
class Gpt2Tokenizer(file: File) {
    private val vocab = HashMap<String, Int>(60000)
    private val ranks = HashMap<String, Int>(60000)
    private val added = LinkedHashMap<String, Int>()
    private val eot: Int
    private val cache = HashMap<String, List<Int>>()

    init {
        val json = JSONObject(file.readText())
        val model = json.getJSONObject("model")
        val v = model.getJSONObject("vocab")
        for (k in v.keys()) vocab[k] = v.getInt(k)
        val merges = model.getJSONArray("merges")
        for (i in 0 until merges.length()) {
            val m = merges.get(i)
            val pair = if (m is String) m else (m as org.json.JSONArray).let { "${it.getString(0)} ${it.getString(1)}" }
            ranks[pair] = i
        }
        val addedTokens = json.getJSONArray("added_tokens")
        for (i in 0 until addedTokens.length()) {
            val t = addedTokens.getJSONObject(i)
            added[t.getString("content")] = t.getInt("id")
        }
        eot = added.getValue("<|endoftext|>")
    }

    fun encode(text: String): LongArray {
        val ids = ArrayList<Int>()
        for ((piece, special) in splitAdded(text)) {
            if (special != null) {
                ids += special
                continue
            }
            for (m in PATTERN.findAll(piece)) {
                val word = m.value.toByteArray(Charsets.UTF_8).joinToString("") { BYTE_TO_CHAR[it.toInt() and 0xFF].toString() }
                ids += cache.getOrPut(word) { bpe(word) }
            }
        }
        ids += eot
        ids += eot
        return LongArray(ids.size) { ids[it].toLong() }
    }

    /** Splits out added tokens such as "[laugh]" so they map straight to their ids. */
    private fun splitAdded(text: String): List<Pair<String, Int?>> {
        val out = ArrayList<Pair<String, Int?>>()
        var rest = text
        while (rest.isNotEmpty()) {
            val hit = added.keys.mapNotNull { k -> rest.indexOf(k).takeIf { it >= 0 }?.let { it to k } }.minByOrNull { it.first }
            if (hit == null) {
                out += rest to null
                break
            }
            if (hit.first > 0) out += rest.substring(0, hit.first) to null
            out += hit.second to added.getValue(hit.second)
            rest = rest.substring(hit.first + hit.second.length)
        }
        return out
    }

    private fun bpe(word: String): List<Int> {
        var parts = word.map { it.toString() }
        while (parts.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val r = ranks["${parts[i]} ${parts[i + 1]}"] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    best = i
                }
            }
            if (best < 0) break
            val merged = ArrayList<String>(parts.size - 1)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == parts[best] && parts[i + 1] == parts[best + 1]) {
                    merged += parts[i] + parts[i + 1]
                    i += 2
                } else {
                    merged += parts[i]
                    i++
                }
            }
            parts = merged
        }
        return parts.map { vocab[it] ?: eot }
    }

    private companion object {
        val PATTERN = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        /** GPT-2's reversible byte → printable-character table. */
        val BYTE_TO_CHAR: CharArray = run {
            val printable = (33..126) + (161..172) + (174..255)
            val table = CharArray(256)
            var extra = 0
            for (b in 0..255) {
                table[b] = if (b in printable) b.toChar() else (256 + extra++).toChar()
            }
            table
        }
    }
}
