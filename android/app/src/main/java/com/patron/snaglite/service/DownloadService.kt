package com.patron.snaglite.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.patron.snaglite.MainActivity
import com.patron.snaglite.R
import com.patron.snaglite.SnagLiteApplication

/**
 * Foreground service holding the download notification so the OS won't kill us
 * mid-download when the activity goes to the background. The actual download
 * work runs in the ViewModel's coroutine — this service is a lifecycle holder.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Downloading"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Working…"
        val notif = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    /**
     * Android 15 caps a `dataSync` foreground service at roughly 6 hours of
     * cumulative runtime per 24-hour window for apps targeting API 35+ (this
     * app targets 36), then calls this. Not stopping promptly costs us a
     * [android.app.ForegroundServiceDidNotStopInTimeException] crash; once the
     * quota is spent, further starts throw
     * `ForegroundServiceStartNotAllowedException` until it refills.
     *
     * This is a crash guard only. Downloads still in flight are owned by
     * `DownloadController`'s in-memory queue, which does not survive the
     * service going away — see the WorkManager entry in CLAUDE.md's
     * "Deliberately out of scope".
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(
            TAG,
            "dataSync foreground-service timeout (startId=$startId, fgsType=$fgsType): " +
                "Android 15 6h/24h quota exhausted, stopping service",
        )
        stopNow()
    }

    /** API 34's single-argument form. Only ever fires for `shortService`, but
     * overriding it costs nothing and keeps the two paths identical. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "foreground-service timeout (startId=$startId), stopping service")
        stopNow()
    }

    private fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(title: String, text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SnagLiteApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "DownloadService"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context, title: String, text: String) {
            val i = Intent(ctx, DownloadService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DownloadService::class.java))
        }
    }
}
