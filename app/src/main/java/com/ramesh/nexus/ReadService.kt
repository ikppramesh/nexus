package com.ramesh.nexus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread

/** Reads text aloud in Ramesh's voice in the background, with a Stop button in the notification. */
class ReadService : Service() {
    enum class State { Idle, Preparing, Reading }

    @Volatile private var job: Job? = null

    private class Job(val text: String) {
        @Volatile var cancelled = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (intent?.action == ACTION_STOP || text.isNullOrBlank()) {
            job?.cancelled = true
            state.value = State.Idle
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, notification(text, State.Preparing), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        // The same text again (e.g. a double tap) keeps the current reading going.
        job?.let { if (!it.cancelled && it.text == text) return START_NOT_STICKY }
        job?.cancelled = true
        val j = Job(text)
        job = j
        state.value = State.Preparing
        thread(name = "nexus-read") { read(j) }
        return START_NOT_STICKY
    }

    private fun read(j: Job) {
        val prefs = Prefs(this)
        val fast = prefs.readVoice == Prefs.VOICE_FAST && Engines.fastTtsReady() || !Engines.ttsReady()
        val nm = getSystemService(NotificationManager::class.java)
        var started = false
        val speaker = Speaker(speed = prefs.speed)
        // Switches the screen and notification from "Preparing" to "Reading" on the first audio.
        val cancelled = {
            if (!started && !j.cancelled) {
                started = true
                state.value = State.Reading
                nm.notify(NOTIFICATION, notification(j.text, State.Reading))
            }
            j.cancelled
        }
        try {
            if (fast) {
                for (sentence in Sentences.split(j.text)) {
                    if (j.cancelled) break
                    Engines.synthesizeFast(sentence) { audio -> speaker.play(audio, cancelled); !j.cancelled }
                }
            } else {
                Engines.synthesize(j.text) { audio -> speaker.play(audio, cancelled); !j.cancelled }
            }
            speaker.drain { j.cancelled }
        } catch (e: Exception) {
            Log.e("NexusRead", "reading failed", e)
        } finally {
            speaker.release()
            if (job === j) {
                state.value = State.Idle
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        job?.cancelled = true
        super.onDestroy()
    }

    private fun notification(text: String, s: State): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading aloud", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ReadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (s == State.Preparing) "Preparing your voice…" else "Reading in your voice")
            .setContentText(text.take(120))
            .setProgress(0, 0, s == State.Preparing)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "read"
        private const val NOTIFICATION = 1
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "stop"

        /** What the reader is doing, for the Read screen. */
        val state = MutableStateFlow(State.Idle)

        fun read(context: Context, text: String) {
            context.startForegroundService(Intent(context, ReadService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReadService::class.java).setAction(ACTION_STOP))
        }
    }
}
