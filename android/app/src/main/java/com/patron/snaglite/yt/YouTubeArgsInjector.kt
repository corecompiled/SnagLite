package com.patron.snaglite.yt

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest

object YouTubeArgsInjector {

    // tv_simply currently delivers PO-token-free formats; default + mweb are fallbacks.
    // formats=missing_pot prevents yt-dlp from silently dropping un-tokened formats.
    const val PRIMARY = "player_client=tv_simply,default,mweb;formats=missing_pot"
    const val FALLBACK = "player_client=web_safari,android_vr,mweb;formats=missing_pot"

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun augment(
        req: YoutubeDLRequest,
        url: String,
        ctx: Context,
        forceGeneric: Boolean,
        useFallbackClients: Boolean = false,
    ) {
        if (forceGeneric) return
        if (!YouTubeHost.isYouTube(url)) return

        val ua = YouTubePrefs.webUa(ctx).takeUnless { it.isNullOrBlank() } ?: FALLBACK_UA
        req.addOption("--user-agent", ua)
        req.addOption("--add-header", "Accept-Language:en-US,en;q=0.9")

        val base = if (useFallbackClients) FALLBACK else PRIMARY
        val visitor = YouTubePrefs.visitorDataIfFresh(ctx)
        val extractorArgs = if (!visitor.isNullOrBlank()) {
            "youtube:$base;visitor_data=$visitor"
        } else {
            "youtube:$base"
        }
        req.addOption("--extractor-args", extractorArgs)

        if (CookieStore.exists(ctx)) {
            req.addOption("--cookies", CookieStore.cookiesFile(ctx).absolutePath)
        }
    }
}
