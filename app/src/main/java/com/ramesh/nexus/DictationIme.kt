package com.ramesh.nexus

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Wispr Flow-style voice keyboard: tap the mic, talk naturally, tap again. Speech is transcribed on
 * the phone, optionally polished by the AI, and typed into whatever app is open.
 */
class DictationIme : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var mic: TextView

    private var listener: Listener? = null
    /** Transcribed segments of the current recording; only touched on [worker]. */
    private val pieces = mutableListOf<String>()
    /** Bumped on every new recording so late results from a discarded one are dropped. */
    @Volatile private var session = 0

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0D12"))
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#B8BCC8"))
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            text = idleHint()
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(key("⌨") { switchKeyboard() }, keyParams())
        row.addView(key("␣") { currentInputConnection?.commitText(" ", 1) }, keyParams())
        mic = TextView(this).apply {
            text = "🎙"
            textSize = 30f
            gravity = Gravity.CENTER
            background = circle(ACCENT)
            setOnClickListener { toggle() }
        }
        row.addView(mic, LinearLayout.LayoutParams(dp(84), dp(84)).apply { setMargins(dp(14), 0, dp(14), 0) })
        row.addView(key("⌫") { deleteBack() }.apply {
            setOnLongClickListener { currentInputConnection?.deleteSurroundingText(1000, 0); true }
        }, keyParams())
        row.addView(key("⏎") { enter() }, keyParams())
        root.addView(row)
        return root
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelRecording()
        super.onFinishInputView(finishingInput)
    }

    private fun toggle() = if (listener == null) startRecording() else finishRecording()

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Open the Nexus app once to allow the microphone"
            return
        }
        if (!Engines.asrReady()) {
            status.text = "Open Nexus › Setup to install the voice models"
            return
        }
        val id = ++session
        worker.execute { pieces.clear() }
        mic.background = circle(RECORDING)
        status.text = "Listening… tap to finish"
        listener = Listener(minSilence = 0.5f, maxSpeech = 25f, echoCancel = false).also {
            it.start(object : Listener.Callbacks {
                override fun onSegment(samples: FloatArray) {
                    worker.execute {
                        val text = runCatching { Engines.transcribe(samples) }.getOrDefault("")
                        if (text.isBlank() || id != session) return@execute
                        pieces += text
                        val preview = pieces.joinToString(" ").takeLast(120)
                        main.post { if (id == session) status.text = preview }
                    }
                }
            })
        }
    }

    private fun finishRecording() {
        val l = listener ?: return
        listener = null
        val id = session
        mic.background = circle(ACCENT)
        status.text = "Transcribing…"
        val app = currentInputEditorInfo?.packageName
        worker.execute {
            l.stop(flush = true) // queues the last segment's transcription before this task ends
            worker.execute {
                val raw = pieces.joinToString(" ").trim()
                if (id != session) return@execute
                if (raw.isEmpty()) {
                    main.post { status.text = "Didn't catch that. ${idleHint()}" }
                    return@execute
                }
                val prefs = Prefs(this)
                val text = if (prefs.polishDictation && Brain(prefs).available()) {
                    main.post { status.text = "Polishing…" }
                    runCatching { Brain(prefs).polish(raw, app) }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw
                } else raw
                main.post {
                    if (id != session) return@post
                    currentInputConnection?.commitText(text + " ", 1)
                    status.text = idleHint()
                }
            }
        }
    }

    private fun cancelRecording() {
        session++
        listener?.let { l -> worker.execute { l.stop() } }
        listener = null
        if (::mic.isInitialized) {
            mic.background = circle(ACCENT)
            status.text = idleHint()
        }
    }

    private fun idleHint() = "Tap the mic and speak"

    private fun deleteBack() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        } else {
            ic.commitText("", 1)
        }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noEnterAction) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    private fun switchKeyboard() {
        if (!switchToPreviousInputMethod()) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    private fun key(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#1C2030"))
        }
        setOnClickListener { onClick() }
    }

    private fun keyParams() = LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        val ACCENT = Color.parseColor("#7C5CFF")
        val RECORDING = Color.parseColor("#FF4D6D")
    }
}
