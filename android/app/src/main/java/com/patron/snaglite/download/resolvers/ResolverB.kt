package com.patron.snaglite.download.resolvers

import android.net.Uri
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class ResolverB : IResolver() {

    override val name = "b"

    private val hosts = listOf(
        "voe.sx", "voe-network.net", "voe-unblock.net",
        "maryspecialwatch.com", "yodelsounds.com", "donaldlineelse.com",
        "sirdomainprovides.com", "tooseasydefenseable.com",
    )

    private val redirectRegex = Regex(
        """window\.location\.href\s*=\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE,
    )

    private val payloadRegex = Regex(
        """<script[^>]+type=["']application/json["'][^>]*>(\[.*?\])</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    override fun matches(host: String): Boolean {
        val h = host.lowercase()
        return hosts.any { h == it || h.endsWith(".$it") }
    }

    override suspend fun resolve(url: String, http: OkHttpClient): ResolvedMedia? {
        val pageRes = HttpFetch.get(http, url, referer = url)
        if (pageRes.statusCode !in 200..399) {
            Log.w(TAG, "embed GET ${pageRes.statusCode}")
            return null
        }

        var html = pageRes.body
        var landingUrl = pageRes.effectiveUrl

        redirectRegex.find(html)?.let { m ->
            val dst = m.groupValues[1]
            val follow = HttpFetch.get(http, dst, referer = url)
            if (follow.statusCode in 200..399) {
                html = follow.body
                landingUrl = follow.effectiveUrl
            }
        }

        val payloadMatch = payloadRegex.find(html)
        if (payloadMatch == null) {
            Log.w(TAG, "no application/json payload found")
            return null
        }

        val encoded: String = try {
            JSONArray(payloadMatch.groupValues[1]).getString(0)
        } catch (t: Throwable) {
            Log.w(TAG, "payload JSON parse failed: ${t.message}")
            return null
        }
        if (encoded.isEmpty()) return null

        val decoded: String = try { decode(encoded) } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.message}")
            return null
        }

        val root: JSONObject = try { JSONObject(decoded) } catch (t: Throwable) {
            Log.w(TAG, "decoded JSON parse failed: ${t.message}")
            return null
        }

        val direct = tryStr(root, "direct_access_url")
        val source = tryStr(root, "source")
        val title = tryStr(root, "title") ?: tryStr(root, "file_code") ?: "video"
        val name = sanitizeFilename(stripExt(title)) + ".mp4"

        val landing = Uri.parse(landingUrl)
        val origin = "${landing.scheme}://${landing.host}/"

        if (!direct.isNullOrEmpty()) {
            return ResolvedMedia(
                downloadUrl = direct,
                referer = origin,
                suggestedName = name,
                userAgent = HttpFetch.DEFAULT_UA,
            )
        }

        if (!source.isNullOrEmpty()) {
            Log.w(TAG, "only HLS source available — falling back to yt-dlp.")
            return null
        }

        return null
    }

    private fun decode(s: String): String {
        var step = rot13(s)
        for (pat in listOf("@\$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            step = step.replace(pat, "")
        }
        val bytes1 = Base64.decode(step, Base64.DEFAULT)
        val step1 = String(bytes1, Charsets.UTF_8)
        val sb = StringBuilder(step1.length)
        for (c in step1) sb.append((c.code - 3).toChar())
        val step2 = sb.toString()
        val step3 = step2.reversed()
        val bytes2 = Base64.decode(step3, Base64.DEFAULT)
        return String(bytes2, Charsets.UTF_8)
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            sb.append(
                when (c) {
                    in 'a'..'z' -> ((c.code - 'a'.code + 13) % 26 + 'a'.code).toChar()
                    in 'A'..'Z' -> ((c.code - 'A'.code + 13) % 26 + 'A'.code).toChar()
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun tryStr(root: JSONObject, key: String): String? {
        if (!root.has(key)) return null
        val v = root.opt(key)
        return if (v is String) v else null
    }

    private fun stripExt(s: String): String {
        val i = s.lastIndexOf('.')
        return if (i > 0 && s.length - i <= 5) s.substring(0, i) else s
    }

    private fun sanitizeFilename(s: String): String {
        val invalid = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
        var out = s
        for (c in invalid) out = out.replace(c, '_')
        out = Regex("""\s+""").replace(out, " ").trim()
        if (out.length > 120) out = out.substring(0, 120).trim()
        return out.ifBlank { "video" }
    }

    companion object { private const val TAG = "ResolverB" }
}
