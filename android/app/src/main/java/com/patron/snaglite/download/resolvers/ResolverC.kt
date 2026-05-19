package com.patron.snaglite.download.resolvers

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient

class ResolverC : IResolver() {

    override val name = "c"

    private val NUMERIC_HOST = Regex("^m\\d+xdrop\\b")

    private val EXTRACTORS: List<Pair<String, Regex>> = listOf(
        "MDCore.wurl=\"...\"" to Regex("""MDCore\.wurl\s*=\s*"([^"]+)""""),
        "MDCore.wurl='...'" to Regex("""MDCore\.wurl\s*=\s*'([^']+)'"""),
        "wurl=...quoted" to Regex("""\bwurl\s*[=:]\s*["']([^"']+)["']"""),
        "any-mp4-with-token" to Regex("""(?:https?:)?//[^\s"'<>]+?\.mp4\?[^\s"'<>]+"""),
        "MDCore.vurl" to Regex("""MDCore\.vurl\s*=\s*["']([^"']+)["']"""),
    )

    private val TITLE_REGEX = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)

    override fun matches(host: String): Boolean {
        val h = host.lowercase()
        if (NUMERIC_HOST.containsMatchIn(h)) return true
        return h.contains("mixdrop") ||
            h.contains("mixdrp") ||
            h.contains("mixdroop") ||
            h.contains("mxdrop") ||
            h.contains("m1xdrop") ||
            h.contains("mdfx9dc8n") ||
            h.contains("mxcontent")
    }

    override suspend fun resolve(url: String, http: OkHttpClient): ResolvedMedia? {
        lastError = null

        // Normalize /d/ and /f/ to /e/ so we always hit the embed page.
        var u = Uri.parse(url)
        val originalPath = u.path.orEmpty()
        when {
            originalPath.startsWith("/d/") -> u = u.buildUpon().path("/e/" + originalPath.substring(3)).build()
            originalPath.startsWith("/f/") -> u = u.buildUpon().path("/e/" + originalPath.substring(3)).build()
        }
        val embedStr = u.toString()
        Log.i(TAG, "resolving $embedStr")

        val res = try {
            HttpFetch.get(http, embedStr, referer = embedStr)
        } catch (t: Throwable) {
            lastError = "embed fetch threw: ${t.message ?: t.javaClass.simpleName}"
            Log.w(TAG, lastError!!)
            return null
        }
        if (res.statusCode !in 200..399) {
            lastError = "embed fetch HTTP ${res.statusCode}"
            Log.w(TAG, "$lastError -> ${res.effectiveUrl}")
            return null
        }
        val landing = Uri.parse(res.effectiveUrl)
        val origin = "${landing.scheme}://${landing.host}"
        val html = res.body
        Log.i(TAG, "fetched ${html.length} bytes from ${landing}")

        // Strategy 1: try to unpack P.A.C.K.E.R.
        val unpacked = Packer.unpack(html)
        if (unpacked == null) {
            Log.w(TAG, "Packer.unpack returned null; will scan raw HTML as last resort")
        } else {
            Log.i(TAG, "unpacked payload (${unpacked.length} bytes); first 120: ${unpacked.take(120)}")
        }

        // Strategy 2: walk extractors over (a) unpacked, (b) raw HTML.
        val direct = extractWurl(unpacked) ?: extractWurl(html)
        if (direct == null) {
            lastError = "no video URL found (Packer ${if (unpacked == null) "miss" else "ok"}, all 5 extraction patterns missed)"
            Log.w(TAG, lastError!!)
            return null
        }

        var finalUrl = direct.trim()
        if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
        if (!finalUrl.startsWith("http", ignoreCase = true)) {
            lastError = "extracted URL not absolute: '${finalUrl.take(80)}'"
            Log.w(TAG, lastError!!)
            return null
        }
        Log.i(TAG, "resolved direct URL: ${finalUrl.take(120)}")

        val title = TITLE_REGEX.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val cleanTitle = title
            .replace("Watch ", "", ignoreCase = true)
            .trim()
        val id = originalPath.substringAfterLast('/').ifBlank { "video" }
        val safeName = sanitizeFilename(cleanTitle.ifBlank { id }) + ".mp4"

        return ResolvedMedia(
            downloadUrl = finalUrl,
            referer = "$origin/",
            suggestedName = safeName,
            userAgent = HttpFetch.DEFAULT_UA,
        )
    }

    private fun extractWurl(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        EXTRACTORS.forEachIndexed { idx, (label, regex) ->
            val m = regex.find(text)
            if (m != null) {
                val captured = if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.value
                Log.i(TAG, "matched via pattern ${idx + 1} ($label) -> ${captured.take(120)}")
                return captured
            }
        }
        return null
    }

    private fun sanitizeFilename(s: String): String {
        val invalid = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        var out = s
        for (c in invalid) out = out.replace(c, '_')
        out = Regex("""\s+""").replace(out, " ").trim()
        if (out.length > 120) out = out.substring(0, 120).trim()
        return out.ifBlank { "video" }
    }

    companion object {
        private const val TAG = "ResolverC"
    }
}
