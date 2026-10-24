package com.ramesh.nexus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Debug builds only. Ask Nexus something without the mic, and hear the answer:
 *   adb shell am broadcast -n com.ramesh.nexus/.DebugReceiver --es ask "What's a good name for a cat?"
 *   adb shell am broadcast -n com.ramesh.nexus/.DebugReceiver --es polish "um so like send it at 5 no wait 6"
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val brain = Brain(Prefs(context))
        thread {
            try {
                intent.getStringExtra("ask")?.let { q ->
                    val t0 = System.currentTimeMillis()
                    var first = -1L
                    val a = brain.reply(q, { false }) { if (first < 0) first = System.currentTimeMillis() - t0 }
                    Log.i(TAG, "Q: $q\nA (first text ${first} ms, total ${System.currentTimeMillis() - t0} ms): $a")
                    ReadService.read(context, a)
                }
                if (intent.hasExtra("benchtts")) benchTts(context, intent)
                intent.getStringExtra("polish")?.let { raw ->
                    Log.i(TAG, "RAW: $raw\nPOLISHED: ${brain.polish(raw, "com.whatsapp")}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Times each Chatterbox stage alone with given settings:
     *   --es benchtts 1 --ei lm 4 --ei dec 4 --es decfile conditional_decoder_q4.onnx --ez xnn false
     */
    private fun benchTts(context: Context, intent: Intent) {
        val tuning = ChatterboxTts.Tuning(
            lmThreads = intent.getIntExtra("lm", 4),
            decoderThreads = intent.getIntExtra("dec", 4),
            decoderFile = intent.getStringExtra("decfile") ?: "conditional_decoder_q4.onnx",
            decoderXnnpack = intent.getBooleanExtra("xnn", false),
        )
        val tts = ChatterboxTts(java.io.File(Engines.modelsDir, "chatterbox"), context.assets.open("voice/voice.cond"), tuning)
        val text = "A quick tip is to do five minutes of stretching. It will feel good in your body and mind."
        repeat(2) {
            var t0 = System.currentTimeMillis()
            val tokens = tts.speechTokens(text)
            val lmMs = System.currentTimeMillis() - t0
            t0 = System.currentTimeMillis()
            val audio = tts.render(tokens)
            val decMs = System.currentTimeMillis() - t0
            val secs = audio.size / 24000f
            Log.i(TAG, "$tuning: %.1fs audio | LM %d ms (RTF %.2f, %.0f tok/s) | decoder %d ms (RTF %.2f)".format(
                secs, lmMs, lmMs / 1000f / secs, tokens.size * 1000f / lmMs, decMs, decMs / 1000f / secs))
        }
    }

    private companion object {
        const val TAG = "NexusDebug"
    }
}
