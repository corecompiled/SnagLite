package com.patron.snaglite.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.patron.snaglite.yt.EngineUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Silent, fire-and-forget engine update check at the top of every MainScreen
 * composition. No UI surface — if a newer yt-dlp is available and we're on an
 * unmetered network, it installs in the background while the existing version
 * keeps working. Debounced to 24 h via [EngineUpdateChecker.runSilentUpdateIfDue].
 *
 * Suppressed parameter `app` kept so existing call sites don't need editing.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun UpdateGate(app: Application) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            EngineUpdateChecker.runSilentUpdateIfDue(ctx)
        }
    }
}
