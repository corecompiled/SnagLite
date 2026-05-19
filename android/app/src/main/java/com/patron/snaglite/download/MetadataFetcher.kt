package com.patron.snaglite.download

import android.content.Context
import com.patron.snaglite.yt.YouTubeArgsInjector
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Fire-and-forget metadata probe. Runs `yt-dlp --dump-json --skip-download` to
 * pull the thumbnail URL, duration, uploader, and approximate filesize so the
 * UI can render a richer download card before / during the actual download.
 *
 * Failure is non-fatal: callers should fall back to whatever they already have.
 */
object MetadataFetcher {

    data class Metadata(
        val title: String?,
        val thumbnail: String?,
        val durationSec: Int?,
        val uploader: String?,
        val filesizeBytes: Long?,
    )

    suspend fun fetch(context: Context, url: String): Metadata? = withContext(Dispatchers.IO) {
        val req = YoutubeDLRequest(url).apply {
            addOption("--dump-json")
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
            YouTubeArgsInjector.augment(this, url, context, forceGeneric = false, useFallbackClients = false)
        }
        runCatching {
            val r = YoutubeDL.getInstance().execute(req)
            if (r.exitCode != 0) return@runCatching null
            parseFirstJsonLine(r.out)
        }.getOrNull()
    }

    private fun parseFirstJsonLine(out: String): Metadata? {
        val line = out.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: return null
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val thumb = obj.optString("thumbnail", null).ifEmptyNull()
            ?: pickThumbFromArray(obj.optJSONArray("thumbnails"))
        val duration = obj.optInt("duration", -1).takeIf { it > 0 }
        val uploader = obj.optString("uploader", null).ifEmptyNull()
            ?: obj.optString("channel", null).ifEmptyNull()
            ?: obj.optString("extractor_key", null).ifEmptyNull()
        val filesize = obj.optLongOrNull("filesize")
            ?: obj.optLongOrNull("filesize_approx")
        val title = obj.optString("title", null).ifEmptyNull()
        return Metadata(
            title = title,
            thumbnail = thumb,
            durationSec = duration,
            uploader = uploader,
            filesizeBytes = filesize,
        )
    }

    private fun pickThumbFromArray(arr: org.json.JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        // Pick the highest-resolution one if dimensions are present, otherwise the last.
        var best: String? = null
        var bestArea = -1L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url", null).ifEmptyNull() ?: continue
            val w = o.optInt("width", 0).toLong()
            val h = o.optInt("height", 0).toLong()
            val area = w * h
            if (area > bestArea) {
                bestArea = area
                best = u
            }
        }
        return best ?: arr.optJSONObject(arr.length() - 1)?.optString("url", null)?.ifEmptyNull()
    }

    private fun String?.ifEmptyNull(): String? = if (this.isNullOrBlank() || this == "null") null else this

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getLong(key) }.getOrNull()
    }
}
