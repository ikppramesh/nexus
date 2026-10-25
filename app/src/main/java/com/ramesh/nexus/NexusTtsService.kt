package com.ramesh.nexus

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.Locale

/**
 * Android text-to-speech engine backed by Ramesh's cloned voice. Once chosen as the preferred
 * engine (Settings › Text-to-speech), Samsung Read aloud, Select to Speak, TalkBack, browsers and
 * any other app that reads text use it.
 */
class NexusTtsService : TextToSpeechService() {
    @Volatile private var stopRequested = false

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        if (isEnglish(lang)) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_NOT_SUPPORTED

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetVoices(): List<Voice> = listOf(voice())

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == VOICE_NAME) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        if (isEnglish(lang)) VOICE_NAME else null

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        val text = request.charSequenceText?.toString().orEmpty()
        val fast = Prefs(this).readVoice == Prefs.VOICE_FAST && Engines.fastTtsReady() || !Engines.ttsReady()
        if (!Engines.ttsReady() && !Engines.fastTtsReady()) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        callback.start(Engines.TTS_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        if (text.isBlank()) {
            callback.done()
            return
        }
        val maxBytes = callback.maxBufferSize
        val onChunk = { chunk: FloatArray ->
            val pcm = toPcm16(chunk)
            var off = 0
            while (off < pcm.size && !stopRequested) {
                val n = minOf(maxBytes, pcm.size - off)
                if (callback.audioAvailable(pcm, off, n) != TextToSpeech.SUCCESS) stopRequested = true
                off += n
            }
            !stopRequested
        }
        try {
            if (fast) {
                for (sentence in Sentences.split(text)) {
                    if (stopRequested) break
                    Engines.synthesizeFast(sentence, onChunk)
                }
            } else {
                Engines.synthesize(text, onChunk)
            }
            callback.done()
        } catch (e: Exception) {
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun isEnglish(lang: String?) = lang == "eng" || lang == "en"

    private fun voice() = Voice(
        VOICE_NAME, Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, emptySet(),
    )

    private companion object {
        const val VOICE_NAME = "en-us-ramesh"
    }
}
