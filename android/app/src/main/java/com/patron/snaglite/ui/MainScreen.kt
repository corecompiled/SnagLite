package com.patron.snaglite.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patron.snaglite.R
import com.patron.snaglite.download.DownloadController
import com.patron.snaglite.download.DownloadItem
import com.patron.snaglite.download.DownloadStatus
import com.patron.snaglite.download.SnagLitePrefs
import com.patron.snaglite.webview.YouTubeSignInActivity
import kotlinx.coroutines.launch

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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showBattDialog by remember { mutableStateOf(shouldPromptBattOpt(ctx)) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == YouTubeSignInActivity.RESULT_OK) {
            vm.onSignInComplete()
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }

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
    val hasQueued = items.any { it.status is DownloadStatus.Queued }
    val snackbarHostState = remember { SnackbarHostState() }

    SnagLiteScaffold(
        title = "SnagLite",
        snackbarHostState = snackbarHostState,
        actions = {
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.main_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.getClipEntry()
                                ?.clipData
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(ctx)
                                ?.toString()
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { url = it }
                        }
                    }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = stringResource(R.string.cd_paste),
                        )
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
                        Text(
                            stringResource(
                                if (choice == FormatChoice.Video) {
                                    R.string.main_format_video
                                } else {
                                    R.string.main_format_audio
                                },
                            ),
                        )
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
            ) { Text(stringResource(R.string.main_download)) }

            if (items.isEmpty()) {
                // Own Column so the heading sits tight against its body rather
                // than inheriting the parent's 12.dp control spacing, and so the
                // pair centres as one block instead of two separately-centred lines.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.main_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.main_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.main_downloads_header),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasFinished) {
                        TextButton(onClick = { vm.clearFinished() }) {
                            Text(stringResource(R.string.main_clear_finished))
                        }
                    }
                }
                if (hasQueued) {
                    Text(
                        pluralStringResource(
                            R.plurals.main_queue_note,
                            DownloadController.MAX_ACTIVE,
                            DownloadController.MAX_ACTIVE,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
