package com.patron.snaglite.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.patron.snaglite.MainActivity
import com.patron.snaglite.R
import com.patron.snaglite.SnagLiteApplication
import com.patron.snaglite.download.DownloadController
import com.patron.snaglite.download.DownloadItem
import com.patron.snaglite.download.DownloadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

/**
 * Owns the foreground service for the whole queue, and stays alive until the queue
 * drains. Replaces `DownloadService`.
 *
 * **One worker for the queue, not one per download.** A worker per item would have
 * each fighting over the same notification id, would need the `MAX_ACTIVE` cap
 * reimplemented on top of a WorkManager that has no per-tag parallelism control, and
 * would gain nothing: the downloads already run as coroutines on the application
 * scope and are already capped. So this worker does not run downloads itself — it
 * holds the foreground service, publishes the aggregate notification, and waits.
 *
 * What that buys over the old service: WorkManager persists its own work across
 * process death *and reboots* and reschedules it, and an expedited worker may start a
 * foreground service from the background — which is what
 * `ForegroundServiceStartNotAllowedException` blocked when `pumpQueue` tried it
 * directly. See [ensureRunning] for what "expedited" is and is not worth.
 *
 * Because it runs in the same process it talks to [DownloadController] directly, so
 * progress still flows through the existing `StateFlow` and no UI code changes.
 */
class DownloadQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val app get() = applicationContext as SnagLiteApplication

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(app.downloads.items.value)

    override suspend fun doWork(): Result {
        val controller = app.downloads

        // A failed foreground start is not a log line. Without it there is no
        // service and no notification, yet the downloads keep running on the
        // application scope — an unprotected process the OS may reclaim
        // mid-transfer, with nothing on screen to say so. Retrying costs a backoff
        // delay and by the next attempt the app may be foreground, or the
        // Android 15 dataSync quota refilled.
        val started = runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "setForeground failed, retrying: ${it.message}") }
            .isSuccess

        // Kick anything restored from disk or enqueued while we were starting.
        // This runs even when the foreground start failed, and deliberately so: the
        // queue draining unprotected is worse than it draining protected, but it is
        // far better than it not draining at all. Gating the pump on the service
        // would mean a device that persistently refuses a background foreground-start
        // never resumes its queue — a regression against the old service, which
        // pumped regardless and merely logged.
        controller.pumpNow()

        // Retry only after the pump, so the work still makes progress in the
        // meantime. WorkManager re-runs this worker with backoff and tries the
        // foreground start again; the downloads it just started are unaffected,
        // since those coroutines live on the application scope and are not ours to
        // stop.
        if (!started) return Result.retry()

        // Suspend until nothing is active or waiting, refreshing the notification as
        // the queue changes. first{} both waits for the drain condition and lets
        // onEach see every intermediate state.
        runCatching {
            controller.items
                .onEach { items -> maybeUpdateNotification(items) }
                .first { items -> items.none { it.isActive || it.status is DownloadStatus.Queued } }
        }.onFailure { Log.w(TAG, "queue wait ended: ${it.message}") }

        return Result.success()
    }

    private var lastNotifMs = 0L

    private suspend fun maybeUpdateNotification(items: List<DownloadItem>) {
        val now = System.currentTimeMillis()
        // Same 500 ms guard the old service used. Progress arrives several times a
        // second and each update redraws the shade row.
        if (now - lastNotifMs < 500L) return
        lastNotifMs = now
        runCatching { setForeground(foregroundInfo(items)) }
    }

    private fun foregroundInfo(items: List<DownloadItem>): ForegroundInfo {
        val active = items.filter { it.isActive }
        val queued = items.count { it.status is DownloadStatus.Queued }

        val percents = active.map { (it.status as? DownloadStatus.Running)?.percent ?: 0f }
        val mean = if (percents.isEmpty()) 0f else percents.average().toFloat()
        val indeterminate = mean <= 0f
        val pct = mean.toInt().coerceIn(0, 100)

        val title = when {
            active.isEmpty() -> app.getString(R.string.notif_title_starting)
            queued > 0 -> app.resources.getQuantityString(
                R.plurals.notif_title_active_queued, active.size, active.size, queued,
            )
            else -> app.resources.getQuantityString(
                R.plurals.notif_title_active, active.size, active.size,
            )
        }
        val shown = active.firstOrNull { it.status is DownloadStatus.Running } ?: active.firstOrNull()
        val text = if (indeterminate || shown == null) {
            app.getString(R.string.notif_text_preparing)
        } else {
            app.getString(R.string.notif_text_progress, shown.title.take(80), pct)
        }

        val notif = buildNotification(title, text, pct, indeterminate)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean,
    ): Notification {
        val tap = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(app, SnagLiteApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()
    }

    companion object {
        private const val TAG = "DownloadQueueWorker"
        private const val NOTIF_ID = 1001
        private const val WORK_NAME = "snaglite-download-queue"

        /**
         * Idempotent. [ExistingWorkPolicy.KEEP] means a worker already draining the
         * queue keeps running rather than being replaced mid-download — every
         * enqueue path calls this, so REPLACE would cancel the foreground service
         * every time a second item was added.
         *
         * Expedited is a *request*, not a guarantee: the app has a finite expedited
         * quota per 24 hours, and once it is spent the scheduler treats the request
         * as ordinary work. That is why the fallback is
         * [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] and not
         * `DROP_WORK_REQUEST` — a queue drain that starts late is an inconvenience,
         * one that never starts is a queue the user has to notice and restart by
         * hand. Expedited work needs [getForegroundInfo] on API < 31, which this
         * worker already overrides.
         */
        fun ensureRunning(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
