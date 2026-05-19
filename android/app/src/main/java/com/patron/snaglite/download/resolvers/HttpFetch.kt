package com.patron.snaglite.download.resolvers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class FetchResult(
    val statusCode: Int,
    val effectiveUrl: String,
    val body: String,
)

object HttpFetch {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun get(
        client: OkHttpClient,
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        userAgent: String = DEFAULT_UA,
    ): FetchResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply {
                if (!referer.isNullOrEmpty()) header("Referer", referer)
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()

        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            FetchResult(res.code, res.request.url.toString(), body)
        }
    }
}
