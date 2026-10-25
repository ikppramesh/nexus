package com.ramesh.nexus

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope

private val Accent = Color(0xFF7C5CFF)
private val Bg = Color(0xFF0B0D12)
private val Surface = Color(0xFF151824)
private val Muted = Color(0xFF9AA0B2)
private val Good = Color(0xFF3DDC97)
private val Warn = Color(0xFFFFB547)

class NexusViewModel(app: Application) : AndroidViewModel(app) {
    val conversation = Conversation(app, viewModelScope)
    val prefs = Prefs(app)

    override fun onCleared() {
        conversation.stop()
    }
}

class MainActivity : ComponentActivity() {
    private val vm: NexusViewModel by viewModels()
    private var tab by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleLaunch(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface)) {
                NexusScreen(vm, tab) { tab = it }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    /** Opened as the assistant (side key / "Ramesh, open Nexus"): start talking right away. */
    private fun handleLaunch(intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_ASSIST || action == Intent.ACTION_VOICE_COMMAND) {
            tab = 0
            if (hasMic()) vm.conversation.start()
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun NexusScreen(vm: NexusViewModel, tab: Int, onTab: (Int) -> Unit) {
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Surface) {
                listOf(
                    Triple("Talk", Icons.Filled.GraphicEq, 0),
                    Triple("Read", Icons.Filled.RecordVoiceOver, 1),
                    Triple("Setup", Icons.Filled.Settings, 2),
                ).forEach { (label, icon, i) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TalkScreen(vm.conversation)
                1 -> ReadScreen(vm.prefs)
                else -> SetupScreen(vm.prefs)
            }
        }
    }
}

@Composable
private fun TalkScreen(conv: Conversation) {
    val state by conv.state.collectAsState()
    val lines by conv.lines.collectAsState()
    val level by conv.level.collectAsState()
    val error by conv.error.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Nexus", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            if (lines.isNotEmpty()) TextButton(onClick = conv::clear) { Text("New chat") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lines.isEmpty()) item {
                Text(
                    "Tap the orb and just talk. Nexus answers in your voice, and you can interrupt it any time.",
                    color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 24.dp),
                )
            }
            items(lines) { line -> Bubble(line) }
        }
        error?.let { Text(it, color = Warn, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Orb(state, level) { conv.toggle() }
        Text(
            when (state) {
                Conversation.State.Idle -> "Tap to talk"
                Conversation.State.Loading -> "Waking up…"
                Conversation.State.Listening -> "Listening"
                Conversation.State.Thinking -> "Thinking"
                Conversation.State.Speaking -> "Speaking · talk to interrupt"
            },
            color = Muted, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun Bubble(line: Conversation.Line) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            line.text.ifEmpty { "…" },
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier
                .widthIn(max = 520.dp)
                .background(if (line.fromUser) Accent.copy(alpha = 0.35f) else Surface, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Orb(state: Conversation.State, level: Float, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.92f, 1.08f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "pulse",
    )
    val active = state != Conversation.State.Idle
    val voice by animateFloatAsState(if (state == Conversation.State.Listening) level else 0f, label = "level")
    val scale = when (state) {
        Conversation.State.Idle -> 1f
        Conversation.State.Listening -> 1f + voice * 0.35f
        else -> pulse
    }
    val colors = when (state) {
        Conversation.State.Speaking -> listOf(Color(0xFFB59CFF), Accent)
        Conversation.State.Thinking, Conversation.State.Loading -> listOf(Color(0xFF6E7BFF), Color(0xFF3A3F9E))
        Conversation.State.Listening -> listOf(Color(0xFF8CF5D2), Color(0xFF2BB5A0))
        Conversation.State.Idle -> listOf(Color(0xFF3A3F55), Color(0xFF22263A))
    }
    Box(
        Modifier.fillMaxWidth().height(180.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(170.dp)) {
            val r = size.minDimension / 2 * 0.62f * scale
            if (active) drawCircle(colors[0].copy(alpha = 0.18f), radius = r * 1.45f)
            drawCircle(Brush.radialGradient(colors, center = center, radius = r), radius = r)
        }
    }
}

@Composable
private fun ReadScreen(prefs: Prefs) {
    val context = LocalContext.current
    var text by rememberSaveable {
        mutableStateOf("Hi, this is Ramesh. Everything you select on this phone can now be read in my own voice.")
    }
    var speed by remember { mutableStateOf(prefs.speed) }
    var voice by remember { mutableStateOf(prefs.readVoice) }
    val reading by ReadService.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Read in my voice", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Anywhere on the phone: select text › ⋮ › Read with my voice, or share text to Nexus.",
            color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
        )
        Text("Voice", color = Muted, modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = voice == Prefs.VOICE_REALISTIC,
                onClick = { voice = Prefs.VOICE_REALISTIC; prefs.readVoice = voice },
                label = { Text("Realistic · slower start") },
            )
            FilterChip(
                selected = voice == Prefs.VOICE_FAST,
                onClick = { voice = Prefs.VOICE_FAST; prefs.readVoice = voice },
                label = { Text("Fast · instant") },
            )
        }
        Text(
            if (voice == Prefs.VOICE_REALISTIC) "Sounds most like you. Takes a few seconds to start, with short pauses on long text."
            else "Starts in about a second. Less like you.",
            color = Muted, fontSize = 13.sp,
        )
        Text("Speed ${"%.2f".format(speed)}×", color = Muted, modifier = Modifier.padding(top = 16.dp))
        Slider(
            value = speed, onValueChange = { speed = it }, valueRange = 0.7f..1.4f,
            onValueChangeFinished = { prefs.speed = speed },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { ReadService.read(context, text) },
                enabled = text.isNotBlank() && reading == ReadService.State.Idle,
            ) { Text("Speak") }
            OutlinedButton(onClick = { ReadService.stop(context) }, enabled = reading != ReadService.State.Idle) { Text("Stop") }
            when (reading) {
                ReadService.State.Preparing -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Preparing your voice…", color = Muted, fontSize = 14.sp)
                }
                ReadService.State.Reading -> Text("Reading…", color = Good, fontSize = 14.sp)
                ReadService.State.Idle -> Unit
            }
        }
    }
}

@Composable
private fun SetupScreen(prefs: Prefs) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    // Re-checked whenever Ramesh comes back from a system settings screen.
    val modelsReady = remember(refresh) { Engines.ttsReady() && Engines.asrReady() }
    val mic = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val defaultTts = remember(refresh) {
        Settings.Secure.getString(context.contentResolver, "tts_default_synth") == context.packageName
    }
    val imeEnabled = remember(refresh) {
        context.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.any { it.packageName == context.packageName }
    }

    var engine by remember { mutableStateOf(prefs.engine) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    var model by remember { mutableStateOf(prefs.model) }
    var polish by remember { mutableStateOf(prefs.polishDictation) }
    var bargeIn by remember { mutableStateOf(prefs.bargeIn) }

    fun open(action: String) = runCatching { context.startActivity(Intent(action)) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Setup", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Step("Voice models on the phone", modelsReady) {
            Text(
                if (modelsReady) "Your voice, speech recognition and voice detection run fully on this phone."
                else "From the Mac, run  scripts/setup.sh  in the Nexus folder (phone on the same Wi-Fi with " +
                    "wireless debugging on). It copies about 2 GB of models to:\n${Engines.modelsDir}",
                color = Muted, fontSize = 14.sp,
            )
        }

        Step("Microphone", mic) {
            if (!mic) Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Allow microphone") }
        }

        val download by ModelDownloadService.progress.collectAsState()
        val brainReady = if (engine == Prefs.ENGINE_LOCAL) remember(refresh, download?.finished) { LocalLlm.ready() }
        else key.isNotBlank()
        Step("AI brain", brainReady) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = engine == Prefs.ENGINE_LOCAL,
                    onClick = { engine = Prefs.ENGINE_LOCAL; prefs.engine = engine },
                    label = { Text("IRx-mini · on phone") },
                )
                FilterChip(
                    selected = engine == Prefs.ENGINE_CLAUDE,
                    onClick = { engine = Prefs.ENGINE_CLAUDE; prefs.engine = engine },
                    label = { Text("Claude · online") },
                )
            }
            if (engine == Prefs.ENGINE_LOCAL) {
                Text(
                    "Runs fully offline. Nothing you say leaves the phone.",
                    color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
                )
                ModelPicker()
            }
        }

        if (engine == Prefs.ENGINE_CLAUDE) Step("Claude API key", key.isNotBlank()) {
            Text(
                "Create one at console.anthropic.com. It is stored only on this phone.",
                color = Muted, fontSize = 14.sp,
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it; prefs.apiKey = it },
                label = { Text("sk-ant-api…") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Prefs.MODELS.forEach { (id, label) ->
                    FilterChip(
                        selected = model == id,
                        onClick = { model = id; prefs.model = id },
                        label = { Text(label.substringBefore(" ·")) },
                    )
                }
            }
        }

        Step("Read everything in my voice", defaultTts) {
            Text(
                "Set “Nexus (Ramesh's voice)” as the preferred engine. Then Samsung Read aloud, Select to Speak, " +
                    "TalkBack and browsers all use your voice.",
                color = Muted, fontSize = 14.sp,
            )
            Button(onClick = { open("com.android.settings.TTS_SETTINGS") }, Modifier.padding(top = 8.dp)) {
                Text("Open text-to-speech settings")
            }
        }

        Step("Dictation keyboard (like Wispr Flow)", imeEnabled) {
            Text(
                "Turn on Nexus Dictation, then switch to it in any text box. Tap the mic, talk, tap again.",
                color = Muted, fontSize = 14.sp,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { open(Settings.ACTION_INPUT_METHOD_SETTINGS) }) { Text("Enable") }
                OutlinedButton(onClick = {
                    context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }) { Text("Switch keyboard") }
            }
            Toggle("Polish with the AI (fillers, punctuation, corrections)", polish) {
                polish = it; prefs.polishDictation = it
            }
        }

        Step("Wake & assistant", null) {
            Text(
                "Say “Ramesh, open Nexus” with your Samsung wake word, or set Nexus as the digital assistant app " +
                    "(Settings › Apps › Default apps) so holding the side key starts a conversation.",
                color = Muted, fontSize = 14.sp,
            )
            Button(onClick = { open(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) }, Modifier.padding(top = 8.dp)) {
                Text("Default apps")
            }
            Toggle("Interrupt Nexus by talking over it", bargeIn) { bargeIn = it; prefs.bargeIn = it }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Step(title: String, done: Boolean?, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done != null) {
                    Icon(
                        if (done) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null,
                        tint = if (done) Good else Warn, modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
