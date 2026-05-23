package com.patron.snaglite.yt

import android.net.Uri

object YouTubeHost {

    private val HOSTS = setOf(
        "youtube.com",
        "youtu.be",
        "m.youtube.com",
        "music.youtube.com",
        "youtube-nocookie.com",
        "www.youtube.com",
        "www.youtube-nocookie.com",
    )

    // Real sign-in / access-restriction signals. 403 alone is *not* one — it's
    // almost always an extractor-staleness symptom on YouTube. "This video is
    // unavailable" is also intentionally NOT here: it covers private + region-
    // locked + deleted videos, none of which signing in fixes.
    private val SIGN_IN_REGEX = Regex(
        "Sign in to confirm you.?re not a bot|Please sign in|members-only|age-restricted|This video may be inappropriate|Login required|This video requires payment|Private video",
        RegexOption.IGNORE_CASE,
    )

    private val FORBIDDEN_REGEX = Regex(
        "HTTP Error 403|\\b403 Forbidden\\b|HTTP/[\\d.]+ 403|status code 403|errorCode=403",
        RegexOption.IGNORE_CASE,
    )

    fun isYouTube(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        if (host in HOSTS) return true
        return HOSTS.any { host.endsWith(".$it") }
    }

    fun isSignInError(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return SIGN_IN_REGEX.containsMatchIn(text)
    }

    fun is403Error(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return FORBIDDEN_REGEX.containsMatchIn(text)
    }
}
