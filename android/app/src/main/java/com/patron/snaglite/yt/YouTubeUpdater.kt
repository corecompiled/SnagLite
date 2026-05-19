package com.patron.snaglite.yt

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeUpdater {

    private const val TAG = "YTUpdater"

    /** Synchronously update yt-dlp. Returns true on success. */
    suspend fun updateNow(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                ctx,
                YoutubeDL.UpdateChannel.STABLE,
            )
            YouTubePrefs.setLastYtdlpUpdate(ctx, System.currentTimeMillis())
            Log.i(TAG, "yt-dlp update status: $status")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "yt-dlp update failed: ${t.message}")
            false
        }
    }

    /** Background update if last attempt was >7 days ago. Failure is silent. */
    suspend fun maybeUpdate(ctx: Context) = withContext(Dispatchers.IO) {
        val last = YouTubePrefs.lastYtdlpUpdate(ctx)
        if (System.currentTimeMillis() - last < YouTubePrefs.UPDATE_INTERVAL_MS) return@withContext
        updateNow(ctx)
    }

    fun version(ctx: Context): String? = runCatching {
        YoutubeDL.getInstance().version(ctx)
    }.getOrNull()
}
