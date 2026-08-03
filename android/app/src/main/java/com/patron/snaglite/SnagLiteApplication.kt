package com.patron.snaglite

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.patron.snaglite.download.DownloadController
import com.patron.snaglite.download.MediaSink
import com.patron.snaglite.download.SnagLitePrefs
import com.patron.snaglite.download.resolvers.IframeUnwrapper
import com.patron.snaglite.ui.ThemeMode
import com.patron.snaglite.ui.themeModeFromPref
import com.patron.snaglite.ui.toPref
import com.patron.snaglite.yt.EngineUpdateChecker
import com.patron.snaglite.yt.YouTubePrefs
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SnagLiteApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var enginesReady: Boolean = false
        private set

    @Volatile
    var initError: String? = null
        private set

    val downloads: DownloadController by lazy {
        DownloadController(this, applicationScope)
    }

    /**
     * Held here rather than in a ViewModel because the theme wraps the whole
     * activity, above where any ViewModel is scoped, and the setting has to take
     * effect the moment it is changed rather than on next launch.
     */
    // Seeded in onCreate, NOT here. A field initializer runs in the Application
    // constructor, before attachBaseContext, so touching SharedPreferences at this
    // point dereferences a null base context and kills the process at the splash
    // screen — before installCrashHandler() has run, so it leaves no crash dump.
    private val _themeMode = MutableStateFlow(ThemeMode.System)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        SnagLitePrefs.setThemeMode(this, mode.toPref())
        _themeMode.value = mode
    }

    private val engineMutex = Mutex()

    override fun onCreate() {
        installCrashHandler()
        super.onCreate()
        _themeMode.value = themeModeFromPref(SnagLitePrefs.themeMode(this))
        createNotificationChannel()
        // Load the gitignored wrapper-host list from assets (empty if missing).
        IframeUnwrapper.init(this)
        // One-shot rename of legacy Movies/Snag + Music/Snag MediaStore rows.
        if (!SnagLitePrefs.mediaMigrated(this)) {
            applicationScope.launch {
                runCatching { MediaSink.migrateLegacyPublishedFiles(this@SnagLiteApplication) }
                SnagLitePrefs.setMediaMigrated(this@SnagLiteApplication, true)
            }
        }
        // Eager engine init only when setup is already complete; otherwise SetupScreen drives it.
        // After engines are ready, fire a silent yt-dlp update check (wifi-only,
        // 24 h-debounced) so the binary stays current across cold launches without
        // ever surfacing UI.
        if (YouTubePrefs.isSetupComplete(this)) {
            applicationScope.launch {
                ensureEngines()
                if (enginesReady) {
                    EngineUpdateChecker.runSilentUpdateIfDue(this@SnagLiteApplication)
                }
            }
        }
    }

    /**
     * Idempotent: extracts bundled native binaries into internal storage.
     * Safe to call from multiple coroutines — guarded by mutex.
     */
    suspend fun ensureEngines(): Boolean = engineMutex.withLock {
        if (enginesReady) return@withLock true
        withContext(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(this@SnagLiteApplication)
                FFmpeg.getInstance().init(this@SnagLiteApplication)
                Aria2c.getInstance().init(this@SnagLiteApplication)
                enginesReady = true
                Log.i(TAG, "Engines ready")
                true
            } catch (t: Throwable) {
                initError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "Engine init failed", t)
                false
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { dumpCrash(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
                ?: kotlin.system.exitProcess(2)
        }
    }

    private fun dumpCrash(thread: Thread, throwable: Throwable) {
        // getExternalFilesDir is null before super.onCreate; fall back to filesDir.
        val dir: File = (runCatching { getExternalFilesDir(null) }.getOrNull() ?: filesDir)
            ?: return
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val text = buildString {
            append("Time: ").append(stamp).append('\n')
            append("Thread: ").append(thread.name).append('\n')
            append("Build.FINGERPRINT: ").append(Build.FINGERPRINT).append('\n')
            append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n')
            append("App versionName: ").append(
                runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull(),
            ).append('\n')
            append("Throwable:\n").append(throwable.stackTraceToString())
        }
        File(dir, "last_crash.txt").writeText(text)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active video downloads"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "snaglite_downloads"
        private const val TAG = "SnagLiteApp"
    }
}
