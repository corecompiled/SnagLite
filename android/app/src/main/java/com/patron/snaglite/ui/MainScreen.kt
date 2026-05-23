package com.patron.snaglite.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.patron.snaglite.download.DownloadItem
import com.patron.snaglite.download.SnagLitePrefs
import com.patron.snaglite.webview.YouTubeSignInActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onResetSetup: () -> Unit = {},
) {
    val items by vm.items.collectAsState()
    val format by vm.format.collectAsState()
    var url by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<DownloadItem?>(null) }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var showBattDialog by remember { mutableStateOf(shouldPromptBattOpt(ctx)) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == YouTubeSignInActivity.RESULT_OK) {
            vm.onSignInComplete()
        }
    }

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onReRunSetup = {
                showSettings = false
                onResetSetup()
            },
        )
        return
    }

    val hasFinished = items.any { it.isTerminal }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "SnagLite",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Video URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = {
                IconButton(onClick = {
                    clipboard.getText()?.text?.let { url = it.trim() }
                }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                }
            },
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val entries = FormatChoice.entries
            entries.forEachIndexed { idx, choice ->
                SegmentedButton(
                    selected = format == choice,
                    onClick = { vm.setFormat(choice) },
                    shape = SegmentedButtonDefaults.itemShape(idx, entries.size),
                ) {
                    Text(if (choice == FormatChoice.Video) "Video" else "Audio")
                }
            }
        }
        Button(
            onClick = {
                vm.enqueue(url)
                url = ""
            },
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Download") }

        if (items.isEmpty()) {
            Text(
                "Paste a link, tap Download. Up to 3 downloads run at once; swipe to remove.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (hasFinished) {
                    TextButton(onClick = { vm.clearFinished() }) {
                        Text("Clear finished")
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    DownloadItemCard(
                        item = item,
                        onPause = { vm.pause(item.id) },
                        onResume = { vm.resume(item.id) },
                        onRequestRemove = { pendingRemoval = item },
                        onSignIn = {
                            vm.rememberPendingSignIn(item.id)
                            signInLauncher.launch(Intent(ctx, YouTubeSignInActivity::class.java))
                        },
                        onUpdateEngine = { vm.updateEngineAndRetry(item.id) },
                    )
                }
            }
        }
    }

    pendingRemoval?.let { item ->
        DeleteConfirmDialog(
            item = item,
            defaultDeleteFile = SnagLitePrefs.deleteFileOnRemove(ctx),
            onDismiss = { pendingRemoval = null },
            onConfirm = { deleteFile ->
                vm.remove(item.id, deleteFile)
                pendingRemoval = null
            },
        )
    }

    if (showBattDialog) {
        BatteryOptDialog(onDismiss = { showBattDialog = false })
    }
}
