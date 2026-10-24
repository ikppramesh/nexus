package com.ramesh.nexus

import android.content.Context
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveData
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Owns the on-device speech models, shared by the app, the system TTS engine and the keyboard
 * (they all run in one process).
 *
 * Models live in /sdcard/Android/data/com.ramesh.nexus/files/models (pushed by scripts/setup.sh):
 *   chatterbox/  Chatterbox Turbo q4 ONNX — Ramesh's realistic voice, for reading aloud
 *                (conditioning in assets/voice/voice.cond)
 *   pocket/      Pocket TTS int8 — Ramesh's fast voice, for live conversation
 *                (reference clip in assets/voice/pocket_reference.wav)
 *   parakeet/    NeMo Parakeet TDT 0.6B v2 int8 — speech recognition
 *   silero_vad_v5.onnx
 *   irx-mini-Q8_0.gguf — the on-device AI (see LocalLlm)
 */
object Engines {
    private const val TAG = "NexusEngines"
    const val ASR_SAMPLE_RATE = 16000

    private lateinit var app: Context
    val context: Context get() = app

    fun init(context: Context) {
        app = context.applicationContext
    }

    val modelsDir: File get() = File(app.getExternalFilesDir(null), "models")
    private val chatterboxDir get() = File(modelsDir, "chatterbox")
    private val pocketDir get() = File(modelsDir, "pocket")
    private val parakeetDir get() = File(modelsDir, "parakeet")
    private val vadFile get() = File(modelsDir, "silero_vad_v5.onnx")

    /** A voice file placed here (from tools/make_voice.py) replaces the bundled one. */
    private val customVoice: File get() = File(app.getExternalFilesDir(null), "voice/voice.cond")

    fun ttsReady() = File(chatterboxDir, "language_model_q4.onnx_data").exists()
    fun fastTtsReady() = File(pocketDir, "lm_main.int8.onnx").exists()
    fun asrReady() = File(parakeetDir, "encoder.int8.onnx").exists() && vadFile.exists()

    @Volatile private var tts: ChatterboxTts? = null
    @Volatile private var fastTts: OfflineTts? = null
    @Volatile private var fastReference: WaveData? = null
    @Volatile private var recognizer: OfflineRecognizer? = null
    private val fastLock = Any()
    private val lmLock = Any()
    private val decoderLock = Any()
    private val asrLock = Any()

    /**
     * Runs [block] at raised CPU priority. Inference worker threads inherit the priority of the
     * thread that creates them, so models are loaded inside this too; at the default (or the
     * background-loader's lowest) priority the voice ran at half real-time speed.
     */
    private inline fun <T> boosted(block: () -> T): T {
        val tid = Process.myTid()
        val old = Process.getThreadPriority(tid)
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        try {
            return block()
        } finally {
            Process.setThreadPriority(old)
        }
    }

    private fun tts(): ChatterboxTts = tts ?: synchronized(this) {
        tts ?: boosted {
            val t0 = System.currentTimeMillis()
            val voice = if (customVoice.exists()) customVoice.inputStream() else app.assets.open("voice/voice.cond")
            ChatterboxTts(chatterboxDir, voice).also {
                tts = it
                Log.i(TAG, "TTS loaded in ${System.currentTimeMillis() - t0} ms")
            }
        }
    }

    private fun fastTts(): OfflineTts = fastTts ?: synchronized(this) {
        fastTts ?: boosted {
            val d = pocketDir.path
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    pocket = OfflineTtsPocketModelConfig(
                        lmFlow = "$d/lm_flow.int8.onnx",
                        lmMain = "$d/lm_main.int8.onnx",
                        encoder = "$d/encoder.onnx",
                        decoder = "$d/decoder.int8.onnx",
                        textConditioner = "$d/text_conditioner.onnx",
                        vocabJson = "$d/vocab.json",
                        tokenScoresJson = "$d/token_scores.json",
                    ),
                    numThreads = 4,
                ),
            )
            fastReference = WaveReader.readWave(app.assets, "voice/pocket_reference.wav")
            OfflineTts(null, config).also { fastTts = it }
        }
    }

    private fun recognizer(): OfflineRecognizer = recognizer ?: synchronized(this) {
        recognizer ?: boosted {
            val d = parakeetDir.path
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = ASR_SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "$d/encoder.int8.onnx",
                        decoder = "$d/decoder.int8.onnx",
                        joiner = "$d/joiner.int8.onnx",
                    ),
                    tokens = "$d/tokens.txt",
                    modelType = "nemo_transducer",
                    numThreads = 4,
                ),
            )
            val t0 = System.currentTimeMillis()
            OfflineRecognizer(null, config).also {
                recognizer = it
                Log.i(TAG, "ASR loaded in ${System.currentTimeMillis() - t0} ms")
            }
        }
    }

    /** Loads whatever models are present so the first sentence isn't delayed. Call off the main thread. */
    fun preload() {
        if (fastTtsReady()) fastTts()
        if (ttsReady()) tts()
        if (asrReady()) recognizer()
    }

    const val TTS_SAMPLE_RATE = ChatterboxTts.SAMPLE_RATE

    /**
     * First stage of speaking: text → speech tokens. Thread-safe; can run while another thread is in
     * [renderSpeech], which is how the next sentence gets ready while the current one is decoded.
     */
    fun speechTokens(text: String, cancelled: () -> Boolean = { false }): LongArray = synchronized(lmLock) {
        boosted { speechTokensLocked(text, cancelled) }
    }

    private fun speechTokensLocked(text: String, cancelled: () -> Boolean): LongArray {
        val t0 = System.currentTimeMillis()
        return tts().speechTokens(text, cancelled).also {
            Log.d(TAG, "TTS tokens: ${it.size} (%.1fs audio) in %d ms".format(it.size / 25f, System.currentTimeMillis() - t0))
        }
    }

    /** Second stage of speaking: speech tokens → 24 kHz audio. */
    fun renderSpeech(tokens: LongArray): FloatArray = synchronized(decoderLock) {
        boosted {
            val t0 = System.currentTimeMillis()
            tts().render(tokens).also {
                Log.d(TAG, "TTS render: %.1fs audio in %d ms".format(it.size / 24000f, System.currentTimeMillis() - t0))
            }
        }
    }

    /**
     * Speaks [text] in Ramesh's voice, sentence by sentence, passing each sentence's audio to
     * [onChunk] as soon as it's ready. The next sentence is generated while the current one is
     * decoded. Return false from [onChunk] to stop.
     */
    fun synthesize(text: String, onChunk: (FloatArray) -> Boolean) {
        val sentences = Sentences.split(text)
        if (sentences.isEmpty()) return
        val stop = AtomicBoolean(false)
        val queue = LinkedBlockingQueue<Result<LongArray>>(2)
        val producer = thread(name = "nexus-tts-lm") {
            for (s in sentences) {
                if (stop.get()) break
                queue.put(runCatching { speechTokens(s) { stop.get() } })
            }
        }
        try {
            repeat(sentences.size) {
                val audio = renderSpeech(queue.take().getOrThrow())
                if (stop.get() || !onChunk(audio)) return
            }
        } finally {
            stop.set(true)
            queue.clear() // unblocks a producer waiting on a full queue
            producer.join()
        }
    }

    /**
     * Ramesh's fast voice (Pocket TTS) for live conversation: audio streams out while it's being
     * generated, so replies start within about a second. Less like him than [synthesize].
     * Return false from [onChunk] to stop.
     */
    fun synthesizeFast(text: String, onChunk: (FloatArray) -> Boolean) {
        val engine = fastTts()
        val ref = fastReference!!
        val config = GenerationConfig(
            referenceAudio = ref.samples,
            referenceSampleRate = ref.sampleRate,
            numSteps = 5,
            extra = mapOf("temperature" to "0.7", "chunk_size" to "15"),
        )
        synchronized(fastLock) {
            boosted { engine.generateWithConfigAndCallback(text, config) { if (onChunk(it)) 1 else 0 } }
        }
    }

    /** Transcribes 16 kHz mono audio. */
    fun transcribe(samples: FloatArray): String {
        val rec = recognizer()
        synchronized(asrLock) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, ASR_SAMPLE_RATE)
                val t0 = System.currentTimeMillis()
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                Log.d(TAG, "ASR %.1fs audio in %d ms".format(samples.size / ASR_SAMPLE_RATE.toFloat(), System.currentTimeMillis() - t0))
                return text
            } finally {
                stream.release()
            }
        }
    }

    /** Voice-activity detector; each listener needs its own because it is stateful. */
    fun newVad(minSilence: Float, maxSpeech: Float): Vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadFile.path,
                threshold = 0.5f,
                minSilenceDuration = minSilence,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = maxSpeech,
            ),
            sampleRate = ASR_SAMPLE_RATE,
        ),
    )
}
