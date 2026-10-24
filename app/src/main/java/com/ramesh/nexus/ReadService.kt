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
import kotlin.concurrent.thread

/** Reads text aloud in Ramesh's voice in the background, with a Stop button in the notification. */
class ReadService : Service() {
    @Volatile private var job: Job? = null

    private class Job(val text: String) {
        @Volatile var cancelled = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (intent?.action == ACTION_STOP || text.isNullOrBlank()) {
            job?.cancelled = true
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        // The same text again (e.g. a double tap) keeps the current reading going.
        job?.let { if (!it.cancelled && it.text == text) return START_NOT_STICKY }
        job?.cancelled = true
        val j = Job(text)
        job = j
        thread(name = "nexus-read") {
            if (!Engines.ttsReady()) {
                stopSelf()
                return@thread
            }
            val speaker = Speaker(speed = Prefs(this).speed)
            try {
                speaker.speak(j.text) { j.cancelled }
                speaker.drain { j.cancelled }
            } finally {
                speaker.release()
                if (job === j) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancelled = true
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
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
            .setContentTitle("Reading in your voice")
            .setContentText(text.take(120))
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "read"
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "stop"

        fun read(context: Context, text: String) {
            context.startForegroundService(Intent(context, ReadService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReadService::class.java).setAction(ACTION_STOP))
        }
    }
}
