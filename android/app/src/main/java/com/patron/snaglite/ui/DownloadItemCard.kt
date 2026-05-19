package com.patron.snaglite.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.patron.snaglite.download.DownloadItem
import com.patron.snaglite.download.DownloadStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadItemCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRequestRemove: () -> Unit,
    onSignIn: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onRequestRemove()
                false
            } else true
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeBackground() },
        content = { CardBody(item, onPause, onResume, onSignIn) },
    )
}

@Composable
private fun SwipeBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun CardBody(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSignIn: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ThumbnailBox(item)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (item.playlistIndex != null && item.playlistTotal != null) {
                    Text(
                        "Video ${item.playlistIndex} of ${item.playlistTotal}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                SecondaryLine(item)
                StatusBlock(item.status)
            }
            TrailingAction(item, onPause, onResume, onSignIn) { uri ->
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri.toString().toUri(), if (item.audioOnly) "audio/*" else "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailBox(item: DownloadItem) {
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (!item.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        ThumbnailOverlay(item.status)
    }
}

@Composable
private fun ThumbnailOverlay(status: DownloadStatus) {
    when (status) {
        is DownloadStatus.Preparing -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = Color.White,
                )
            }
        }
        is DownloadStatus.Running -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                if (status.percent > 0f) {
                    CircularProgressIndicator(
                        progress = { (status.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                    Text(
                        "${status.percent.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                    )
                }
            }
        }
        is DownloadStatus.Paused -> CornerBadge(Icons.Filled.Pause, MaterialTheme.colorScheme.onSurface)
        is DownloadStatus.Done -> CornerBadge(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary)
        is DownloadStatus.Error -> CornerBadge(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error)
        is DownloadStatus.Queued -> Unit
    }
}

@Composable
private fun CornerBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SecondaryLine(item: DownloadItem) {
    val parts = listOfNotNull(
        item.uploader?.takeIf { it.isNotBlank() },
        item.durationSec?.let(::formatDuration),
        item.filesizeBytes?.let(::formatBytes),
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun StatusBlock(status: DownloadStatus) {
    when (status) {
        is DownloadStatus.Running -> {
            LinearProgressIndicator(
                progress = { (status.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                strokeCap = StrokeCap.Round,
            )
            Text(
                status.line.ifBlank { "Working…" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        is DownloadStatus.Preparing -> StatusText("Preparing…", MaterialTheme.colorScheme.onSurfaceVariant)
        is DownloadStatus.Queued -> StatusText("Queued", MaterialTheme.colorScheme.onSurfaceVariant)
        is DownloadStatus.Paused -> {
            if (status.percent > 0f) {
                LinearProgressIndicator(
                    progress = { (status.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
            StatusText("Paused", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is DownloadStatus.Done -> StatusText("Saved to gallery", MaterialTheme.colorScheme.primary)
        is DownloadStatus.Error -> StatusText(status.message, MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TrailingAction(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSignIn: () -> Unit,
    onOpen: (android.net.Uri) -> Unit,
) {
    when (val s = item.status) {
        is DownloadStatus.Queued, is DownloadStatus.Preparing, is DownloadStatus.Running -> {
            FilledIconButton(
                onClick = onPause,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(Icons.Filled.Pause, contentDescription = "Pause")
            }
        }
        is DownloadStatus.Paused -> {
            FilledIconButton(onClick = onResume) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
            }
        }
        is DownloadStatus.Done -> {
            FilledIconButton(
                onClick = { onOpen(s.publicUri) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open")
            }
        }
        is DownloadStatus.Error -> {
            if (s.needsYouTubeSignIn) {
                FilledIconButton(onClick = onSignIn) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Sign in & retry")
                }
            } else {
                IconButton(onClick = onResume) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmDialog(
    item: DownloadItem,
    defaultDeleteFile: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (deleteFile: Boolean) -> Unit,
) {
    var deleteFile by remember { mutableStateOf(defaultDeleteFile) }
    val canOfferDelete = item.status is DownloadStatus.Done
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove download?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (canOfferDelete) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = deleteFile,
                            onCheckedChange = { deleteFile = it },
                        )
                        Text("Also delete file from device")
                    }
                } else {
                    Text(
                        "Cached working files will be cleaned up.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(canOfferDelete && deleteFile)
            }) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return if (idx == 0) "%d %s".format(bytes, units[idx])
    else "%.1f %s".format(value, units[idx])
}
