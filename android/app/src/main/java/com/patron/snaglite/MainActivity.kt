package com.patron.snaglite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patron.snaglite.ui.MainScreen
import com.patron.snaglite.ui.MainViewModel
import com.patron.snaglite.ui.SetupScreen
import com.patron.snaglite.ui.SetupViewModel
import com.patron.snaglite.ui.SnagLiteTheme
import com.patron.snaglite.ui.UpdateGate
import com.patron.snaglite.yt.YouTubePrefs
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : ComponentActivity() {

    private var pendingPermissionContinuation: ((Boolean) -> Unit)? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingPermissionContinuation?.invoke(granted)
        pendingPermissionContinuation = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() — it swaps the window from
        // Theme.SnagLite.Splash over to Theme.SnagLite.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Android 15+ with targetSdk 35+ forces this on regardless; calling it
        // explicitly makes API 26–34 behave the same way instead of drawing
        // inside opaque system bars.
        enableEdgeToEdge()

        setContent {
            SnagLiteTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val app = application as SnagLiteApplication
                    var setupDone by remember { mutableStateOf(YouTubePrefs.isSetupComplete(this)) }
                    if (!setupDone) {
                        val vm: SetupViewModel = viewModel()
                        SetupScreen(
                            vm = vm,
                            onDone = { setupDone = true },
                            requestNotifPermission = { requestNotificationPermission() },
                        )
                    } else {
                        val vm: MainViewModel = viewModel()
                        MainScreen(
                            vm = vm,
                            onResetSetup = {
                                YouTubePrefs.setSetupComplete(this, false)
                                setupDone = false
                            },
                        )
                        UpdateGate(app)
                    }
                }
            }
        }
    }

    private suspend fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true
        return suspendCoroutine { cont ->
            pendingPermissionContinuation = { ok -> cont.resume(ok) }
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
