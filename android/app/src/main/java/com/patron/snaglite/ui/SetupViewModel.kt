package com.patron.snaglite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.patron.snaglite.SnagLiteApplication
import com.patron.snaglite.yt.YouTubeBootstrapper
import com.patron.snaglite.yt.YouTubePrefs
import com.patron.snaglite.yt.YouTubeUpdater
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SetupStepStatus { Pending, Running, Done, Failed }

data class SetupStep(
    val key: String,
    val label: String,
    val status: SetupStepStatus = SetupStepStatus.Pending,
    val detail: String? = null,
)

data class SetupUiState(
    val steps: List<SetupStep>,
    val finished: Boolean,
)

class SetupViewModel(private val app: Application) : AndroidViewModel(app) {

    private val initial = listOf(
        SetupStep("engines", "Installing download engine"),
        SetupStep("ytdlp", "Updating download engine"),
        SetupStep("youtube", "Preparing YouTube access"),
        SetupStep("notif", "Enabling notifications"),
    )

    private val _state = MutableStateFlow(SetupUiState(initial, finished = false))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun start(requestNotifPermission: suspend () -> Unit) {
        viewModelScope.launch {
            runStep("engines") {
                val sapp = app as SnagLiteApplication
                val ok = sapp.ensureEngines()
                if (!ok) error(sapp.initError ?: "engine init failed")
            }
            // ytdlp + visitor_data harvest are independent — yt-dlp update writes
            // to internal storage, harvest hits a hidden WebView on the main
            // thread. Run them concurrently to cut perceived setup latency.
            coroutineScope {
                val ytdlp = async {
                    runStep("ytdlp") {
                        YouTubeUpdater.updateNow(app)
                        // failure non-fatal: the bundled yt-dlp still works; mark Done
                    }
                }
                val youtube = async {
                    runStep("youtube") {
                        YouTubeBootstrapper.harvest(app)
                        // failure non-fatal: extractor args still work without visitor_data
                    }
                }
                awaitAll(ytdlp, youtube)
            }
            runStep("notif") {
                requestNotifPermission()
            }
            YouTubePrefs.setSetupComplete(app, true)
            _state.value = _state.value.copy(finished = true)
        }
    }

    private suspend fun runStep(key: String, block: suspend () -> Unit) {
        update(key) { it.copy(status = SetupStepStatus.Running) }
        try {
            block()
            update(key) { it.copy(status = SetupStepStatus.Done) }
        } catch (t: Throwable) {
            update(key) {
                it.copy(status = SetupStepStatus.Failed, detail = t.message)
            }
            // engines step is the only blocker; others continue
            if (key == "engines") throw t
        }
    }

    private fun update(key: String, fn: (SetupStep) -> SetupStep) {
        _state.value = _state.value.copy(
            steps = _state.value.steps.map { if (it.key == key) fn(it) else it },
        )
    }
}
