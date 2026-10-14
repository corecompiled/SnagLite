package com.patron.snaglite.download.resolvers

import android.net.Uri
import okhttp3.OkHttpClient

class ResolverPipeline {

    private val http: OkHttpClient = HttpFetch.newClient()

    private val resolvers: List<IResolver> = listOf(
        ResolverA(),
        ResolverB(),
        ResolverC(),
        ResolverD(),
    )

    /**
     * True when the host is in the runtime wrapper list, i.e. we expected to find an
     * iframe on it. Lets the caller tell "configured wrapper host that yielded
     * nothing" apart from "site we know nothing about" — two failures that otherwise
     * arrive looking identical.
     */
    fun isWrapperHost(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return IframeUnwrapper.shouldUnwrap(host)
    }

    fun match(url: String): IResolver? {
        val host = Uri.parse(url).host?.lowercase() ?: return null
        return resolvers.firstOrNull { it.matches(host) }
    }

    suspend fun resolve(url: String): ResolvedMedia? {
        val r = match(url) ?: return null
        return r.resolve(url, http)
    }

    suspend fun unwrapIfNeeded(url: String): String {
        val host = Uri.parse(url).host?.lowercase() ?: return url
        if (!IframeUnwrapper.shouldUnwrap(host)) return url
        return IframeUnwrapper.unwrap(url, http) ?: url
    }
}
