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
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Downloads a GGUF model from Hugging Face into the app's private storage, in the background with a
 * progress notification. Interrupted downloads resume where they stopped.
 */
class ModelDownloadService : Service() {
    data class Progress(val name: String, val done: Long, val total: Long, val error: String? = null, val finished: Boolean = false)

    @Volatile private var cancelled = false
    @Volatile private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            if (!active) stopSelf()
            return START_NOT_STICKY
        }
        val repo = intent?.getStringExtra(EXTRA_REPO) ?: return START_NOT_STICKY
        val path = intent.getStringExtra(EXTRA_PATH) ?: return START_NOT_STICKY
        val file = ModelStore.HfFile(repo, path, intent.getLongExtra(EXTRA_BYTES, 0))
        if (active) return START_NOT_STICKY // one at a time

        active = true
        cancelled = false
        startForeground(NOTIFICATION, notification(file.name, 0, file.bytes), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        progress.value = Progress(file.name, 0, file.bytes)
        thread(name = "nexus-download") {
            try {
                download(file)
            } catch (e: Exception) {
                progress.value = progress.value?.copy(error = if (cancelled) "Cancelled" else e.message ?: "Download failed")
            } finally {
                active = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun download(file: ModelStore.HfFile) {
        val store = ModelStore(this)
        val target = File(store.dir, file.name)
        val part = File(store.dir, file.name + ".part")
        val nm = getSystemService(NotificationManager::class.java)

        val conn = URL(file.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (part.exists()) conn.setRequestProperty("Range", "bytes=${part.length()}-")
        try {
            val resumed = conn.responseCode == 206
            if (conn.responseCode != 200 && !resumed) {
                throw RuntimeException(
                    if (conn.responseCode in listOf(401, 403)) "This model needs a Hugging Face login (gated)."
                    else "Hugging Face error ${conn.responseCode}",
                )
            }
            var done = if (resumed) part.length() else 0L
            val total = if (resumed) done + conn.contentLengthLong else conn.contentLengthLong.takeIf { it > 0 } ?: file.bytes
            var lastUpdate = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled) throw RuntimeException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            lastUpdate = now
                            progress.value = Progress(file.name, done, total)
                            nm.notify(NOTIFICATION, notification(file.name, done, total))
                        }
                    }
                }
            }
            check(total <= 0 || done >= total) { "Download incomplete; try again to resume." }
            part.renameTo(target)
            store.installed().firstOrNull { it.file == target }?.let(store::select)
            progress.value = Progress(file.name, done, total, finished = true)
        } finally {
            conn.disconnect()
        }
    }

    private fun notification(name: String, done: Long, total: Long): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading $name")
            .setContentText("${ModelStore.size(done)} of ${ModelStore.size(total)}")
            .setProgress(100, pct, total <= 0)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 2
        private const val EXTRA_REPO = "repo"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_BYTES = "bytes"
        private const val ACTION_CANCEL = "cancel"

        /** The current (or last) download, for the Setup screen. */
        val progress = MutableStateFlow<Progress?>(null)

        fun start(context: Context, file: ModelStore.HfFile) {
            context.startForegroundService(
                Intent(context, ModelDownloadService::class.java)
                    .putExtra(EXTRA_REPO, file.repo).putExtra(EXTRA_PATH, file.path).putExtra(EXTRA_BYTES, file.bytes),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
