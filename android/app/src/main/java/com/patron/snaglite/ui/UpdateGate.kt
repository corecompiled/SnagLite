package com.patron.snaglite.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.patron.snaglite.SnagLiteApplication
import com.patron.snaglite.yt.EngineUpdateChecker
import com.patron.snaglite.yt.YouTubePrefs
import com.patron.snaglite.yt.YouTubeUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Checks once per app launch whether the download engine has a newer release.
 * Cadence: every 3 days, with snooze. Prompts via AlertDialog; install only on consent.
 */
@Composable
fun UpdateGate(app: Application) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<EngineUpdateChecker.Result?>(null) }
    var installing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val res = withContext(Dispatchers.IO) { EngineUpdateChecker.checkIfDue(ctx) }
        if (res != null) pending = res
    }

    pending?.let { res ->
        AlertDialog(
            onDismissRequest = {
                if (!installing) {
                    YouTubePrefs.setEngineSnoozeUntil(
                        ctx,
                        System.currentTimeMillis() + YouTubePrefs.ENGINE_CHECK_INTERVAL_MS,
                    )
                    pending = null
                }
            },
            title = { Text("Update available") },
            text = {
                Text(
                    "A newer version of the download engine is available.\n\n" +
                        "Installed: ${res.current}\nLatest: ${res.latest}\n\n" +
                        "Install it now to keep video sites working reliably.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !installing,
                    onClick = {
                        installing = true
                        val sapp = app as SnagLiteApplication
                        sapp.applicationScope.launch {
                            val ok = YouTubeUpdater.updateNow(ctx)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    ctx,
                                    if (ok) "Download engine updated."
                                    else "Could not update the download engine.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                installing = false
                                pending = null
                            }
                        }
                    },
                ) { Text(if (installing) "Updating…" else "Update now") }
            },
            dismissButton = {
                TextButton(
                    enabled = !installing,
                    onClick = {
                        YouTubePrefs.setEngineSnoozeUntil(
                            ctx,
                            System.currentTimeMillis() + YouTubePrefs.ENGINE_CHECK_INTERVAL_MS,
                        )
                        pending = null
                    },
                ) { Text("Later") }
            },
        )
    }
}
