package com.ramesh.nexus

import android.util.Log
import java.io.File

/** Ramesh's IRx-mini model (Gemma 3 1B fine-tune, GGUF) running on the phone through llama.cpp. */
object LocalLlm {
    private const val TAG = "NexusLlm"
    const val MODEL_FILE = "irx-mini-Q8_0.gguf"
    private const val CONTEXT = 4096

    init {
        System.loadLibrary("nexus_llm")
    }

    private val modelFile: File get() = File(Engines.modelsDir, MODEL_FILE)
    private var session = 0L

    fun ready() = modelFile.exists()

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
        if (session == 0L) {
            check(ready()) { "IRx-mini isn't on the phone yet. Run scripts/setup.sh on the Mac." }
            val t0 = System.currentTimeMillis()
            session = nativeLoad(modelFile.path, 4, CONTEXT)
            check(session != 0L) { "Couldn't load IRx-mini." }
            Log.i(TAG, "loaded in ${System.currentTimeMillis() - t0} ms")
        }
        val out = StringBuilder()
        val utf8 = Utf8Assembler()
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
                    val text = utf8.add(bytes)
                    if (text.isNotEmpty()) {
                        out.append(text)
                        onText(text)
                    }
                    return true
                }
            },
        )
        check(n >= 0) { "IRx-mini couldn't answer (the conversation may be too long; start a new chat)." }
        val secs = (System.currentTimeMillis() - t0) / 1000f
        Log.d(TAG, "generated $n tokens in %.1fs (%.1f tok/s)".format(secs, n / secs))
        return out.toString()
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
