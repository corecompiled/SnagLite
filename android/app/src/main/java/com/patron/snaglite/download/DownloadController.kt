package com.patron.snaglite.download

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.patron.snaglite.SnagLiteApplication
import com.patron.snaglite.service.DownloadQueueWorker
import com.patron.snaglite.yt.YouTubePlaylist
import com.patron.snaglite.yt.YouTubeUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton owner of the active download list. Lives on `SnagLiteApplication.applicationScope`
 * so coroutines survive Activity / ViewModel destruction.
 *
 * Up to [MAX_ACTIVE] downloads run concurrently; extra submissions wait as `Queued`
 * and are promoted as slots free. The foreground service + wake lock are held for
 * as long as any item is active.
 */
class DownloadController(
    private val app: SnagLiteApplication,
    private val scope: CoroutineScope,
) {
    // Read synchronously rather than off-thread so the list is already correct the
    // first time the UI collects it — an async restore would flash an empty screen
    // and then fill, which reads as "my downloads were lost" for exactly as long as
    // it takes to notice. The payload is a few KB of JSON.
    private val _items = MutableStateFlow(QueueStore.load(app))
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    /**
     * Restored items are not started here. `pumpQueue` starts a foreground service,
     * and this constructor can run while the app is in the background — which
     * Android 12+ answers with `ForegroundServiceStartNotAllowedException`.
     * [resumeRestored] is called from the UI instead, where a foreground start is
     * legal.
     */
    @Volatile private var hasResumedRestored = _items.value.none { it.status is DownloadStatus.Queued }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()

    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var pendingSignInId: String? = null

    fun enqueue(url: String, audioOnly: Boolean): String? {
        val trimmed = url.trim()
        val id = UUID.randomUUID().toString()
        if (trimmed.isEmpty() || !trimmed.startsWith("http", ignoreCase = true)) {
            val item = DownloadItem(
                id = id,
                url = trimmed,
                audioOnly = audioOnly,
                title = trimmed.ifEmpty { "(empty URL)" },
                status = DownloadStatus.Error("URL must start with http(s)://"),
            )
            updateItems { it + item }
            return id
        }
        val item = DownloadItem(
            id = id,
            url = trimmed,
            audioOnly = audioOnly,
            title = trimmed,
            status = DownloadStatus.Queued,
        )
        updateItems { it + item }
        scope.launch { pumpQueue() }
        scope.launch {
            // Fire-and-forget metadata probe so the row gets a thumbnail + richer
            // secondary line as soon as yt-dlp can dump-json the URL. Safe if the
            // download completes first — updateItem just patches the snapshot.
            val md = MetadataFetcher.fetch(app, trimmed) ?: return@launch
            updateItem(id) {
                it.copy(
                    title = md.title ?: it.title,
                    thumbnailUrl = md.thumbnail ?: it.thumbnailUrl,
                    durationSec = md.durationSec ?: it.durationSec,
                    uploader = md.uploader ?: it.uploader,
                    filesizeBytes = md.filesizeBytes ?: it.filesizeBytes,
                )
            }
        }
        return id
    }

    fun pause(id: String) {
        val item = currentItem(id) ?: return
        if (item.status !is DownloadStatus.Running && item.status !is DownloadStatus.Preparing) return
        val (pct, line) = when (val s = item.status) {
            is DownloadStatus.Running -> s.percent to s.line
            else -> 0f to "Pausing…"
        }
        updateItem(id) { it.copy(status = DownloadStatus.Paused(pct, line)) }
        // Kill native yt-dlp worker first (cooperative cancel won't reach it), then cancel coroutine.
        Downloader.killYtDlp(id)
        jobs.remove(id)?.cancel()
        scope.launch { pumpQueue() }
    }

    fun resume(id: String) {
        val item = currentItem(id) ?: return
        if (item.status !is DownloadStatus.Paused && item.status !is DownloadStatus.Error) return
        updateItem(id) { it.copy(status = DownloadStatus.Queued) }
        scope.launch { pumpQueue() }
    }

    fun remove(id: String, deleteFile: Boolean) {
        val item = currentItem(id) ?: return
        Downloader.killYtDlp(id)
        jobs.remove(id)?.cancel()
        if (deleteFile) {
            (item.status as? DownloadStatus.Done)?.publicUri?.let { uri ->
                runCatching { MediaSink.delete(app, uri) }
            }
        }
        // Best-effort wipe of the per-item cache subdir.
        runCatching { workDirFor(id).deleteRecursively() }
        updateItems { list -> list.filterNot { it.id == id } }
        scope.launch { pumpQueue() }
    }

    fun clearFinished() {
        val toRemove = _items.value.filter { it.isTerminal }
        toRemove.forEach { runCatching { workDirFor(it.id).deleteRecursively() } }
        updateItems { list -> list.filterNot { it.isTerminal } }
    }

    fun rememberPendingSignIn(id: String) {
        pendingSignInId = id
    }

    fun retryAfterSignIn() {
        val id = pendingSignInId ?: return
        pendingSignInId = null
        resume(id)
    }

    /**
     * Run a foreground yt-dlp update, then re-queue the given item. Used by the
     * "Update engine" CTA on a 403 error card.
     */
    fun updateEngineAndRetry(id: String) {
        val item = currentItem(id) ?: return
        updateItem(id) {
            it.copy(status = DownloadStatus.Error("Updating download engine…"))
        }
        scope.launch {
            val ok = YouTubeUpdater.updateNow(app)
            Log.i(TAG, "manual engine update on retry: ok=$ok (item=$id)")
            // resume() expects Paused or Error; we just set Error above, so this re-queues.
            resume(item.id)
        }
    }

    private suspend fun pumpQueue() = mutex.withLock {
        val snapshot = _items.value
        val activeCount = snapshot.count { it.isActive }
        val freeSlots = (MAX_ACTIVE - activeCount).coerceAtLeast(0)
        if (freeSlots == 0) {
            refreshServiceLifecycle()
            return@withLock
        }
        val toStart = snapshot
            .filter { it.status is DownloadStatus.Queued }
            .sortedBy { it.createdAt }
            .take(freeSlots)
        toStart.forEach { startItem(it.id) }
        refreshServiceLifecycle()
    }

    private fun startItem(id: String) {
        if (jobs[id]?.isActive == true) return
        updateItem(id) { it.copy(status = DownloadStatus.Preparing) }
        val job = scope.launch {
            try {
                if (!app.enginesReady) {
                    app.ensureEngines()
                    app.initError?.let {
                        updateItem(id) { item ->
                            item.copy(status = DownloadStatus.Error("Engine init failed: $it"))
                        }
                        return@launch
                    }
                }
                val item = currentItem(id) ?: return@launch
                if (YouTubePlaylist.isPlaylist(item.url)) {
                    runPlaylist(id, item)
                } else {
                    runSingle(id, item)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "download $id crashed", t)
                updateItem(id) { it.copy(status = DownloadStatus.Error(t.message ?: t.javaClass.simpleName)) }
            } finally {
                jobs.remove(id)
                refreshServiceLifecycle()
                scope.launch { pumpQueue() }
            }
        }
        jobs[id] = job
        refreshServiceLifecycle()
    }

    private suspend fun runSingle(id: String, item: DownloadItem) {
        updateItem(id) { it.copy(status = DownloadStatus.Running(0f, "Starting…")) }
        val result = Downloader.run(
            context = app,
            url = item.url,
            audioOnly = item.audioOnly,
            workDir = workDirFor(id).apply { mkdirs() },
            processId = id,
            onProgress = { pct, line ->
                updateItem(id) {
                    val cur = it.status
                    // A negative percent means "status line only, percentage unknown" —
                    // used by the 403 retry, which resumes from bytes already on disk.
                    // Overwriting the real percentage there would report a restart that
                    // is not happening.
                    if (cur is DownloadStatus.Running) {
                        it.copy(status = DownloadStatus.Running(if (pct < 0f) cur.percent else pct, line))
                    } else {
                        it
                    }
                }
                refreshLocksThrottled()
            },
        )
        applyResult(id, result)
    }

    private suspend fun runPlaylist(id: String, item: DownloadItem) {
        updateItem(id) {
            it.copy(status = DownloadStatus.Running(0f, "Reading playlist…"))
        }
        val entries = YouTubePlaylist.enumerate(app, item.url)
        if (entries.isEmpty()) {
            updateItem(id) {
                it.copy(status = DownloadStatus.Error("Could not read this playlist. It may be private, region-locked, or empty."))
            }
            return
        }
        var saved = 0
        var lastPublished: MediaSink.Published? = null
        for ((idx, entry) in entries.withIndex()) {
            val n = idx + 1
            updateItem(id) {
                it.copy(
                    title = entry.title,
                    status = DownloadStatus.Running(0f, "Starting video $n of ${entries.size}"),
                    playlistIndex = n,
                    playlistTotal = entries.size,
                )
            }
            val subDir = File(workDirFor(id), "p$n").apply { mkdirs() }
            val result = Downloader.run(
                context = app,
                url = entry.watchUrl,
                audioOnly = item.audioOnly,
                workDir = subDir,
                processId = "$id-$n",
                onProgress = { pct, line ->
                    updateItem(id) {
                        if (it.status is DownloadStatus.Running) it.copy(status = DownloadStatus.Running(pct, line))
                        else it
                    }
                    refreshLocksThrottled()
                },
            )
            if (result is DownloadResult.Success) {
                val published = MediaSink.publishToMovies(app, result.file, result.audioOnly)
                if (published != null) {
                    saved++
                    lastPublished = published
                }
            } else if (result is DownloadResult.Failure) {
                Log.w(TAG, "playlist $id item $n failed: ${result.message.take(120)}")
            }
        }
        if (saved > 0) cleanWorkDir(id)
        updateItem(id) {
            if (saved == 0) {
                it.copy(status = DownloadStatus.Error("No videos from this playlist could be downloaded."))
            } else {
                it.copy(
                    title = "$saved of ${entries.size} videos saved",
                    status = DownloadStatus.Done(
                        publicUri = lastPublished?.uri ?: android.net.Uri.EMPTY,
                        displayName = "$saved of ${entries.size} videos saved",
                    ),
                )
            }
        }
    }

    private fun applyResult(id: String, result: DownloadResult) {
        when (result) {
            is DownloadResult.Success -> {
                val published = MediaSink.publishToMovies(app, result.file, result.audioOnly)
                if (published != null) {
                    cleanWorkDir(id)
                    updateItem(id) {
                        it.copy(
                            title = published.displayName,
                            status = DownloadStatus.Done(published.uri, published.displayName),
                        )
                    }
                } else {
                    updateItem(id) {
                        it.copy(
                            status = DownloadStatus.Error(
                                "Downloaded, but couldn't be saved to your gallery",
                            ),
                        )
                    }
                }
            }
            is DownloadResult.Failure -> {
                if (result.needsYouTubeSignIn) pendingSignInId = id
                val needsEngineUpdate = result.needs403Recovery && !result.needsYouTubeSignIn
                // A Failure that carries its own detail was written for a person
                // already; only raw tool output goes through the classifier.
                val classified = result.detail
                    ?.let { DownloadErrors.Classified(result.message, it) }
                    ?: DownloadErrors.classify(result.message)
                val displayMessage = when {
                    // These two already have a button attached, so the line only has
                    // to say what happened — the affordance says what to do.
                    needsEngineUpdate -> "YouTube blocked this download"
                    result.needsYouTubeSignIn -> "This video requires signing in"
                    else -> classified.message
                }
                updateItem(id) {
                    it.copy(
                        status = DownloadStatus.Error(
                            message = displayMessage,
                            needsYouTubeSignIn = result.needsYouTubeSignIn,
                            needsEngineUpdate = needsEngineUpdate,
                            detail = classified.detail,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Deliberately [android.content.Context.getFilesDir] and not `cacheDir`. Downloads
     * resume through yt-dlp's `--continue` against partial files kept here, and Android
     * is free to reclaim `cacheDir` whenever storage runs low — which it is most likely
     * to do while the app is backgrounded, against the largest files, i.e. exactly the
     * near-complete download the user just switched away from.
     *
     * The trade is that nothing reclaims this directory for us, so every terminal path
     * has to sweep it: [remove], [clearFinished], and [cleanWorkDir] after a successful
     * publish.
     */
    private fun workDirFor(id: String): File = File(File(app.filesDir, "snaglite-dl"), id)

    /**
     * Drop an item's working directory once its bytes have been published to MediaStore.
     * Under `cacheDir` the OS eventually did this for us; under `filesDir` it does not,
     * and a kept-but-finished item would otherwise pin a full copy of every download.
     */
    private fun cleanWorkDir(id: String) {
        runCatching { workDirFor(id).deleteRecursively() }
    }

    private fun currentItem(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun updateItems(transform: (List<DownloadItem>) -> List<DownloadItem>) {
        _items.value = transform(_items.value)
        persist()
    }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    @Volatile private var lastPersistMs = 0L

    /**
     * Mirror the queue to disk. Throttled like [refreshLocksThrottled], because
     * progress ticks arrive several times a second and the only thing that changes
     * between most of them is a percentage this store deliberately does not keep.
     *
     * The throttle is skipped whenever the set of items or any of their *states*
     * changed rather than just their progress — those are the transitions worth
     * losing nothing over.
     */
    private fun persist() {
        val snapshot = _items.value
        val signature = snapshot.joinToString(",") { "${it.id}:${it.status.javaClass.simpleName}" }
        val structural = signature != lastPersistSignature
        val now = System.currentTimeMillis()
        if (!structural && now - lastPersistMs < 2_000L) return
        lastPersistMs = now
        lastPersistSignature = signature
        scope.launch(Dispatchers.IO) { QueueStore.save(app, snapshot) }
    }

    @Volatile private var lastPersistSignature: String = ""

    /**
     * Start anything restored from disk. Safe to call repeatedly; only the first
     * call does anything. Must be invoked from the foreground — see the note on
     * [hasResumedRestored].
     */
    fun resumeRestored() {
        if (hasResumedRestored) return
        hasResumedRestored = true
        DownloadQueueWorker.ensureRunning(app)
    }

    /**
     * Entry point for [DownloadQueueWorker]. The worker holds the foreground service
     * but does not run downloads itself, so it calls this once on start to promote
     * whatever is waiting; [startItem]'s `finally` keeps the queue moving after that.
     */
    internal suspend fun pumpNow() = pumpQueue()

    /**
     * Acquire the wake lock + Wi-Fi lock and guarantee a queue worker exists when at
     * least one download is active; release the locks when none are. Called after
     * every state transition. The Wi-Fi lock prevents the radio from sleeping
     * mid-stream when the screen is off; the wake lock keeps the CPU running.
     *
     * The notification is not built here. [DownloadQueueWorker] owns it and reads the
     * same [items] flow, so there is nothing to push — this only ensures one worker is
     * running to read it.
     */
    private fun refreshServiceLifecycle() {
        val active = _items.value.count { it.isActive }
        if (active > 0) {
            ensureLocks()
            DownloadQueueWorker.ensureRunning(app)
        } else {
            releaseLocks()
            // Not cancelled: the worker exits on its own once the queue drains, and
            // cancelling here would tear down the foreground service while its final
            // notification update is still in flight.
        }
    }

    @Volatile private var lastLockRefreshMs = 0L

    /**
     * Progress-tick entry point into [refreshServiceLifecycle]. Ticks arrive several
     * times a second and none of them change the active/idle answer, so the only
     * thing this buys per call is pushing the wake lock's timeout back out — worth
     * doing, not worth doing at that rate.
     */
    private fun refreshLocksThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastLockRefreshMs < 500L) return
        lastLockRefreshMs = now
        refreshServiceLifecycle()
    }

    @Synchronized
    private fun ensureLocks() {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "snaglite:download").also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        // Re-acquire unconditionally rather than only when !isHeld. The timeout is a
        // leak guard, and acquiring again simply restarts it, so an active download
        // keeps pushing the deadline out instead of racing it.
        //
        // The old guard mostly self-healed, because this runs on every state
        // transition and a live download ticks constantly. What it could not recover
        // from was a download that stalled with no output: nothing called back in, so
        // an expired lock stayed expired for as long as the stall lasted — precisely
        // when the CPU needs to stay up for the transfer to resume.
        wl.acquire(WAKE_LOCK_TIMEOUT_MS)
        if (wifiLock?.isHeld != true) {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val fl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "snaglite:wifi")
            fl.setReferenceCounted(false)
            fl.acquire()
            wifiLock = fl
        }
    }

    @Synchronized
    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    companion object {
        private const val TAG = "DownloadController"
        const val MAX_ACTIVE = 3

        /**
         * Leak guard, not a budget. Refreshed on every state transition, so a live
         * download never reaches it; it only matters if the app dies without running
         * [releaseLocks], where it caps how long a stray lock can hold the CPU up.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L
    }
}
