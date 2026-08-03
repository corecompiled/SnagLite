package com.patron.snaglite.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.patron.snaglite.yt.CookieStore
import com.patron.snaglite.yt.YouTubePrefs

class YouTubeSignInActivity : ComponentActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            val title = TextView(context).apply {
                text = "Sign in to YouTube"
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val close = Button(context).apply {
                text = "Close"
                setOnClickListener { finishWith(RESULT_CANCELED) }
            }
            addView(title)
            addView(close)
        }
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            YouTubePrefs.webUa(this@YouTubeSignInActivity)?.let {
                settings.userAgentString = it
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    checkForSignInComplete()
                }
            }
        }
        container.addView(web)
        setContentView(container)

        // This is the app's only View-based screen. With targetSdk 36, Android
        // 15+ draws every window edge-to-edge and ignores
        // setDecorFitsSystemWindows, so without this the header label and the
        // Close button sit under the status bar and the bottom of the login
        // page — where Google puts its Sign in / Next button — sits under the
        // navigation bar. Insets are added to the root's own padding so any
        // design padding survives; the header keeps its 24px separately.
        val baseLeft = container.paddingLeft
        val baseTop = container.paddingTop
        val baseRight = container.paddingRight
        val baseBottom = container.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom,
            )
            // Returned unconsumed on purpose. The only children are a plain
            // LinearLayout header, which ignores insets entirely, and the
            // WebView — so there is nothing here that would double-apply the
            // system-bar padding, while WindowInsetsCompat.CONSUMED would also
            // swallow Type.ime() and stop the WebView reacting to the keyboard
            // it needs for the login form.
            insets
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.loadUrl(LOGIN_URL)
    }

    private fun checkForSignInComplete() {
        val cm = CookieManager.getInstance()
        cm.flush()
        val cookies = cm.getCookie("https://www.youtube.com") ?: return
        val hasSapisid = cookies.contains("SAPISID=")
        val has3psid = cookies.contains("__Secure-3PSID=")
        if (hasSapisid && has3psid) {
            Log.i(TAG, "sign-in detected, harvesting cookies")
            CookieStore.importFromAndroidCookieManager(this)
            YouTubePrefs.setSignedIn(this, true)
            finishWith(RESULT_OK)
        }
    }

    private fun finishWith(code: Int) {
        setResult(code)
        if (::web.isInitialized) runCatching { web.destroy() }
        finish()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    @Deprecated("Predictive back not relevant here", ReplaceWith(""))
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            finishWith(RESULT_CANCELED)
        }
    }

    companion object {
        private const val TAG = "YTSignIn"
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"

        const val RESULT_OK = Activity.RESULT_OK
        const val RESULT_CANCELED = Activity.RESULT_CANCELED
    }
}
