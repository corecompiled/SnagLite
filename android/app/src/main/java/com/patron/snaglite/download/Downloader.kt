package com.patron.snaglite.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.patron.snaglite.download.resolvers.ResolverPipeline
import com.patron.snaglite.yt.YouTubeArgsInjector
import com.patron.snaglite.yt.YouTubeBootstrapper
import com.patron.snaglite.yt.YouTubeHost
import com.patron.snaglite.yt.YouTubePrefs
import com.patron.snaglite.yt.YouTubeUpdater
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface DownloadResult {
    data class Success(val file: File, val audioOnly: Boolean) : DownloadResult
    data class Failure(
        val message: String,
        val needsYouTubeSignIn: Boolean = false,
        val needs403Recovery: Boolean = false,
    ) : DownloadResult
}

object Downloader {

    private const val TAG = "Downloader"

    suspend fun run(
        context: Context,
        url: String,
        audioOnly: Boolean,
        workDir: File,
        processId: String,
        onProgress: (percent: Float, line: String) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (!workDir.exists()) workDir.mkdirs()

        // Step 1: iframe unwrap for known listing-page hosts.
        val pipeline = ResolverPipeline()
        var workUrl = try {
            pipeline.unwrapIfNeeded(url)
        } catch (t: Throwable) {
            Log.w(TAG, "iframe unwrap failed: ${t.message}")
            url
        }
        if (workUrl != url) Log.i(TAG, "unwrapped iframe: $workUrl")

        // Step 2: custom resolver pipeline (wrapper hosts yt-dlp doesn't recognize).
        // Only attempt when not audio-only (resolvers return mp4 streams).
        // When a resolver matches the host, we commit to it: yt-dlp's bundled extractors
        // for these embed sites are months stale, so falling through swaps a specific
        // error for the unhelpful generic "Unsupported URL".
        if (!audioOnly) {
            val resolver = pipeline.match(workUrl)
            if (resolver != null) {
                Log.i(TAG, "resolver: ${resolver.name}")
                onProgress(0f, "Resolving via ${resolver.name}…")
                val media = try {
                    pipeline.resolve(workUrl)
                } catch (t: Throwable) {
                    resolver.lastError = "resolver threw: ${t.message ?: t.javaClass.simpleName}"
                    Log.w(TAG, "resolver threw", t)
                    null
                }
                if (media != null) {
                    return@withContext try {
                        val produced = DirectDownloader.download(media, workDir, onProgress)
                        DownloadResult.Success(produced, audioOnly = false)
                    } catch (t: Throwable) {
                        Log.w(TAG, "direct download failed: ${t.message}", t)
                        DownloadResult.Failure(
                            "Couldn't download from ${resolver.name}: ${t.message ?: t.javaClass.simpleName}. " +
                                "If the site changed, open an issue with this URL.",
                        )
                    }
                }
                // resolve() returned null — surface its lastError instead of falling through.
                val cause = resolver.lastError ?: "unknown reason"
                return@withContext DownloadResult.Failure(
                    "Couldn't extract video from ${resolver.name}: $cause. " +
                        "Try Settings → Update yt-dlp now if the site recently changed.",
                )
            }
        }

        ytDlpFlow(context, workUrl, workDir, processId, audioOnly, onProgress)
    }

    /**
     * Kills any in-flight yt-dlp process for this id. Safe to call when nothing
     * is running. Used by DownloadController.pause()/remove() to interrupt the
     * native worker (the Kotlin coroutine cancellation alone cannot stop it).
     */
    fun killYtDlp(processId: String): Boolean =
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }.getOrDefault(false)

    private suspend fun ytDlpFlow(
        context: Context,
        url: String,
        workDir: File,
        processId: String,
        audioOnly: Boolean,
        onProgress: (Float, String) -> Unit,
    ): DownloadResult {
        val first = runOnce(context, url, workDir, processId, audioOnly, forceGeneric = false, useFallbackClients = false, skipAria2c = false, onProgress)
        if (first is DownloadResult.Failure && first.message.contains("Unsupported URL", ignoreCase = true)) {
            Log.i(TAG, "Unsupported URL — retrying with generic extractor")
            val retry = runOnce(context, url, workDir, processId, audioOnly, forceGeneric = true, useFallbackClients = false, skipAria2c = false, onProgress)
            return finalize(retry, workDir, audioOnly)
        }
        // YouTube auto-recovery: a 403 or sign-in/bot error from the primary client chain
        // is almost always an extractor-staleness symptom. Try once more after refreshing
        // yt-dlp + visitor_data, with the fallback client chain and yt-dlp's native HTTP
        // downloader (aria2c can amplify URL drift). Sign-in state is orthogonal — we
        // retry even when signed in.
        if (first is DownloadResult.Failure &&
            (first.needs403Recovery || first.needsYouTubeSignIn) &&
            YouTubeHost.isYouTube(url)
        ) {
            Log.i(TAG, "YouTube blocked first attempt — refreshing engine + visitor_data then retrying")
            onProgress(0f, "YouTube blocked download — refreshing…")
            runCatching { YouTubeUpdater.updateNow(context) }
                .onFailure { Log.w(TAG, "engine refresh failed: ${it.message}") }
            runCatching { YouTubeBootstrapper.harvest(context) }
                .onFailure { Log.w(TAG, "visitor_data refresh failed: ${it.message}") }
            val retry = runOnce(context, url, workDir, processId, audioOnly, forceGeneric = false, useFallbackClients = true, skipAria2c = true, onProgress)
            return finalize(retry, workDir, audioOnly)
        }
        return finalize(first, workDir, audioOnly)
    }

    private fun finalize(
        result: DownloadResult,
        workDir: File,
        audioOnly: Boolean,
    ): DownloadResult {
        if (result is DownloadResult.Failure) return result
        // Pick the largest *finished* file, ignoring any leftover .part / .ytdl temp files.
        val produced = workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.lastModified() }
            ?: return DownloadResult.Failure("Download finished but no file was produced.")
        return DownloadResult.Success(produced, audioOnly)
    }

    private fun runOnce(
        context: Context,
        url: String,
        workDir: File,
        processId: String,
        audioOnly: Boolean,
        forceGeneric: Boolean,
        useFallbackClients: Boolean,
        skipAria2c: Boolean,
        onProgress: (Float, String) -> Unit,
    ): DownloadResult {
        val req = YoutubeDLRequest(url).apply {
            addOption("-o", File(workDir, "%(title)s [%(id)s].%(ext)s").absolutePath)
            addOption("--no-mtime")
            addOption("--restrict-filenames")
            // Use .part files so an interrupted download can be resumed by --continue.
            addOption("--continue")
            addOption("--newline")

            if (!skipAria2c) {
                addOption("--external-downloader", "aria2c")
                addOption("--external-downloader-args", "aria2c:-x16 -s16 -k1M --console-log-level=warn")
            }

            if (forceGeneric) {
                addOption("--force-generic-extractor")
                runCatching { Uri.parse(url).host }.getOrNull()?.let { host ->
                    addOption("--add-header", "Referer:https://$host/")
                }
            }

            if (audioOnly) {
                addOption("-f", "ba/b")
                addOption("-x")
                addOption("--audio-format", "m4a")
            } else {
                addOption("-f", "bv*+ba/b")
                addOption("--merge-output-format", "mp4")
            }

            YouTubeArgsInjector.augment(this, url, context, forceGeneric, useFallbackClients)
        }

        return try {
            val response = YoutubeDL.getInstance().execute(req, processId) { progress, _, line ->
                onProgress(progress, line.takeLast(180))
            }
            if (response.exitCode == 0) {
                DownloadResult.Success(workDir, audioOnly)
            } else {
                val combined = response.err + "\n" + response.out
                val msg = response.err.ifBlank { response.out.takeLast(400) }
                val isYouTubeUrl = YouTubeHost.isYouTube(url)
                val needsSignIn = isYouTubeUrl &&
                    YouTubeHost.isSignInError(combined) &&
                    !YouTubePrefs.isSignedIn(context)
                val needs403 = isYouTubeUrl &&
                    !needsSignIn &&
                    YouTubeHost.is403Error(combined)
                DownloadResult.Failure(
                    msg,
                    needsYouTubeSignIn = needsSignIn,
                    needs403Recovery = needs403,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "execute() threw", t)
            // The yausername library raises YoutubeDLException for non-zero exits
            // instead of returning a Response, so 403 / sign-in detection has to
            // run on the thrown message too — otherwise the recovery branch above
            // never sees the failure mode.
            val text = t.message ?: t.javaClass.simpleName
            val isYouTubeUrl = YouTubeHost.isYouTube(url)
            val needsSignIn = isYouTubeUrl &&
                YouTubeHost.isSignInError(text) &&
                !YouTubePrefs.isSignedIn(context)
            val needs403 = isYouTubeUrl &&
                !needsSignIn &&
                YouTubeHost.is403Error(text)
            DownloadResult.Failure(
                text,
                needsYouTubeSignIn = needsSignIn,
                needs403Recovery = needs403,
            )
        }
    }
}
