package com.patron.snaglite.yt

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lightweight check for a newer yt-dlp release. We hit the GitHub releases API
 * for the latest tag and compare against the installed version returned by
 * `YoutubeDL.version()`. No mutations — install only happens when the user opts in.
 */
object EngineUpdateChecker {

    private const val TAG = "EngineUpdateChecker"
    private const val LATEST_API =
        "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"

    data class Result(val current: String, val latest: String)

    suspend fun checkIfDue(ctx: Context): Result? {
        val now = System.currentTimeMillis()
        if (now < YouTubePrefs.engineSnoozeUntil(ctx)) {
            Log.i(TAG, "snoozed until ${YouTubePrefs.engineSnoozeUntil(ctx)}")
            return null
        }
        if (now - YouTubePrefs.lastEngineCheck(ctx) < YouTubePrefs.ENGINE_CHECK_INTERVAL_MS) {
            Log.i(TAG, "checked recently, skipping")
            return null
        }
        return checkNow(ctx)
    }

    /**
     * Fire-and-forget: if an update is due, install it silently. No UI, no
     * AlertDialog. Skipped on metered networks by default so we don't burn the
     * user's mobile data quota on a ~10 MB yt-dlp tarball.
     *
     * Called from app launch + after engine init. Idempotent: the 24 h debounce
     * inside checkIfDue prevents back-to-back invocations from doing work.
     */
    suspend fun runSilentUpdateIfDue(ctx: Context, wifiOnly: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            if (wifiOnly && isMeteredNetwork(ctx)) {
                Log.i(TAG, "metered network, deferring silent update")
                return@withContext false
            }
            val due = checkIfDue(ctx) ?: return@withContext false
            Log.i(TAG, "silent update due: ${due.current} -> ${due.latest}")
            YouTubeUpdater.updateNow(ctx)
        }

    /**
     * True when the active network is metered (mobile data, hotspot, some VPNs).
     * Returns false when there's no network at all — letting the update attempt
     * proceed and fail naturally rather than silently skip in airplane mode.
     */
    private fun isMeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun checkNow(ctx: Context): Result? = withContext(Dispatchers.IO) {
        val current = runCatching { YoutubeDL.getInstance().version(ctx) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "no installed version reported")
                return@withContext null
            }
        val latest = fetchLatestTag() ?: run {
            Log.w(TAG, "could not fetch latest tag")
            return@withContext null
        }
        YouTubePrefs.setLastEngineCheck(ctx, System.currentTimeMillis())
        Log.i(TAG, "current=$current latest=$latest")
        if (compareVersions(latest, current) > 0) Result(current, latest) else null
    }

    private fun fetchLatestTag(): String? {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SnagLite-Android")
            .build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.w(TAG, "GitHub API HTTP ${res.code}")
                    return null
                }
                val body = res.body?.string().orEmpty()
                JSONObject(body).optString("tag_name").trim().takeIf { it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GitHub fetch threw: ${t.message}")
            null
        }
    }

    private fun parseVersion(raw: String): IntArray =
        raw.removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }.toIntArray()

    private fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i) ?: 0
            val y = pb.getOrNull(i) ?: 0
            if (x != y) return x - y
        }
        return 0
    }
}
