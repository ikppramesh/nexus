package com.ramesh.nexus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Hands-free voice conversation: listen → transcribe on device → AI (streamed) → speak each
 * sentence in Ramesh's voice as soon as it arrives. Talking over Nexus interrupts it.
 */
class Conversation(private val context: Context, private val scope: CoroutineScope) {
    enum class State { Idle, Loading, Listening, Thinking, Speaking }
    data class Line(val fromUser: Boolean, val text: String)

    val state = MutableStateFlow(State.Idle)
    val lines = MutableStateFlow<List<Line>>(emptyList())
    val level = MutableStateFlow(0f)
    val error = MutableStateFlow<String?>(null)

    private val prefs = Prefs(context)
    private val brain = Brain(prefs)
    private val asr = Executors.newSingleThreadExecutor()
    private val audio = context.getSystemService(AudioManager::class.java)

    private var listener: Listener? = null
    private var speaker: Speaker? = null
    private var bargeIn = true
    @Volatile private var turn: Turn? = null

    private class Turn {
        @Volatile var cancelled = false
        var job: Job? = null
        val spoken = StringBuilder()
    }

    fun toggle() = if (state.value == State.Idle) start() else stop()

    fun start() {
        if (state.value != State.Idle) return
        if (!Engines.ttsReady() || !Engines.asrReady()) {
            error.value = "The voice models aren't on the phone yet. See Setup."
            return
        }
        error.value = null
        state.value = State.Loading
        bargeIn = prefs.bargeIn
        scope.launch {
            withContext(Dispatchers.Default) { Engines.preload() }
            if (state.value != State.Loading) return@launch // stopped while loading
            if (bargeIn) enterCallMode()
            speaker = Speaker(
                if (bargeIn) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT,
                prefs.speed,
            )
            listener = Listener(minSilence = 0.7f, maxSpeech = 20f, echoCancel = bargeIn).also {
                it.start(callbacks)
            }
            state.value = State.Listening
            if (!brain.available()) say("Hi Ramesh. My brain isn't set up yet. Check the Setup tab, and then we can talk.")
        }
    }

    fun stop() {
        interrupt()
        listener?.stop()
        listener = null
        speaker?.release()
        speaker = null
        if (bargeIn) exitCallMode()
        level.value = 0f
        state.value = State.Idle
    }

    fun clear() {
        brain.reset()
        lines.value = emptyList()
    }

    private val callbacks = object : Listener.Callbacks {
        override fun onSpeechStart() {
            val s = state.value
            if (bargeIn && (s == State.Speaking || s == State.Thinking)) {
                interrupt()
                state.value = State.Listening
            }
        }

        override fun onSegment(samples: FloatArray) {
            // Without echo cancellation the mic hears Nexus itself, so ignore it while it talks.
            if (!bargeIn && state.value != State.Listening) return
            asr.execute {
                val text = runCatching { Engines.transcribe(samples) }.getOrDefault("")
                if (text.length < 2 || isEcho(text)) return@execute
                scope.launch { respond(text) }
            }
        }

        override fun onLevel(level: Float) {
            this@Conversation.level.value = level
        }
    }

    /** True when the "utterance" is mostly Nexus's own last reply leaking back into the mic. */
    private fun isEcho(text: String): Boolean {
        val spoken = turn?.spoken?.toString()?.lowercase() ?: return false
        if (spoken.isBlank()) return false
        val words = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (words.isEmpty()) return false
        return words.count { spoken.contains(it) } >= words.size * 0.7
    }

    private fun respond(userText: String) {
        if (state.value == State.Idle) return
        interrupt()
        lines.update { it + Line(true, userText) + Line(false, "") }
        state.value = State.Thinking
        val t = Turn()
        turn = t
        t.job = scope.launch(Dispatchers.IO) {
            val sentences = Channel<String>(Channel.UNLIMITED)
            val voice = launch { speakAll(t, sentences) }
            val streamer = Sentences.Streamer()
            try {
                brain.reply(userText, { t.cancelled }) { delta ->
                    lines.update { it.dropLast(1) + Line(false, it.last().text + delta) }
                    streamer.add(delta).forEach { sentences.trySend(it) }
                }
                streamer.flush().forEach { sentences.trySend(it) }
            } catch (e: Exception) {
                if (!t.cancelled) {
                    val msg = e.message ?: "Something went wrong."
                    lines.update { it.dropLast(1) + Line(false, msg) }
                    sentences.trySend(if (prefs.engine == Prefs.ENGINE_CLAUDE) "Sorry, I couldn't reach Claude." else msg)
                }
            } finally {
                sentences.close()
            }
            voice.join()
            if (!t.cancelled && turn === t) state.value = State.Listening
        }
    }

    /** Speaks sentences as they arrive; the next one is generated while the current one is decoded. */
    private suspend fun speakAll(t: Turn, sentences: Channel<String>) = coroutineScope {
        val sp = speaker ?: return@coroutineScope
        val tokens = Channel<LongArray>(2)
        launch(Dispatchers.Default) {
            for (s in sentences) {
                if (t.cancelled) break
                t.spoken.append(s).append(' ')
                tokens.send(Engines.speechTokens(s) { t.cancelled })
            }
            tokens.close()
        }
        for (tk in tokens) {
            if (t.cancelled) break
            val audio = Engines.renderSpeech(tk)
            if (t.cancelled) break
            state.value = State.Speaking
            sp.play(audio) { t.cancelled }
        }
        tokens.cancel() // stops the generator if we left early
        sp.drain { t.cancelled }
    }

    /** Speaks a fixed line (no AI call). */
    private fun say(text: String) {
        lines.update { it + Line(false, text) }
        val t = Turn()
        turn = t
        t.job = scope.launch(Dispatchers.IO) {
            val ch = Channel<String>(Channel.UNLIMITED)
            Sentences.split(text).forEach { ch.trySend(it) }
            ch.close()
            speakAll(t, ch)
            if (!t.cancelled && turn === t) state.value = State.Listening
        }
    }

    private fun interrupt() {
        turn?.let {
            it.cancelled = true
            it.job?.cancel()
        }
        turn = null
        speaker?.interrupt()
    }

    /**
     * Voice-call audio mode, so the phone's echo canceller removes Nexus's voice from the mic and
     * Ramesh can talk over it. Routed to the loudspeaker unless a headset is connected.
     */
    private fun enterCallMode() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        val devices = audio.availableCommunicationDevices
        val headset = devices.firstOrNull {
            it.type in setOf(
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            )
        }
        (headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })
            ?.let { audio.setCommunicationDevice(it) }
    }

    private fun exitCallMode() {
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }
}
