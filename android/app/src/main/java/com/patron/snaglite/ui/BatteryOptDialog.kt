package com.patron.snaglite.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.patron.snaglite.download.SnagLitePrefs

/**
 * True iff a battery-optimization prompt is warranted: the user hasn't opted
 * out and SnagLite is not already on the exempt list.
 */
fun shouldPromptBattOpt(ctx: Context): Boolean {
    if (SnagLitePrefs.battOptDontAsk(ctx)) return false
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

@Composable
fun BatteryOptDialog(
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {
            if (dontAsk) SnagLitePrefs.setBattOptDontAsk(ctx, true)
            onDismiss()
        },
        title = { Text("Keep downloads running in the background") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Android may pause SnagLite's downloads when the screen is off or the device sits idle. " +
                        "Allowing the battery-optimization exemption lets a long download finish even when you " +
                        "lock the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = dontAsk, onCheckedChange = { dontAsk = it })
                    Text("Don't ask again", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (dontAsk) SnagLitePrefs.setBattOptDontAsk(ctx, true)
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData("package:${ctx.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }.onFailure {
                    // Some OEMs disable this Settings screen; open generic battery-opt list as a fallback.
                    runCatching {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(fallback)
                    }
                }
                onDismiss()
            }) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (dontAsk) SnagLitePrefs.setBattOptDontAsk(ctx, true)
                onDismiss()
            }) { Text("Not now") }
        },
    )
}
