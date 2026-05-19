package com.patron.snaglite.yt

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubePlaylist {

    private const val TAG = "YTPlaylist"
    private val LIST_PARAM = Regex("[?&]list=([A-Za-z0-9_-]+)")

    data class Item(val id: String, val title: String) {
        val watchUrl: String get() = "https://www.youtube.com/watch?v=$id"
    }

    fun isPlaylist(url: String): Boolean {
        if (!YouTubeHost.isYouTube(url)) return false
        return LIST_PARAM.containsMatchIn(url)
    }

    /**
     * Enumerate playlist entries via `yt-dlp --flat-playlist --print "id\ttitle"`.
     * Light call: no per-video metadata, no streams. Returns empty on failure (logged).
     */
    suspend fun enumerate(ctx: Context, url: String): List<Item> = withContext(Dispatchers.IO) {
        val req = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--no-warnings")
            addOption("--print", "%(id)s\t%(title)s")
            YouTubeArgsInjector.augment(this, url, ctx, forceGeneric = false)
        }
        val processId = "playlist-${url.hashCode()}"
        val res = try {
            YoutubeDL.getInstance().execute(req, processId) { _, _, _ -> }
        } catch (t: Throwable) {
            Log.e(TAG, "enumerate threw", t)
            return@withContext emptyList()
        }
        if (res.exitCode != 0) {
            Log.w(TAG, "enumerate exit ${res.exitCode}: ${res.err.takeLast(400)}")
            return@withContext emptyList()
        }
        val items = res.out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                val id = parts.getOrNull(0)?.trim().orEmpty()
                val title = parts.getOrNull(1)?.trim().orEmpty()
                if (id.isBlank()) null else Item(id, title.ifBlank { id })
            }
            .toList()
        Log.i(TAG, "enumerated ${items.size} items from $url")
        items
    }
}
