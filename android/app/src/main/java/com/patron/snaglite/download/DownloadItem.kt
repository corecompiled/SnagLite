package com.patron.snaglite.download

import android.net.Uri

sealed interface DownloadStatus {
    data object Queued : DownloadStatus
    data object Preparing : DownloadStatus
    data class Running(val percent: Float, val line: String) : DownloadStatus
    data class Paused(val percent: Float, val line: String) : DownloadStatus
    data class Done(val publicUri: Uri, val displayName: String) : DownloadStatus
    data class Error(
        val message: String,
        val needsYouTubeSignIn: Boolean = false,
        val needsEngineUpdate: Boolean = false,
    ) : DownloadStatus
}

data class DownloadItem(
    val id: String,
    val url: String,
    val audioOnly: Boolean,
    val title: String,
    val status: DownloadStatus,
    val playlistIndex: Int? = null,
    val playlistTotal: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val thumbnailUrl: String? = null,
    val durationSec: Int? = null,
    val uploader: String? = null,
    val filesizeBytes: Long? = null,
) {
    val isTerminal: Boolean get() = status is DownloadStatus.Done || status is DownloadStatus.Error
    val isActive: Boolean
        get() = status is DownloadStatus.Preparing || status is DownloadStatus.Running
}
