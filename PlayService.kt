package app.tini.player

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

/**
 * Müzik çalarken uygulamanın arka planda kapanmaması için ön plan hizmeti.
 * Sesi WebView çalar, bu hizmet yalnızca süreci ve işlemciyi uyanık tutar ve bildirimi gösterir.
 */
class PlayService : Service() {

    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(
            this,
            intent?.getStringExtra("t") ?: "Tını",
            intent?.getStringExtra("a") ?: ""
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, n)
        }
        running = true

        if (lock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tini:play").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        super.onDestroy()
    }

    companion object {
        private const val CH = "tini_play"
        private const val ID = 1

        @Volatile
        var running = false

        fun update(ctx: Context, playing: Boolean, title: String, artist: String) {
            if (!playing) {
                ctx.stopService(Intent(ctx, PlayService::class.java))
                return
            }
            if (running) {
                post(ctx, title, artist)
                return
            }
            val i = Intent(ctx, PlayService::class.java)
                .putExtra("t", title)
                .putExtra("a", artist)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // uygulama arka plandayken başlatılamazsa ses yine de uygulama açıkken çalmaya devam eder
            }
        }

        private fun post(ctx: Context, title: String, artist: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.notify(ID, build(ctx, title, artist))
        }

        private fun build(ctx: Context, title: String, artist: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "Müzik çalma", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.ifEmpty { "Tını" })
                .setContentText(artist)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}
