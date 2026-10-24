package com.ramesh.nexus

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** "Read with my voice" from any app's text-selection menu or share sheet. */
class ReadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (fold/rotate) before finishing: the text was already sent.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val text = (intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT))?.toString()
        when {
            text.isNullOrBlank() -> Unit
            !Engines.ttsReady() -> Toast.makeText(this, "Open Nexus to finish setup first", Toast.LENGTH_LONG).show()
            else -> ReadService.read(this, text)
        }
        finish()
    }
}
