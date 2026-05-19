package com.patron.snaglite.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.patron.snaglite.download.SnagLitePrefs
import com.patron.snaglite.yt.YouTubeUpdater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onReRunSetup: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var updating by remember { mutableStateOf(false) }
    var deleteFileOnRemove by remember { mutableStateOf(SnagLitePrefs.deleteFileOnRemove(ctx)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Text(
            "Downloads",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Delete file from device when removing",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Pre-checks the box in the swipe-to-remove dialog. You can still flip it per-download.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = deleteFileOnRemove,
                onCheckedChange = {
                    deleteFileOnRemove = it
                    SnagLitePrefs.setDeleteFileOnRemove(ctx, it)
                },
            )
        }

        HorizontalDivider()

        Text(
            "Maintenance",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(
            onClick = {
                if (updating) return@Button
                updating = true
                scope.launch {
                    val ok = YouTubeUpdater.updateNow(ctx)
                    updating = false
                    Toast.makeText(
                        ctx,
                        if (ok) "Download engine updated." else "Could not update the download engine.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            enabled = !updating,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (updating) "Updating…" else "Update download engine") }

        OutlinedButton(
            onClick = onReRunSetup,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Re-run initial setup") }

        HorizontalDivider()

        Text(
            "About",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "SnagLite — Version 1.0",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Developed by Paolo Patron.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
    }
}
