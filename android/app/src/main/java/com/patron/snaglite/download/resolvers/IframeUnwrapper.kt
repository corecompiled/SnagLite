package com.patron.snaglite.download.resolvers

import android.content.Context
import okhttp3.OkHttpClient

// Some hosts are pure listing pages that embed a third-party player via <iframe>.
// Unwrap to the inner URL before resolver/yt-dlp routing so the engine sees the
// real provider.
//
// The list of wrapper hosts is loaded at runtime from `hosts.local.txt` — a
// gitignored asset placed under `app/src/main/assets/`. If the file is missing
// (e.g. a fresh OSS clone), the list is empty and the unwrap feature is
// silently disabled; downloads still work through yt-dlp's normal extractor +
// generic-fallback chain. Call [init] once at process start (from
// SnagLiteApplication.onCreate). See PRIVATE_NOTES.local.md.
object IframeUnwrapper {

    @Volatile private var WRAPPER_HOSTS: List<String> = emptyList()

    fun init(context: Context) {
        WRAPPER_HOSTS = runCatching {
            context.assets.open("hosts.local.txt").bufferedReader().use { r ->
                r.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { it.lowercase() }
                    .toList()
            }
        }.getOrElse { emptyList() }
    }

    private val IFRAME_REGEX = Regex(
        """<iframe[^>]+src=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun shouldUnwrap(host: String): Boolean {
        val h = host.lowercase()
        return WRAPPER_HOSTS.any { h.endsWith(it) }
    }

    suspend fun unwrap(url: String, http: OkHttpClient): String? {
        val res = HttpFetch.get(http, url)
        if (res.statusCode !in 200..399) return null
        val m = IFRAME_REGEX.find(res.body) ?: return null
        var src = m.groupValues[1].trim()
        if (src.startsWith("//")) src = "https:$src"
        if (!src.startsWith("http", ignoreCase = true)) return null
        return src
    }
}
