package com.ramesh.nexus

import android.util.Log
import java.io.File

/**
 * The on-device AI: a GGUF model running through llama.cpp. Ramesh's IRx-mini by default, or any
 * model he downloads from Hugging Face in Setup (see ModelStore).
 */
object LocalLlm {
    private const val TAG = "NexusLlm"
    const val MODEL_FILE = "irx-mini-Q8_0.gguf"
    private const val CONTEXT = 4096

    init {
        System.loadLibrary("nexus_llm")
    }

    private var session = 0L
    private var loadedPath: String? = null

    private fun modelFile(): File? = ModelStore(Engines.context).selected()

    fun ready() = modelFile() != null

    /** Native code calls this with raw UTF-8 bytes of each token. */
    private interface PieceSink {
        fun onPiece(bytes: ByteArray): Boolean
    }

    /**
     * Streams a reply to [messages] (role to text; roles are "system", "user", "assistant").
     * Returns the full reply text.
     */
    @Synchronized
    fun generate(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        cancelled: () -> Boolean,
        onText: (String) -> Unit,
    ): String {
        val file = checkNotNull(modelFile()) { "No AI model on the phone yet. Download one in Setup." }
        if (session != 0L && loadedPath != file.path) {
            nativeFree(session) // Ramesh picked a different model
            session = 0L
        }
        if (session == 0L) {
            val t0 = System.currentTimeMillis()
            session = nativeLoad(file.path, 4, CONTEXT)
            check(session != 0L) { "Couldn't load ${file.name}." }
            loadedPath = file.path
            Log.i(TAG, "loaded ${file.name} in ${System.currentTimeMillis() - t0} ms")
        }
        val out = StringBuilder()
        val utf8 = Utf8Assembler()
        val visible = ThinkFilter()
        val t0 = System.currentTimeMillis()
        val n = nativeGenerate(
            session,
            messages.map { it.first }.toTypedArray(),
            messages.map { it.second }.toTypedArray(),
            maxTokens,
            temperature,
            object : PieceSink {
                override fun onPiece(bytes: ByteArray): Boolean {
                    if (cancelled()) return false
                    val text = visible.add(utf8.add(bytes))
                    if (text.isNotEmpty()) {
                        out.append(text)
                        onText(text)
                    }
                    return true
                }
            },
        )
        check(n >= 0) { "The AI couldn't answer (the conversation may be too long; start a new chat)." }
        val secs = (System.currentTimeMillis() - t0) / 1000f
        Log.d(TAG, "generated $n tokens in %.1fs (%.1f tok/s)".format(secs, n / secs))
        return out.toString()
    }

    /**
     * Drops "<think>…</think>" reasoning that some models (e.g. Qwen-based ones) write even with
     * thinking switched off, so it's never shown or spoken.
     */
    private class ThinkFilter {
        private val pending = StringBuilder()
        private var thinking = false

        fun add(text: String): String {
            pending.append(text)
            val out = StringBuilder()
            while (true) {
                val tag = if (thinking) "</think>" else "<think>"
                val i = pending.indexOf(tag)
                if (i >= 0) {
                    if (!thinking) out.append(pending, 0, i)
                    pending.delete(0, i + tag.length)
                    thinking = !thinking
                    continue
                }
                // Hold back a tail that could be the start of a tag split across tokens.
                val keep = (1 until tag.length).lastOrNull { pending.endsWith(tag.substring(0, it)) } ?: 0
                if (!thinking) out.append(pending, 0, pending.length - keep)
                pending.delete(0, pending.length - keep)
                return out.toString()
            }
        }
    }

    /** Joins token bytes into text without splitting multi-byte characters such as emoji. */
    private class Utf8Assembler {
        private var pending = ByteArray(0)

        fun add(bytes: ByteArray): String {
            val all = pending + bytes
            var end = all.size
            // Walk back over a trailing incomplete sequence, if any.
            var i = all.size - 1
            var cont = 0
            while (i >= 0 && cont < 3 && (all[i].toInt() and 0xC0) == 0x80) { i--; cont++ }
            if (i >= 0) {
                val b = all[i].toInt() and 0xFF
                val need = when {
                    b >= 0xF0 -> 3
                    b >= 0xE0 -> 2
                    b >= 0xC0 -> 1
                    else -> 0
                }
                if (need > cont) end = i
            }
            pending = all.copyOfRange(end, all.size)
            return String(all, 0, end, Charsets.UTF_8)
        }
    }

    private external fun nativeLoad(path: String, threads: Int, nCtx: Int): Long
    private external fun nativeFree(ptr: Long)
    private external fun nativeGenerate(
        ptr: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        sink: Any,
    ): Int
}
