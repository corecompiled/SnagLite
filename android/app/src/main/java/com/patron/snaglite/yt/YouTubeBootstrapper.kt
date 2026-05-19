package com.patron.snaglite.yt

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object YouTubeBootstrapper {

    private const val TAG = "YTBootstrap"
    private const val URL = "https://www.youtube.com/"
    private const val TIMEOUT_MS = 15_000L

    private const val JS_GET_VISITOR =
        "(function(){try{var c=window.ytcfg;var v=null;" +
            "if(c&&typeof c.get==='function'){var ic=c.get('INNERTUBE_CONTEXT');if(ic&&ic.client)v=ic.client.visitorData;}" +
            "if(!v&&c&&c.data_&&c.data_.INNERTUBE_CONTEXT&&c.data_.INNERTUBE_CONTEXT.client)v=c.data_.INNERTUBE_CONTEXT.client.visitorData;" +
            "return v||null;}catch(e){return null;}})();"

    /**
     * Loads youtube.com in a hidden WebView, harvests INNERTUBE visitor_data
     * + cookies, persists them. Failure is non-fatal.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun harvest(ctx: Context): Boolean = withContext(Dispatchers.Main) {
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val web = WebView(ctx)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                YouTubePrefs.setWebUa(ctx, web.settings.userAgentString)

                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(web, true)

                var settled = false
                fun finish(ok: Boolean) {
                    if (settled) return
                    settled = true
                    runCatching {
                        web.stopLoading()
                        web.destroy()
                    }
                    if (cont.isActive) cont.resume(ok)
                }

                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, urlLoaded: String?) {
                        view?.evaluateJavascript(JS_GET_VISITOR) { raw ->
                            val visitor = raw
                                ?.trim()
                                ?.removeSurrounding("\"")
                                ?.takeIf { it.isNotBlank() && it != "null" }
                            if (!visitor.isNullOrBlank()) {
                                YouTubePrefs.setVisitorData(ctx, visitor)
                                Log.i(TAG, "harvested visitor_data (${visitor.length} chars)")
                            } else {
                                Log.w(TAG, "visitor_data not found in ytcfg")
                            }
                            cm.flush()
                            CookieStore.importFromAndroidCookieManager(ctx)
                            finish(!visitor.isNullOrBlank())
                        }
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    @Deprecated("Legacy error callback kept for API <23 emulation", ReplaceWith(""))
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?,
                    ) {
                        Log.w(TAG, "webview error $errorCode: $description")
                    }
                }
                cont.invokeOnCancellation { finish(false) }
                web.loadUrl(URL)
            }
        }
        result == true
    }
}
