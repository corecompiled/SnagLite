package com.patron.snaglite.download.resolvers

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import kotlin.random.Random

class ResolverA : IResolver() {

    override val name = "a"

    private val hostSuffixes = listOf(
        "dood.", "doods.", "dood-", "ds2play.", "ds2video.",
        "dsvplay.", "playmogo.", "all3do.", "vidply.", "d000d.",
        "d0000d.", "d0o0d.", "doply.", "dooood.", "vide0.",
    )

    private val passMd5Regex = Regex("""/pass_md5/[A-Za-z0-9_\-/]+""")
    private val titleRegex = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)

    override fun matches(host: String): Boolean {
        val h = host.lowercase()
        return hostSuffixes.any { h.contains(it) }
    }

    override suspend fun resolve(url: String, http: OkHttpClient): ResolvedMedia? {
        var embed = Uri.parse(url)
        val path = embed.path ?: ""
        if (path.startsWith("/d/")) {
            embed = embed.buildUpon().path("/e/" + path.substring(3)).build()
        }
        val embedStr = embed.toString()

        val pageRes = HttpFetch.get(http, embedStr, referer = embedStr)
        if (pageRes.statusCode !in 200..399) {
            Log.w(TAG, "embed GET ${pageRes.statusCode} -> ${pageRes.effectiveUrl}")
            return null
        }

        val landing = Uri.parse(pageRes.effectiveUrl)
        val landingOrigin = "${landing.scheme}://${landing.host}"
        val html = pageRes.body

        val passMatch = passMd5Regex.find(html)
        if (passMatch == null) {
            Log.w(TAG, "no pass_md5 in HTML (len=${html.length}) at $landing")
            return null
        }
        val passPath = passMatch.value
        val token = passPath.substringAfterLast('/')

        val passUrl = "$landingOrigin$passPath"
        val passRes = HttpFetch.get(
            http, passUrl,
            referer = landing.toString(),
            extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        if (passRes.statusCode !in 200..399) {
            Log.w(TAG, "pass_md5 GET ${passRes.statusCode} at $passUrl")
            return null
        }
        val baseUrl = passRes.body.trim()
        if (baseUrl.isBlank() || !baseUrl.startsWith("http")) {
            Log.w(TAG, "pass_md5 body not URL: '${baseUrl.take(120)}'")
            return null
        }

        val rand = randomAlnum(10)
        val expiryMs = System.currentTimeMillis()
        val finalUrl = "$baseUrl$rand?token=$token&expiry=$expiryMs"

        var title = titleRegex.find(html)?.groupValues?.get(1)?.trim().orEmpty()
        if (title.isBlank()) title = token
        val safe = sanitizeFilename(title) + ".mp4"

        return ResolvedMedia(
            downloadUrl = finalUrl,
            referer = "$landingOrigin/",
            suggestedName = safe,
            userAgent = HttpFetch.DEFAULT_UA,
        )
    }

    private fun randomAlnum(n: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(n) { repeat(n) { append(chars[Random.nextInt(chars.length)]) } }
    }

    private fun sanitizeFilename(s: String): String {
        val invalid = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        var out = s
        for (c in invalid) out = out.replace(c, '_')
        out = Regex("""\s+""").replace(out, " ").trim()
        if (out.length > 120) out = out.substring(0, 120).trim()
        return out.ifBlank { "video" }
    }

    companion object { private const val TAG = "ResolverA" }
}
