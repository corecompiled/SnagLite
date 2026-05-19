package com.patron.snaglite.download

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.patron.snaglite.SnagLiteApplication
import com.patron.snaglite.service.DownloadService
import com.patron.snaglite.yt.YouTubePlaylist
import kotlinx.coroutines.CoroutineScope
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
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

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
                    if (it.status is DownloadStatus.Running) it.copy(status = DownloadStatus.Running(pct, line))
                    else it
                }
                bumpServiceNotification()
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
                    bumpServiceNotification()
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
                    updateItem(id) {
                        it.copy(
                            title = published.displayName,
                            status = DownloadStatus.Done(published.uri, published.displayName),
                        )
                    }
                } else {
                    updateItem(id) {
                        it.copy(status = DownloadStatus.Error("Saved to cache but could not publish to the gallery."))
                    }
                }
            }
            is DownloadResult.Failure -> {
                if (result.needsYouTubeSignIn) pendingSignInId = id
                updateItem(id) {
                    it.copy(
                        status = DownloadStatus.Error(
                            message = result.message,
                            needsYouTubeSignIn = result.needsYouTubeSignIn,
                        ),
                    )
                }
            }
        }
    }

    private fun workDirFor(id: String): File = File(File(app.cacheDir, "snaglite-dl"), id)

    private fun currentItem(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    private fun updateItems(transform: (List<DownloadItem>) -> List<DownloadItem>) {
        _items.value = transform(_items.value)
    }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
    }

    /**
     * Acquire the wake lock + Wi-Fi lock + foreground service when at least one
     * download is active, release when none are. Called after every state
     * transition. The Wi-Fi lock prevents the radio from sleeping mid-stream
     * when the screen is off; the wake lock keeps the CPU running.
     */
    private fun refreshServiceLifecycle() {
        val items = _items.value
        val active = items.count { it.isActive }
        if (active > 0) {
            ensureLocks()
            val running = items.firstOrNull { it.status is DownloadStatus.Running } ?: items.first { it.isActive }
            val pct = (running.status as? DownloadStatus.Running)?.percent
            val title = if (active == 1) "SnagLite — downloading" else "SnagLite — $active downloads"
            val text = running.title.take(80) +
                (pct?.let { "  ${"%.0f".format(it)}%" } ?: "")
            DownloadService.start(app, title, text)
        } else {
            releaseLocks()
            DownloadService.stop(app)
        }
    }

    @Volatile private var lastNotifMs = 0L
    private fun bumpServiceNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifMs < 500L) return
        lastNotifMs = now
        refreshServiceLifecycle()
    }

    @Synchronized
    private fun ensureLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "snaglite:download")
            wl.setReferenceCounted(false)
            wl.acquire(2L * 60L * 60L * 1000L)
            wakeLock = wl
        }
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
    }
}
