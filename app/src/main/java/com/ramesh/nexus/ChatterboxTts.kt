package com.ramesh.nexus

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Chatterbox Turbo (Resemble AI, q4 ONNX) speaking in Ramesh's voice. It matched his real voice
 * best of the on-device options we measured (speaker similarity 0.70 vs 0.42 for Pocket TTS).
 *
 * The voice conditioning (voice.cond) is computed once on the Mac by tools/make_voice.py, and its
 * KV cache is computed once per process, so each sentence only runs the text part.
 *
 * Two stages that can run at the same time on different threads:
 *   [speechTokens]  text → speech tokens (the language model)
 *   [render]        speech tokens → 24 kHz audio (the decoder)
 */
class ChatterboxTts(dir: File, voice: InputStream, private val tuning: Tuning = Tuning()) {
    /** Session settings, measured on the Fold7 (see DebugReceiver "benchtts"). */
    data class Tuning(
        val lmThreads: Int = 4,
        val decoderThreads: Int = 4,
        val decoderFile: String = "conditional_decoder_q4.onnx",
        val decoderXnnpack: Boolean = false,
    )

    private val env = OrtEnvironment.getEnvironment()
    private val embed = session(File(dir, "embed_tokens_q4.onnx"), 2)
    private val lm = session(File(dir, "language_model_q4.onnx"), tuning.lmThreads)
    private val decoder = session(File(dir, tuning.decoderFile), tuning.decoderThreads, tuning.decoderXnnpack)
    private val tokenizer = Gpt2Tokenizer(File(dir, "tokenizer.json"))

    private val cond = readCond(voice)
    private val condLen: Int
    /** KV cache for the voice-conditioning prefix, reused by every sentence. */
    private val prefixKv: Map<String, OnnxTensor>

    init {
        val condEmb = cond.getValue("cond_emb") as OnnxTensor
        condLen = condEmb.info.shape[1].toInt()
        val empty = FloatBuffer.allocate(0)
        val inputs = HashMap<String, OnnxTensor>()
        inputs["inputs_embeds"] = condEmb
        inputs["attention_mask"] = longs(LongArray(condLen) { 1 }, 1, condLen.toLong())
        inputs["position_ids"] = longs(LongArray(condLen) { it.toLong() }, 1, condLen.toLong())
        for (i in 0 until LAYERS) for (kv in KV) {
            inputs["past_key_values.$i.$kv"] = OnnxTensor.createTensor(env, empty, longArrayOf(1, HEADS, 0, HEAD_DIM))
        }
        lm.run(inputs).use { res ->
            prefixKv = (0 until LAYERS).flatMap { i -> KV.map { kv -> i to kv } }.associate { (i, kv) ->
                val t = res.get("present.$i.$kv").get() as OnnxTensor
                "past_key_values.$i.$kv" to OnnxTensor.createTensor(env, t.floatBuffer, t.info.shape)
            }
        }
        inputs.forEach { (k, v) -> if (k != "inputs_embeds") v.close() }
    }

    /** Runs the language model: text → speech tokens. Stops early once [cancelled]. */
    fun speechTokens(text: String, cancelled: () -> Boolean = { false }): LongArray {
        val ids = tokenizer.encode(text)
        val prompt = embedIds(longArrayOf(*ids, START))
        val generated = ArrayList<Long>().apply { add(START) }
        val maxTokens = minOf(1000, 60 + text.length * 3)

        var embeds: OnnxTensor = prompt
        var total = condLen + ids.size + 1
        var positions = LongArray(ids.size + 1) { condLen + it.toLong() }
        var past: Map<String, OnnxTensor> = prefixKv
        var previous: OrtSession.Result? = null
        try {
            while (generated.size <= maxTokens && !cancelled()) {
                val inputs = HashMap<String, OnnxTensor>(past)
                inputs["inputs_embeds"] = embeds
                val mask = longs(LongArray(total) { 1 }, 1, total.toLong())
                val pos = longs(positions, 1, positions.size.toLong())
                inputs["attention_mask"] = mask
                inputs["position_ids"] = pos
                val res = lm.run(inputs)
                mask.close(); pos.close(); embeds.close()
                previous?.close() // its present tensors were this step's past
                previous = res

                val next = pickToken(res.get("logits").get() as OnnxTensor, generated)
                generated += next
                if (next == STOP) break

                past = (0 until LAYERS).flatMap { i -> KV.map { kv -> i to kv } }.associate { (i, kv) ->
                    "past_key_values.$i.$kv" to res.get("present.$i.$kv").get() as OnnxTensor
                }
                embeds = embedIds(longArrayOf(next))
                positions = longArrayOf(total.toLong())
                total++
            }
        } finally {
            previous?.close()
        }
        val speech = generated.drop(1).filter { it != STOP }
        return LongArray(speech.size) { speech[it] }
    }

    /** Runs the decoder: speech tokens → 24 kHz mono audio. */
    fun render(speech: LongArray): FloatArray {
        if (speech.isEmpty()) return FloatArray(0)
        val prompt = cond.getValue("prompt_tokens") as OnnxTensor
        val promptIds = LongArray(prompt.info.shape[1].toInt()).also { prompt.longBuffer.get(it) }
        val all = promptIds + speech + LongArray(3) { SILENCE }
        longs(all, 1, all.size.toLong()).use { tokens ->
            val inputs = mapOf(
                "speech_tokens" to tokens,
                "speaker_embeddings" to cond.getValue("speaker_embeddings") as OnnxTensor,
                "speaker_features" to cond.getValue("speaker_features") as OnnxTensor,
            )
            decoder.run(inputs).use { res ->
                val wav = (res.get("waveform").get() as OnnxTensor).floatBuffer
                return FloatArray(wav.remaining()).also { wav.get(it) }
            }
        }
    }

    /** Greedy choice with repetition penalty 1.2, as in Resemble's reference implementation. */
    private fun pickToken(logits: OnnxTensor, generated: List<Long>): Long {
        val shape = logits.info.shape
        val vocab = shape[2].toInt()
        val buf = logits.floatBuffer
        val last = FloatArray(vocab)
        buf.position(((shape[1] - 1) * vocab).toInt())
        buf.get(last)
        for (t in generated.toSet()) {
            val i = t.toInt()
            last[i] = if (last[i] < 0) last[i] * 1.2f else last[i] / 1.2f
        }
        var best = 0
        for (i in 1 until vocab) if (last[i] > last[best]) best = i
        return best.toLong()
    }

    private fun embedIds(ids: LongArray): OnnxTensor =
        longs(ids, 1, ids.size.toLong()).use { input ->
            embed.run(mapOf("input_ids" to input)).use { res ->
                val t = res.get("inputs_embeds").get() as OnnxTensor
                OnnxTensor.createTensor(env, t.floatBuffer, t.info.shape)
            }
        }

    private fun longs(data: LongArray, vararg shape: Long) = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun session(file: File, threads: Int, xnnpack: Boolean = false): OrtSession =
        env.createSession(file.path, OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (xnnpack) {
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                setIntraOpNumThreads(1)
            } else {
                setIntraOpNumThreads(threads)
            }
            // Don't busy-spin between runs; the LM and decoder share the CPU.
            addConfigEntry("session.intra_op.allow_spinning", "0")
        })

    /** Reads the NXC1 file written by tools/make_voice.py. */
    private fun readCond(input: InputStream): Map<String, OnnxTensor> {
        val bytes = input.use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { bb.get(it) }
        check(String(magic) == "NXC1") { "Not a Nexus voice file" }
        val out = HashMap<String, OnnxTensor>()
        repeat(bb.int) {
            val name = String(ByteArray(bb.int).also { bb.get(it) })
            val dtype = bb.int
            val shape = LongArray(bb.int) { bb.long }
            val count = shape.fold(1L) { a, b -> a * b }.toInt()
            out[name] = if (dtype == 0) {
                val data = FloatArray(count).also { bb.asFloatBuffer().get(it) }
                bb.position(bb.position() + count * 4)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
            } else {
                val data = LongArray(count).also { bb.asLongBuffer().get(it) }
                bb.position(bb.position() + count * 8)
                OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
            }
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 24000
        private const val START = 6561L
        private const val STOP = 6562L
        private const val SILENCE = 4299L
        private const val LAYERS = 24
        private const val HEADS = 16L
        private const val HEAD_DIM = 64L
        private val KV = listOf("key", "value")
    }
}
