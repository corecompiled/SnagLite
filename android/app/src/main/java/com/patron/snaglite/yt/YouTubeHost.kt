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

    private val SIGN_IN_REGEX = Regex(
        "Sign in to confirm you.?re not a bot|Please sign in|HTTP Error 403|Private video|members-only|This video is unavailable|age-restricted|This video may be inappropriate",
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
}
