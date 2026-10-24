package com.ramesh.nexus

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * AI models (GGUF) for the on-device brain. Downloads from Hugging Face go into the app's internal
 * storage, which only Nexus can read; they're removed if the app is uninstalled.
 */
class ModelStore(context: Context) {
    data class Model(val file: File, val name: String, val bytes: Long)
    data class HfFile(val repo: String, val path: String, val bytes: Long) {
        val name: String get() = path.substringAfterLast('/')
        val url: String get() = "https://huggingface.co/$repo/resolve/main/$path"
    }

    private val prefs = Prefs(context)
    val dir: File = File(context.filesDir, "llm").apply { mkdirs() }

    /** Models on the phone: Hugging Face downloads plus the one copied from the Mac by setup.sh. */
    fun installed(): List<Model> {
        val downloaded = dir.listFiles { f -> f.name.endsWith(".gguf") }.orEmpty().toList()
        val fromMac = File(Engines.modelsDir, LocalLlm.MODEL_FILE).takeIf { it.exists() }
        return (downloaded + listOfNotNull(fromMac)).map { Model(it, it.name.removeSuffix(".gguf"), it.length()) }
    }

    /** The model the brain uses: the chosen one if it's still there, else the first installed. */
    fun selected(): File? {
        val chosen = prefs.localModel.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
        return chosen ?: installed().firstOrNull()?.file
    }

    fun select(model: Model) {
        prefs.localModel = model.file.path
    }

    fun delete(model: Model) {
        if (model.file.parentFile == dir) model.file.delete()
        if (prefs.localModel == model.file.path) prefs.localModel = ""
    }

    /** Hugging Face repos with GGUF files matching [query], most downloaded first. */
    fun search(query: String): List<String> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = JSONArray(get("https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&limit=25"))
        return (0 until json.length()).map { json.getJSONObject(it).getString("id") }
    }

    /** The GGUF files in [repo] (including sub-folders), smallest first. */
    fun files(repo: String): List<HfFile> {
        val json = JSONArray(get("https://huggingface.co/api/models/$repo/tree/main?recursive=true"))
        return (0 until json.length()).map { json.getJSONObject(it) }
            .filter { it.optString("type") == "file" && it.getString("path").endsWith(".gguf", ignoreCase = true) }
            .map { o -> HfFile(repo, o.getString("path"), o.optJSONObject("lfs")?.optLong("size") ?: o.optLong("size")) }
            // Multi-part files (…-00001-of-00003.gguf) need merging first, so they aren't offered.
            .filterNot { Regex("-\\d{5}-of-\\d{5}\\.gguf$").containsMatchIn(it.path) }
            .sortedBy { it.bytes }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            when (conn.responseCode) {
                200 -> return conn.inputStream.bufferedReader().readText()
                401, 403 -> throw RuntimeException("This model needs a Hugging Face login (gated). Pick another.")
                404 -> throw RuntimeException("Not found on Hugging Face.")
                else -> throw RuntimeException("Hugging Face error ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Ramesh's own IRx family, offered as one-tap picks. */
        val SUGGESTED = listOf(
            "ikppramesh/irx-mini-GGUF" to "IRx-mini · 1.1 GB",
            "ikppramesh/irx-1-GGUF" to "IRx-1 · 1.2 GB",
            "ikppramesh/irx-2-GGUF" to "IRx-2 · 2.7 GB",
            "ikppramesh/irx-2-pro-GGUF" to "IRx-2 Pro · 5.3 GB",
        )

        fun size(bytes: Long): String =
            if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble())
            else "%d MB".format(bytes shr 20)
    }
}
