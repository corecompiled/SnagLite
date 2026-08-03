package com.patron.snaglite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.patron.snaglite.SnagLiteApplication
import com.patron.snaglite.download.DownloadItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FormatChoice { Video, Audio }

/**
 * Thin wrapper over `DownloadController` (which owns the applicationScope coroutines).
 * State survives Activity destruction; ViewModel just exposes shared StateFlows.
 */
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val sapp = app as SnagLiteApplication

    init {
        // Anything restored from disk is Queued with no process behind it. Starting
        // it here rather than in the controller's constructor is deliberate: this
        // ViewModel is built by the UI, so the app is in the foreground and the
        // foreground-service start that pumpQueue performs is permitted.
        sapp.downloads.resumeRestored()
    }

    val items: StateFlow<List<DownloadItem>> = sapp.downloads.items

    private val _format = MutableStateFlow(FormatChoice.Video)
    val format: StateFlow<FormatChoice> = _format.asStateFlow()

    fun setFormat(f: FormatChoice) {
        _format.value = f
    }

    fun enqueue(url: String) {
        val audioOnly = _format.value == FormatChoice.Audio
        sapp.downloads.enqueue(url, audioOnly)
    }

    fun pause(id: String) = sapp.downloads.pause(id)
    fun resume(id: String) = sapp.downloads.resume(id)
    fun remove(id: String, deleteFile: Boolean) = sapp.downloads.remove(id, deleteFile)
    fun clearFinished() = sapp.downloads.clearFinished()

    fun rememberPendingSignIn(id: String) = sapp.downloads.rememberPendingSignIn(id)
    fun onSignInComplete() = sapp.downloads.retryAfterSignIn()
    fun updateEngineAndRetry(id: String) = sapp.downloads.updateEngineAndRetry(id)
}
