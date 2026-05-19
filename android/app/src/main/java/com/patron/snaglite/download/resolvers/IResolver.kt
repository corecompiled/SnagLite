package com.patron.snaglite.download.resolvers

import okhttp3.OkHttpClient

data class ResolvedMedia(
    val downloadUrl: String,
    val referer: String,
    val suggestedName: String,
    val userAgent: String,
)

abstract class IResolver {
    abstract val name: String

    /** Set by `resolve` when it returns null, so callers can surface a useful message. */
    @Volatile
    var lastError: String? = null

    abstract fun matches(host: String): Boolean
    abstract suspend fun resolve(url: String, http: OkHttpClient): ResolvedMedia?
}
