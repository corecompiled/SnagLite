package com.patron.snaglite.yt

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import java.io.File

object CookieStore {

    private const val TAG = "CookieStore"
    private const val FILE_NAME = "yt-cookies.txt"

    fun cookiesFile(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun exists(ctx: Context): Boolean = cookiesFile(ctx).let { it.exists() && it.length() > 0 }

    fun clear(ctx: Context) {
        runCatching { cookiesFile(ctx).delete() }
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
        runCatching { CookieManager.getInstance().flush() }
    }

    /**
     * Read cookies for youtube.com + accounts.google.com from Android's CookieManager
     * and write them as a Netscape-format cookies.txt yt-dlp can consume.
     * Returns true if at least one cookie was written.
     */
    fun importFromAndroidCookieManager(ctx: Context): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val entries = mutableListOf<Triple<String, String, String>>() // domain, name, value
        val domains = listOf(
            ".youtube.com" to "https://www.youtube.com",
            ".google.com" to "https://accounts.google.com",
        )
        for ((domain, url) in domains) {
            val raw = cm.getCookie(url) ?: continue
            for (pair in raw.split(';')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isEmpty()) continue
                entries.add(Triple(domain, name, value))
            }
        }
        if (entries.isEmpty()) {
            Log.w(TAG, "no cookies found via CookieManager")
            return false
        }
        // De-dup by (domain, name) keeping the last seen
        val deduped = entries.associateBy { it.first to it.second }.values

        val expiry = (System.currentTimeMillis() / 1000L) + (365L * 24 * 3600)
        val sb = StringBuilder()
        sb.append("# Netscape HTTP Cookie File\n")
        sb.append("# https://curl.se/docs/http-cookies.html\n")
        sb.append("# This is a generated file!  Do not edit.\n\n")
        for ((domain, name, value) in deduped) {
            // domain  includeSubdomains  path  secure  expiry  name  value
            sb.append(domain).append('\t')
                .append("TRUE").append('\t')
                .append('/').append('\t')
                .append("TRUE").append('\t')
                .append(expiry).append('\t')
                .append(name).append('\t')
                .append(value).append('\n')
        }
        return try {
            cookiesFile(ctx).writeText(sb.toString())
            Log.i(TAG, "wrote ${deduped.size} cookies to ${cookiesFile(ctx).absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to write cookies file", t)
            false
        }
    }
}
