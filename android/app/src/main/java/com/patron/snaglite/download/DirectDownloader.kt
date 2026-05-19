package com.patron.snaglite.download

import android.util.Log
import com.patron.snaglite.download.resolvers.ResolvedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object DirectDownloader {

    private const val TAG = "DirectDownloader"
    private const val CHUNKS = 8
    private const val MIN_PART_SIZE = 1L * 1024 * 1024
    private const val BUFFER_SIZE = 1 shl 16

    // Shared across downloads so OkHttp's connection pool / DNS cache survives
    // between resolves — each new builder() spun up a fresh pool and forced a
    // full TLS handshake every time.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun download(
        media: ResolvedMedia,
        workDir: File,
        onProgress: (percent: Float, line: String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!workDir.exists()) workDir.mkdirs()
        val target = File(workDir, media.suggestedName)
        // Resume semantics: ranged GETs write at specific byte offsets and we don't
        // persist per-chunk progress, so resuming after a pause restarts from byte 0.
        // (yt-dlp's --continue path does real resume; this affects only direct hosts.)
        if (target.exists()) target.delete()

        val total = probeLength(client, media)
        Log.i(TAG, "probe length: $total bytes (referer=${media.referer})")

        if (total <= 0L) {
            Log.i(TAG, "no Content-Length; using single-stream GET")
            singleStream(client, media, target, onProgress)
            return@withContext target
        }

        if (total < MIN_PART_SIZE) {
            Log.i(TAG, "small file (<1MiB); using single-stream GET")
            singleStream(client, media, target, onProgress)
            return@withContext target
        }

        try {
            rangedDownload(client, media, target, total, onProgress)
        } catch (t: Throwable) {
            Log.w(TAG, "ranged download failed (${t.message}); retrying as single-stream")
            runCatching { target.delete() }
            singleStream(client, media, target, onProgress)
        }
        target
    }

    private fun probeLength(client: OkHttpClient, media: ResolvedMedia): Long {
        val req = Request.Builder()
            .url(media.downloadUrl)
            .head()
            .header("User-Agent", media.userAgent)
            .header("Referer", media.referer)
            .build()
        return runCatching {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    Log.i(TAG, "HEAD probe non-2xx (${res.code}); will fall through")
                    -1L
                } else res.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }.getOrElse { t ->
            Log.i(TAG, "HEAD probe threw (${t.message}); will fall through")
            -1L
        }
    }

    private fun singleStream(
        client: OkHttpClient,
        media: ResolvedMedia,
        target: File,
        onProgress: (Float, String) -> Unit,
    ) {
        val req = Request.Builder()
            .url(media.downloadUrl)
            .get()
            .header("User-Agent", media.userAgent)
            .header("Referer", media.referer)
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = res.body ?: error("empty body")
            val total = body.contentLength()
            val written = AtomicLong(0L)
            val startMs = System.currentTimeMillis()
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        val w = written.addAndGet(n.toLong())
                        publishProgress(w, total, startMs, onProgress)
                    }
                }
            }
        }
    }

    private suspend fun rangedDownload(
        client: OkHttpClient,
        media: ResolvedMedia,
        target: File,
        total: Long,
        onProgress: (Float, String) -> Unit,
    ) = coroutineScope {
        RandomAccessFile(target, "rw").use { raf -> raf.setLength(total) }

        val partSize = (total + CHUNKS - 1) / CHUNKS
        val written = AtomicLong(0L)
        val startMs = System.currentTimeMillis()

        val jobs = (0 until CHUNKS).map { i ->
            val start = i * partSize
            val end = minOf(start + partSize - 1, total - 1)
            async(Dispatchers.IO) {
                downloadRange(client, media, target, start, end, written, total, startMs, onProgress)
            }
        }
        jobs.forEach { it.await() }
        // Force a final 100% tick
        onProgress(100f, formatLine(total, total, startMs))
    }

    private fun downloadRange(
        client: OkHttpClient,
        media: ResolvedMedia,
        target: File,
        start: Long,
        end: Long,
        written: AtomicLong,
        total: Long,
        startMs: Long,
        onProgress: (Float, String) -> Unit,
    ) {
        val req = Request.Builder()
            .url(media.downloadUrl)
            .get()
            .header("User-Agent", media.userAgent)
            .header("Referer", media.referer)
            .header("Range", "bytes=$start-$end")
            .build()
        client.newCall(req).execute().use { res ->
            if (res.code !in 200..299) error("HTTP ${res.code} on range $start-$end")
            val body = res.body ?: error("empty body")
            val raf = RandomAccessFile(target, "rw")
            raf.use {
                it.seek(start)
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        it.write(buf, 0, n)
                        val w = written.addAndGet(n.toLong())
                        publishProgress(w, total, startMs, onProgress)
                    }
                }
            }
        }
    }

    @Volatile private var lastTickMs = 0L

    private fun publishProgress(
        written: Long,
        total: Long,
        startMs: Long,
        onProgress: (Float, String) -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTickMs < 250) return
        lastTickMs = now
        val pct = if (total > 0) (written.toFloat() / total.toFloat()) * 100f else 0f
        onProgress(pct.coerceIn(0f, 100f), formatLine(written, total, startMs))
    }

    private fun formatLine(written: Long, total: Long, startMs: Long): String {
        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
        val bps = written * 1000.0 / elapsedMs
        return "${humanBytes(written)} / ${humanBytes(total)}  ${humanBytes(bps.toLong())}/s"
    }

    private fun humanBytes(n: Long): String {
        if (n < 1024) return "${n}B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var d = n.toDouble() / 1024.0
        var i = 0
        while (d >= 1024.0 && i < units.lastIndex) { d /= 1024.0; i++ }
        return "%.1f%s".format(d, units[i])
    }
}
