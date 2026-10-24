package com.ramesh.nexus

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Nexus's AI: Ramesh's own IRx-mini model on the phone (default, offline), or Claude through the
 * Messages API with his API key (stored on the phone only).
 */
class Brain(private val prefs: Prefs) {
    /** (role, text) turns of the current conversation. */
    private val history = mutableListOf<Pair<String, String>>()

    fun reset() = synchronized(history) { history.clear() }

    /**
     * Streams the reply to [userText], calling [onText] with each piece of text.
     * Returns the full reply (partial if [cancelled] became true).
     */
    fun reply(userText: String, cancelled: () -> Boolean, onText: (String) -> Unit): String {
        val local = prefs.engine == Prefs.ENGINE_LOCAL
        val messages = synchronized(history) {
            history += "user" to userText
            // The small on-device model has a 4k context, so it gets a shorter memory.
            val max = if (local) 12 else 30
            while (history.size > max) history.removeAt(0)
            // The conversation must start with a user turn.
            while (history.isNotEmpty() && history[0].first != "user") history.removeAt(0)
            history.toList()
        }
        val reply = StringBuilder()
        try {
            generate(conversationPrompt(), messages, if (local) 300 else 1024, 0.7f, cancelled) {
                reply.append(it); onText(it)
            }
        } finally {
            synchronized(history) {
                if (reply.isBlank()) history.removeAt(history.lastIndex)
                else history += "assistant" to reply.toString()
            }
        }
        return reply.toString()
    }

    /** Wispr Flow-style cleanup of raw dictation. [app] is the package Ramesh is typing into. */
    fun polish(raw: String, app: String?): String {
        val system = """
            You clean up dictated text before it is typed into an app${app?.let { " ($it)" } ?: ""}.
            Remove filler words (um, uh, like, you know), false starts and repetitions. Apply spoken
            self-corrections ("at 5, no wait, 6" becomes "at 6"). Fix punctuation, capitalization and
            obvious transcription errors. Turn spoken formatting ("new line", "bullet point") into the
            formatting itself. Keep the speaker's words, tone and meaning; do not summarize, answer
            questions, or add anything. Match the register of the app (casual for chat, polished for
            email). Output only the cleaned text.
        """.trimIndent()
        val out = StringBuilder()
        generate(system, listOf("user" to raw), 1024, 0.2f, { false }) { out.append(it) }
        val cleaned = out.toString().trim()
        // A small model sometimes answers the text instead of cleaning it; keep the raw words then.
        val ratio = cleaned.length.toFloat() / raw.length
        return if (cleaned.isEmpty() || ratio < 0.4f || ratio > 1.6f) raw else cleaned
    }

    /** True when the selected engine can run (model on the phone, or a Claude key set). */
    fun available(): Boolean =
        if (prefs.engine == Prefs.ENGINE_LOCAL) LocalLlm.ready() else prefs.apiKey.isNotBlank()

    private fun generate(
        system: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        cancelled: () -> Boolean,
        onText: (String) -> Unit,
    ) {
        if (prefs.engine == Prefs.ENGINE_LOCAL) {
            LocalLlm.generate(listOf("system" to system) + messages, maxTokens, temperature, cancelled, onText)
        } else {
            val json = JSONArray()
            messages.forEach { (role, text) -> json.put(JSONObject().put("role", role).put("content", text)) }
            streamClaude(system, json, maxTokens, cancelled, onText)
        }
    }

    private fun conversationPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        return """
            You are Nexus, Ramesh's personal voice assistant on his Samsung Galaxy Z Fold7. Your replies
            are spoken aloud in Ramesh's own cloned voice, so write for the ear: plain conversational
            sentences, no markdown, lists, emoji, URLs or code. Keep answers short (one to three
            sentences) unless he asks for detail; he can always ask for more or interrupt you. Be warm,
            direct and natural, like a sharp friend. It is $now.
        """.trimIndent()
    }

    private fun streamClaude(
        system: String,
        messages: JSONArray,
        maxTokens: Int,
        cancelled: () -> Boolean,
        onText: (String) -> Unit,
    ) {
        val key = prefs.apiKey
        if (key.isBlank()) throw IllegalStateException("No Claude API key. Add one in Nexus Setup.")
        val body = JSONObject()
            .put("model", prefs.model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
            .put("stream", true)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                val msg = runCatching { JSONObject(err).getJSONObject("error").getString("message") }
                    .getOrDefault(err.take(200))
                throw RuntimeException("Claude error ${conn.responseCode}: $msg")
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (cancelled()) return
                    if (!line.startsWith("data:")) continue
                    val event = JSONObject(line.removePrefix("data:").trim())
                    when (event.optString("type")) {
                        "content_block_delta" -> {
                            val delta = event.getJSONObject("delta")
                            if (delta.optString("type") == "text_delta") onText(delta.getString("text"))
                        }
                        "error" -> throw RuntimeException(
                            event.optJSONObject("error")?.optString("message") ?: "Claude stream error",
                        )
                        "message_stop" -> return
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_HISTORY = 30
    }
}
