package com.ramesh.nexus

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** Answers the system's text-to-speech settings screen (voice data check, sample text). */
class TtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val ready = Engines.ttsReady()
                setResult(
                    if (ready) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
                    Intent()
                        .putStringArrayListExtra(
                            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                            if (ready) arrayListOf("eng-USA") else arrayListOf(),
                        )
                        .putStringArrayListExtra(
                            TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                            if (ready) arrayListOf() else arrayListOf("eng-USA"),
                        ),
                )
            }
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> setResult(
                TextToSpeech.LANG_AVAILABLE,
                Intent().putExtra("sampleText", "Hi, this is Ramesh. This voice is running on my phone with Nexus."),
            )
            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA ->
                startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
