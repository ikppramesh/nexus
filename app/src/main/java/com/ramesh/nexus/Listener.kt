package com.ramesh.nexus

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Listens to the microphone and cuts it into utterances with Silero VAD.
 * Transcription is left to the caller so the audio loop never stalls.
 */
class Listener(
    private val minSilence: Float,
    private val maxSpeech: Float,
    /** Use the voice-call input path, which cancels Nexus's own voice from the mic. */
    private val echoCancel: Boolean,
) {
    interface Callbacks {
        /** Ramesh started talking (used to interrupt Nexus). */
        fun onSpeechStart() {}
        /** One finished utterance, 16 kHz mono. */
        fun onSegment(samples: FloatArray)
        /** Input level 0..1 for the UI. */
        fun onLevel(level: Float) {}
    }

    @Volatile private var running = false
    @Volatile private var flushOnStop = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
    fun start(callbacks: Callbacks) {
        check(!running)
        running = true
        worker = thread(name = "nexus-listener") {
            val window = 512
            val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val minBuf = AudioRecord.getMinBufferSize(
                Engines.ASR_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = AudioRecord(
                source, Engines.ASR_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, window * 8),
            )
            val effects = buildList {
                if (echoCancel && AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }?.let(::add)
                }
                if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }?.let(::add)
                }
            }
            val vad = Engines.newVad(minSilence, maxSpeech)
            val pcm = ShortArray(window)
            var wasSpeaking = false

            fun drainSegments() {
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    callbacks.onSegment(seg.samples)
                }
            }

            try {
                record.startRecording()
                while (running) {
                    val n = record.read(pcm, 0, window)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { pcm[it] / 32768f }
                    var energy = 0f
                    for (s in samples) energy += s * s
                    callbacks.onLevel((sqrt(energy / n) * 8f).coerceAtMost(1f))

                    vad.acceptWaveform(samples)
                    val speaking = vad.isSpeechDetected()
                    if (speaking && !wasSpeaking) callbacks.onSpeechStart()
                    wasSpeaking = speaking
                    drainSegments()
                }
                if (flushOnStop) {
                    vad.flush()
                    drainSegments()
                }
            } finally {
                record.stop()
                record.release()
                effects.forEach { it.release() }
                vad.release()
            }
        }
    }

    /**
     * Stops listening. With [flush], speech still in progress is delivered as a final segment
     * before this returns.
     */
    fun stop(flush: Boolean = false) {
        flushOnStop = flush
        running = false
        worker?.join()
        worker = null
    }
}
