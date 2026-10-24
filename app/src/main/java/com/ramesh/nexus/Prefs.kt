package com.ramesh.nexus

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nexus", Context.MODE_PRIVATE)

    /** [ENGINE_LOCAL] (IRx-mini on the phone) or [ENGINE_CLAUDE]. */
    var engine: String
        get() = sp.getString("engine", ENGINE_LOCAL)!!
        set(v) = sp.edit { putString("engine", v) }

    var apiKey: String
        get() = sp.getString("api_key", "")!!
        set(v) = sp.edit { putString("api_key", v.trim()) }

    var model: String
        get() = sp.getString("model", MODELS.first().first)!!
        set(v) = sp.edit { putString("model", v) }

    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit { putFloat("speed", v) }

    /** Polish dictation with Claude (filler words, punctuation, self-corrections). */
    var polishDictation: Boolean
        get() = sp.getBoolean("polish", true)
        set(v) = sp.edit { putBoolean("polish", v) }

    /** Let Ramesh interrupt Nexus by talking over it. */
    var bargeIn: Boolean
        get() = sp.getBoolean("barge_in", true)
        set(v) = sp.edit { putBoolean("barge_in", v) }

    companion object {
        const val ENGINE_LOCAL = "irx"
        const val ENGINE_CLAUDE = "claude"

        val MODELS = listOf(
            "claude-haiku-5-5" to "Haiku 5.5 · fastest",
            "claude-sonnet-5-5" to "Sonnet 5.5 · smarter",
            "claude-opus-5-5" to "Opus 5.5 · smartest",
        )
    }
}
