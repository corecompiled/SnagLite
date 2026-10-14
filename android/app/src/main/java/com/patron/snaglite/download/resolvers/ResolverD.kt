package com.patron.snaglite.download.resolvers

import android.net.Uri
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Pornhub watch pages. yt-dlp has a native extractor for these, but the site answers
 * the Android build's Python client with HTTP 403 on the watch page itself — it
 * fingerprints the TLS handshake, and yt-dlp's fix (curl_cffi impersonation) is not
 * available in youtubedl-android. OkHttp on the same device and network gets a 200,
 * so the page and the media lookup are fetched here instead and the resulting mp4 is
 * handed to DirectDownloader.
 *
 * Flow: watch page -> `flashvars_<id>` JSON -> the `format == "mp4"` media definition,
 * whose `videoUrl` is a `get_media` endpoint -> JSON list of progressive mp4s, one per
 * quality -> highest quality wins. `get_media` returns an empty list unless the cookies
 * set by the watch page come with it, hence the per-resolve cookie jar.
 */
class ResolverD : IResolver() {

    override val name = "d"

    private val hostSuffixes = listOf("pornhub.com", "pornhub.org")

    private val flashvarsRegex = Regex("""var\s+flashvars_\d+\s*=\s*\{""")
    private val viewKeyRegex = Regex("""^/embed/([A-Za-z0-9]+)""")

    override fun matches(host: String): Boolean {
        val h = host.lowercase()
        return hostSuffixes.any { h == it || h.endsWith(".$it") }
    }

    override suspend fun resolve(url: String, http: OkHttpClient): ResolvedMedia? {
        val pageUrl = watchUrl(url)
        val client = http.newBuilder().cookieJar(MemoryCookieJar()).build()

        val page = HttpFetch.get(client, pageUrl)
        if (page.statusCode !in 200..299) {
            lastError = "watch page returned HTTP ${page.statusCode}"
            return null
        }
        val flashvars = parseFlashvars(page.body)
        if (flashvars == null) {
            lastError = "no flashvars object in the watch page (len=${page.body.length})"
            return null
        }

        val mediaUrl = mp4DefinitionUrl(flashvars)
        if (mediaUrl == null) {
            lastError = "flashvars has no mp4 media definition"
            return null
        }

        val media = HttpFetch.get(
            client, mediaUrl,
            referer = pageUrl,
            extraHeaders = mapOf("Accept" to "application/json, text/plain, */*"),
        )
        if (media.statusCode !in 200..299) {
            lastError = "media lookup returned HTTP ${media.statusCode}"
            return null
        }
        val best = pickBest(media.body)
        if (best == null) {
            lastError = "media lookup listed no mp4 files"
            return null
        }
        // Untrusted remote value about to be fetched — must be absolute http(s).
        val scheme = Uri.parse(best).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            lastError = "media URL is not http(s)"
            return null
        }

        val title = flashvars.optString("video_title").trim()
            .ifBlank { Uri.parse(pageUrl).getQueryParameter("viewkey").orEmpty() }
        Log.i(TAG, "resolved ${best.substringBefore('?').substringAfterLast('/')}")
        return ResolvedMedia(
            downloadUrl = best,
            referer = pageUrl,
            suggestedName = sanitizeFilename(title) + ".mp4",
            userAgent = HttpFetch.DEFAULT_UA,
        )
    }

    /** `/embed/<key>` pages carry a different player; the watch page is the one parsed here. */
    private fun watchUrl(url: String): String {
        val uri = Uri.parse(url)
        val key = viewKeyRegex.find(uri.path.orEmpty())?.groupValues?.get(1) ?: return url
        return "${uri.scheme}://${uri.host}/view_video.php?viewkey=$key"
    }

    /**
     * The object is assigned inline in a script block and followed by more script, so
     * it is read with a tokenizer — which stops at the end of the first JSON value —
     * rather than a regex guessing where the object ends.
     */
    private fun parseFlashvars(html: String): JSONObject? {
        val m = flashvarsRegex.find(html) ?: return null
        val start = m.range.last
        return runCatching { JSONTokener(html.substring(start)).nextValue() as? JSONObject }
            .getOrNull()
    }

    private fun mp4DefinitionUrl(flashvars: JSONObject): String? {
        val defs = flashvars.optJSONArray("mediaDefinitions") ?: return null
        for (i in 0 until defs.length()) {
            val d = defs.optJSONObject(i) ?: continue
            if (d.optString("format") == "mp4") {
                return d.optString("videoUrl").takeIf { it.startsWith("http") }
            }
        }
        return null
    }

    /** `quality` arrives as a string ("1080"); anything non-numeric sorts last. */
    private fun pickBest(json: String): String? {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        var bestUrl: String? = null
        var bestQ = -1
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val u = e.optString("videoUrl")
            if (u.isBlank()) continue
            val q = e.optString("quality").toIntOrNull() ?: e.optInt("height", 0)
            if (q > bestQ) { bestQ = q; bestUrl = u }
        }
        return bestUrl
    }

    private fun sanitizeFilename(s: String): String {
        val invalid = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        var out = s
        for (c in invalid) out = out.replace(c, '_')
        out = Regex("""\s+""").replace(out, " ").trim()
        if (out.length > 120) out = out.substring(0, 120).trim()
        return out.ifBlank { "video" }
    }

    /** Lives for one resolve: the cookies matter only between the page and get_media. */
    private class MemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store["${it.domain}|${it.path}|${it.name}"] = it }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            store.values.removeAll { it.expiresAt < now }
            return store.values.filter { it.matches(url) }
        }
    }

    companion object { private const val TAG = "ResolverD" }
}
