package local.frameshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import local.frameshot.core.formatCount

/**
 * Layanan latar depan selama ekstraksi berjalan: menjaga proses tetap hidup saat aplikasi
 * ditinggal, menampilkan progres di notifikasi, dan memberi kabar saat selesai.
 * Pekerjaannya sendiri berjalan di AppState.
 */
class ExtractionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = progressNotification(this, "Menyiapkan…", "", 0, true)
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "frameshot:extract").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        running = true
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Batas waktu sistem untuk layanan latar depan: hentikan rapi.
        (application as FrameshotApplication).state.cancelExtraction()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "extraction"
        private const val PROGRESS_ID = 1
        private const val DONE_ID = 2
        @Volatile private var running = false
        private var lastUpdate = 0L

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ExtractionService::class.java)) }
        }

        fun update(context: Context, name: String, index: Int, total: Int, fraction: Double, frames: Int) {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 700) return
            lastUpdate = now
            val title = if (total > 1) "Video ${index + 1} dari $total · ${(fraction * 100).toInt()}%" else "Mengekstrak… ${(fraction * 100).toInt()}%"
            val text = "$name · ${formatCount(frames)} frame"
            notifier(context).notify(PROGRESS_ID, progressNotification(context, title, text, (fraction * 1000).toInt(), false))
        }

        fun finish(context: Context, title: String, text: String) {
            context.stopService(Intent(context, ExtractionService::class.java))
            running = false
            ensureChannel(context)
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            runCatching { notifier(context).notify(DONE_ID, n) }
        }

        fun clearDone(context: Context) = runCatching { notifier(context).cancel(DONE_ID) }

        private fun notifier(c: Context) = c.getSystemService(NotificationManager::class.java)

        private fun ensureChannel(c: Context) {
            val nm = notifier(c)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ekstraksi frame", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progres ekstraksi & kabar selesai"
                })
            }
        }

        private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun progressNotification(c: Context, title: String, text: String, progress: Int, indeterminate: Boolean) =
            Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(1000, progress, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(c))
                .build()
    }
}
