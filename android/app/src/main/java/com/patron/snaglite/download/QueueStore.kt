package com.patron.snaglite.download

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Disk mirror of [DownloadController]'s queue.
 *
 * The queue used to live only in a `MutableStateFlow`, so process death took the
 * whole list with it — a force-stop, a low-memory kill, or an aggressive vendor
 * battery manager and every pending download was gone with no trace on relaunch.
 * This writes the list to `filesDir/queue.json` after each change and reads it
 * back on cold start.
 *
 * Uses `org.json` rather than kotlinx.serialization on purpose: the payload is a
 * flat list of small objects, and the alternative costs a dependency plus a
 * compiler plugin on a toolchain that CLAUDE.md documents as sensitive to exactly
 * that kind of change.
 *
 * **Progress is deliberately not persisted.** A restored item has no running
 * process behind it, so a percentage would be a claim about work nobody is doing.
 * Restored in-flight items come back as [DownloadStatus.Queued] and resume through
 * yt-dlp's `--continue` against the partial files in the work directory — which is
 * why those had to move out of `cacheDir` first.
 */
internal object QueueStore {

    private const val TAG = "QueueStore"
    private const val FILE_NAME = "queue.json"

    private const val K_ITEMS = "items"
    private const val K_ID = "id"
    private const val K_URL = "url"
    private const val K_AUDIO_ONLY = "audioOnly"
    private const val K_TITLE = "title"
    private const val K_CREATED_AT = "createdAt"
    private const val K_PLAYLIST_INDEX = "playlistIndex"
    private const val K_PLAYLIST_TOTAL = "playlistTotal"
    private const val K_THUMBNAIL = "thumbnailUrl"
    private const val K_DURATION = "durationSec"
    private const val K_UPLOADER = "uploader"
    private const val K_FILESIZE = "filesizeBytes"

    private const val K_STATUS = "status"
    private const val K_STATUS_URI = "uri"
    private const val K_STATUS_NAME = "displayName"
    private const val K_STATUS_MESSAGE = "message"
    private const val K_STATUS_SIGN_IN = "needsYouTubeSignIn"
    private const val K_STATUS_ENGINE = "needsEngineUpdate"
    private const val K_STATUS_DETAIL = "detail"

    private const val STATUS_QUEUED = "queued"
    private const val STATUS_DONE = "done"
    private const val STATUS_ERROR = "error"

    private fun fileFor(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /**
     * Best-effort write. A failure here loses durability for this tick but must
     * never take the download with it, so everything is wrapped.
     */
    fun save(ctx: Context, items: List<DownloadItem>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { arr.put(toJson(it)) }
            val root = JSONObject().put(K_ITEMS, arr)
            // Write to a temp file and rename, so a kill mid-write cannot leave a
            // truncated queue.json that fails to parse on the next launch.
            val target = fileFor(ctx)
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "queue save failed: ${it.message}") }
    }

    /** Returns an empty list when the file is absent, unreadable, or corrupt. */
    fun load(ctx: Context): List<DownloadItem> = runCatching {
        val f = fileFor(ctx)
        if (!f.exists()) return emptyList()
        val root = JSONObject(f.readText())
        val arr = root.optJSONArray(K_ITEMS) ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
    }.onFailure { Log.w(TAG, "queue load failed: ${it.message}") }.getOrDefault(emptyList())

    fun clear(ctx: Context) {
        runCatching { fileFor(ctx).delete() }
    }

    private fun toJson(item: DownloadItem): JSONObject = JSONObject().apply {
        put(K_ID, item.id)
        put(K_URL, item.url)
        put(K_AUDIO_ONLY, item.audioOnly)
        put(K_TITLE, item.title)
        put(K_CREATED_AT, item.createdAt)
        item.playlistIndex?.let { put(K_PLAYLIST_INDEX, it) }
        item.playlistTotal?.let { put(K_PLAYLIST_TOTAL, it) }
        item.thumbnailUrl?.let { put(K_THUMBNAIL, it) }
        item.durationSec?.let { put(K_DURATION, it) }
        item.uploader?.let { put(K_UPLOADER, it) }
        item.filesizeBytes?.let { put(K_FILESIZE, it) }
        put(K_STATUS, statusToJson(item.status))
    }

    private fun statusToJson(status: DownloadStatus): JSONObject = JSONObject().apply {
        when (status) {
            is DownloadStatus.Done -> {
                put(K_STATUS, STATUS_DONE)
                put(K_STATUS_URI, status.publicUri.toString())
                put(K_STATUS_NAME, status.displayName)
            }
            is DownloadStatus.Error -> {
                put(K_STATUS, STATUS_ERROR)
                put(K_STATUS_MESSAGE, status.message)
                put(K_STATUS_SIGN_IN, status.needsYouTubeSignIn)
                put(K_STATUS_ENGINE, status.needsEngineUpdate)
                // Without this the Details toggle silently disappears on the next
                // launch — the one moment someone is most likely to go looking for
                // it, having just been interrupted.
                status.detail?.let { put(K_STATUS_DETAIL, it) }
            }
            // Queued, Preparing, Running and Paused all collapse to Queued: their
            // process is gone once this file is read back, so any other state would
            // describe work that is not happening.
            else -> put(K_STATUS, STATUS_QUEUED)
        }
    }

    private fun fromJson(o: JSONObject): DownloadItem = DownloadItem(
        id = o.getString(K_ID),
        url = o.getString(K_URL),
        audioOnly = o.optBoolean(K_AUDIO_ONLY, false),
        title = o.optString(K_TITLE, o.getString(K_URL)),
        status = statusFromJson(o.optJSONObject(K_STATUS)),
        playlistIndex = if (o.has(K_PLAYLIST_INDEX)) o.getInt(K_PLAYLIST_INDEX) else null,
        playlistTotal = if (o.has(K_PLAYLIST_TOTAL)) o.getInt(K_PLAYLIST_TOTAL) else null,
        createdAt = o.optLong(K_CREATED_AT, System.currentTimeMillis()),
        thumbnailUrl = if (o.has(K_THUMBNAIL)) o.getString(K_THUMBNAIL) else null,
        durationSec = if (o.has(K_DURATION)) o.getInt(K_DURATION) else null,
        uploader = if (o.has(K_UPLOADER)) o.getString(K_UPLOADER) else null,
        filesizeBytes = if (o.has(K_FILESIZE)) o.getLong(K_FILESIZE) else null,
    )

    private fun statusFromJson(o: JSONObject?): DownloadStatus {
        if (o == null) return DownloadStatus.Queued
        return when (o.optString(K_STATUS, STATUS_QUEUED)) {
            STATUS_DONE -> DownloadStatus.Done(
                publicUri = Uri.parse(o.optString(K_STATUS_URI)),
                displayName = o.optString(K_STATUS_NAME),
            )
            STATUS_ERROR -> DownloadStatus.Error(
                message = o.optString(K_STATUS_MESSAGE),
                needsYouTubeSignIn = o.optBoolean(K_STATUS_SIGN_IN, false),
                needsEngineUpdate = o.optBoolean(K_STATUS_ENGINE, false),
                detail = if (o.has(K_STATUS_DETAIL)) o.getString(K_STATUS_DETAIL) else null,
            )
            else -> DownloadStatus.Queued
        }
    }
}
