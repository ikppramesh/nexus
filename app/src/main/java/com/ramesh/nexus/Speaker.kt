package com.ramesh.nexus

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import kotlin.math.min

/** Plays Ramesh's voice, sentence by sentence, as it is generated. */
class Speaker(usage: Int = AudioAttributes.USAGE_MEDIA, speed: Float = 1f) {
    private val sampleRate = Engines.TTS_SAMPLE_RATE
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        // ~20 s, so generation never waits on playback: the next sentence is ready before the
        // current one ends. interrupt() still silences instantly by flushing.
        .setBufferSizeInBytes(sampleRate * 4 * 20)
        .build()

    @Volatile private var framesWritten = 0L

    init {
        // Time-stretch without changing pitch.
        if (speed != 1f) track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
    }

    /** Generates and plays [text]; blocks until it has been queued. Stops early once [cancelled]. */
    fun speak(text: String, cancelled: () -> Boolean) {
        Engines.synthesize(text) { audio ->
            play(audio, cancelled)
            !cancelled()
        }
    }

    /** Speaks [text] in the fast conversational voice, playing audio as it's generated. */
    fun speakFast(text: String, cancelled: () -> Boolean) {
        Engines.synthesizeFast(text) { audio ->
            play(audio, cancelled)
            !cancelled()
        }
    }

    /** Queues already-generated audio. */
    fun play(audio: FloatArray, cancelled: () -> Boolean) {
        if (cancelled()) return
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        var off = 0
        while (off < audio.size && !cancelled()) {
            val n = track.write(audio, off, min(4096, audio.size - off), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
        framesWritten += off
    }

    /** Blocks until everything queued has been heard. */
    fun drain(cancelled: () -> Boolean) {
        while (!cancelled() && (track.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten) {
            Thread.sleep(20)
        }
    }

    /** Silences playback immediately and discards anything queued. */
    fun interrupt() {
        track.pause()
        track.flush()
        framesWritten = 0
    }

    fun release() {
        interrupt()
        track.release()
    }
}
