package com.ramesh.nexus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Muted = Color(0xFF9AA0B2)
private val Warn = Color(0xFFFFB547)

/** Choose the on-device AI model, or get one from Hugging Face (stored privately for Nexus only). */
@Composable
fun ModelPicker() {
    val context = LocalContext.current
    val store = remember { ModelStore(context) }
    val scope = rememberCoroutineScope()
    val download by ModelDownloadService.progress.collectAsState()

    var version by remember { mutableIntStateOf(0) } // bump to re-read installed models
    LaunchedEffect(download?.finished) { version++ }
    val installed = remember(version) { store.installed() }
    val selected = remember(version) { store.selected() }

    var query by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<String>>(emptyList()) }
    var repo by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<ModelStore.HfFile>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(block: suspend () -> Unit) = scope.launch {
        busy = true
        error = null
        try {
            block()
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach Hugging Face"
        } finally {
            busy = false
        }
    }

    fun openRepo(id: String) = load {
        repo = id
        files = withContext(Dispatchers.IO) { store.files(id) }
        if (files.isEmpty()) error = "No single-file GGUF models in $id."
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Models on this phone", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        if (installed.isEmpty()) {
            Text("None yet. Download one below.", color = Muted, fontSize = 13.sp)
        }
        installed.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable { store.select(m); version++ },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = m.file == selected, onClick = { store.select(m); version++ })
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = Color.White, fontSize = 14.sp)
                    Text(ModelStore.size(m.bytes), color = Muted, fontSize = 12.sp)
                }
                if (m.file.parentFile == store.dir) {
                    IconButton(onClick = { store.delete(m); version++ }) {
                        Icon(Icons.Filled.Delete, "Delete", tint = Muted)
                    }
                }
            }
        }

        download?.let { d ->
            if (!d.finished) {
                Text(
                    d.error?.let { "${d.name}: $it" }
                        ?: "Downloading ${d.name} · ${ModelStore.size(d.done)} of ${ModelStore.size(d.total)}",
                    color = if (d.error != null) Warn else Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (d.error == null) {
                    LinearProgressIndicator(
                        progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    TextButton(onClick = { ModelDownloadService.cancel(context) }) { Text("Cancel download") }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Get a model from Hugging Face", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text(
            "Downloads stay in Nexus's private storage; other apps can't see them.",
            color = Muted, fontSize = 13.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            ModelStore.SUGGESTED.forEach { (id, label) ->
                AssistChip(onClick = { openRepo(id) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Search, or owner/repo") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val q = query.trim()
                    if (q.count { it == '/' } == 1 && ' ' !in q) openRepo(q)
                    else load { repo = null; repos = withContext(Dispatchers.IO) { store.search(q) } }
                },
                enabled = query.isNotBlank() && !busy,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Find") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let { Text(it, color = Warn, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }

        if (repo == null) {
            repos.forEach { id ->
                Text(
                    id, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clickable { openRepo(id) }.padding(vertical = 8.dp),
                )
            }
        } else {
            Text(repo!!, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            files.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = Color.White, fontSize = 14.sp)
                        Text(ModelStore.size(f.bytes), color = Muted, fontSize = 12.sp)
                    }
                    val have = installed.any { it.file.name == f.name }
                    IconButton(
                        onClick = { ModelDownloadService.start(context, f) },
                        enabled = !have && download?.let { it.finished || it.error != null } != false,
                    ) {
                        Icon(Icons.Filled.Download, "Download", tint = if (have) Muted else Color.White)
                    }
                }
            }
            if (repos.isNotEmpty()) TextButton(onClick = { repo = null }) { Text("Back to results") }
        }
    }
}
