# SnagLite — agent notes

.NET 10 Windows CLI (`snaglite.exe`) + Kotlin/Compose Android app. Both wrap `yt-dlp` + `ffmpeg` + `aria2c`. End-user docs in `README.md`. Previously named "Snag" — first-run migration code keeps old data dirs/files in scope.

## Layout

```
SnagLite/
├── SnagLite.slnx
├── README.md / CLAUDE.md / PRIVATE_NOTES.local.md (gitignored)
├── Directory.Build.props / Directory.Packages.props   # .NET repo-wide props + central package versions
├── .github/dependabot.yml                             # weekly nuget / gradle / actions updates
├── publish/                                 # dotnet publish output → snaglite.exe
├── src/SnagLite/                            # .NET 10 console (AssemblyName=snaglite, RootNamespace=SnagLite)
│   ├── SnagLite.csproj                      # net10.0, single-file self-contained
│   ├── Program.cs                           # Spectre.Console.Cli wiring
│   ├── Commands/                            # DownloadCommand, UpdateCommand, WhereCommand, ConfigCommands
│   ├── Services/                            # Paths, ToolResolver, YtDlpRunner, ProgressParser, Aria2cDownloader, CurlClient
│   ├── Services/Resolvers/                  # IResolver + ResolverA/B/C + IframeUnwrapper + ResolverPipeline (see PRIVATE_NOTES.local.md)
│   ├── Config/AppConfig.cs                  # JSON config at %APPDATA%\SnagLite\config.json
│   └── hosts.local.txt                      # gitignored, embedded as resource if present
└── android/                                 # Kotlin/Compose APK, applicationId com.patron.snaglite
    ├── app/src/main/
    │   ├── AndroidManifest.xml              # FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, ACCESS_WIFI_STATE, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    │   ├── assets/hosts.local.txt           # gitignored, loaded by IframeUnwrapper.init
    │   ├── res/xml/                         # backup_rules.xml + data_extraction_rules.xml (cookie-jar exclusions)
    │   └── java/com/patron/snaglite/
    │       ├── SnagLiteApplication.kt       # channel "snaglite_downloads", media migration on first run
    │       ├── MainActivity.kt
    │       ├── download/                    # DownloadController, Downloader (yt-dlp path), DirectDownloader (resolver path),
    │       │                                # MediaSink (publishes to Movies/SnagLite or Music/SnagLite via MediaStore),
    │       │                                # MetadataFetcher, SnagLitePrefs, DownloadItem, resolvers/,
    │       │                                # QueueStore (queue mirrored to filesDir/queue.json)
    │       ├── ui/                          # Compose: SnagLiteScaffold (the one Scaffold), MainScreen, SettingsScreen,
    │       │                                # SetupScreen, DownloadItemCard, BatteryOptDialog, Theme (SnagLiteTheme), UpdateGate
    │       ├── yt/                          # YouTubeHost, YouTubeArgsInjector, YouTubeBootstrapper (WebView visitor_data harvest),
    │       │                                # YouTubePlaylist, YouTubePrefs ("snaglite_yt"), YouTubeUpdater, EngineUpdateChecker, CookieStore
    │       ├── service/DownloadQueueWorker.kt # WorkManager worker: owns the dataSync FGS +
    │       │                                # aggregate notification, waits for the queue to drain
    │       └── webview/YouTubeSignInActivity.kt
    └── gradle/libs.versions.toml            # version catalog — every Android dependency + plugin version
```

## Build / run

```bash
dotnet publish src/SnagLite/SnagLite.csproj -c Release -r win-x64 --self-contained -o publish   # → publish/snaglite.exe (~42.6 MB)
cd android && .\gradlew.bat :app:assembleRelease                                                  # → app-{arm64-v8a,armeabi-v7a,x86_64,universal}-release.apk
```

## Toolchain versions

All Android versions live in `android/gradle/libs.versions.toml` — never hardcode one in a `build.gradle.kts`.

| | Version | Notes |
|---|---|---|
| AGP | 8.13.2 | Max supported API is 36.1 |
| Gradle | 8.13 | AGP 8.13.2's minimum and default |
| Kotlin | 2.3.20 | K2. Compose compiler is `org.jetbrains.kotlin.plugin.compose`, versioned *as* Kotlin — there is no `composeOptions` block |
| compileSdk / targetSdk | 36 | Play requires 36 for new apps and updates as of 2026-08-31 |
| minSdk | 26 | Do not raise |
| JDK | 17 | |
| Compose BOM | 2026.06.01 | Material3 1.4.0 |
| WorkManager | 2.10.5 | Held on the 2.10 line to match the `lifecycle` pin — the 2.11 androidx generation is the one that wants compileSdk 37 |
| .NET | net10.0 | Current LTS |

**AGP 9.x is deliberately not adopted.** It removes the `org.jetbrains.kotlin.android` plugin in favour of built-in Kotlin *and* flips `android.r8.strictFullModeForKeepRules` to `true`, under which `-keep class A` stops implying A's default constructor. Given this project's history of an R8 launch crash from exactly that (commons-compress `AsiExtraField`), those two migrations must not land together. See the R8 notes below.

**Three libraries are deliberately pinned below their newest release** — do not bump them without solving the blocker first:
- `androidx.core:core-ktx` **1.17.0** — 1.19.0 requires compileSdk 37 + AGP 9.1; 1.18.0 requires compileSdk 36.1.
- `androidx.lifecycle` **2.10.0** — 2.11.0 requires compileSdk 37 + AGP 9.2.
- `io.coil-kt.coil3` **3.4.0** — 3.5.0 is built with Kotlin 2.4.0, whose stdlib metadata a 2.3.20 compiler cannot read. Observed during this branch's Kotlin 2.2.20 → 2.3.20 migration: the symptom is a flood of bogus "unresolved reference" errors on ordinary stdlib calls in files that never touch Coil.

**Kotlin 2.3.20 makes `kotlinOptions { jvmTarget = "17" }` a hard error, not a deprecation warning.** The replacement is a top-level block in `android/app/build.gradle.kts`: `kotlin { compilerOptions { jvmTarget = JvmTarget.fromTarget("17") } }`.

## CI

Three GitHub Actions workflows live under `.github/workflows/`:

| Workflow | Trigger | Output |
|---|---|---|
| `dotnet-cli.yml` | push / PR to `src/**` | `snaglite.exe` as 90-day Actions artifact |
| `android-debug.yml` | push / PR to `android/**` | 4 debug APKs as 90-day Actions artifact |
| `release.yml` | **manual only** (`workflow_dispatch`) | `snaglite.exe` + 4 release-signed APKs attached to a new GitHub Release. Inputs: `tag` (required, e.g. `v0.1.0`), `prerelease` (bool, default false), `notes` (Markdown; auto-generated from commits if blank). Asset filenames are rewritten to include the tag (`snaglite-<tag>-win-x64.exe`, `snaglite-<tag>-<abi>-release.apk`). User-facing runbook lives in the README **Cutting a release** section. |

Release signing is wired into `release.yml`: four GitHub Secrets — `SNAGLITE_KEYSTORE_B64` (base64 of the `.jks` file), `SNAGLITE_KEYSTORE_PASS`, `SNAGLITE_KEY_ALIAS`, `SNAGLITE_KEY_PASS` — drive the build. The "Decode release keystore" step writes the `.jks` to `$RUNNER_TEMP/snaglite-release.jks` and exports `SNAGLITE_KEYSTORE_PATH` via `$GITHUB_ENV`, so the subsequent `./gradlew :app:assembleRelease` invocation picks up all four env vars the `signingConfigs.release` block in `android/app/build.gradle.kts` expects. Missing `SNAGLITE_KEYSTORE_B64` is fail-fast — workflow halts rather than silently shipping debug-signed APKs. Keystore generation recipe is in `PRIVATE_NOTES.local.md`.

## Runtime data

| Platform | Binaries | Config / prefs | Default output |
|---|---|---|---|
| CLI | `%LOCALAPPDATA%\SnagLite\bin\` (auto-installed yt-dlp / ffmpeg / aria2c) | `%APPDATA%\SnagLite\config.json` | `%USERPROFILE%\Videos\SnagLite\` |
| Android | extracted from APK assets to internal storage | `SharedPreferences("snaglite_yt")` + `SharedPreferences("snaglite_app")` | `Movies/SnagLite` (video) / `Music/SnagLite` (audio) |

`filesDir/yt-cookies.txt`, the WebView cookie database under `app_webview/`, and the `snaglite_yt` preference file are excluded from Android Auto Backup and from device-to-device transfer via `res/xml/backup_rules.xml` (API ≤ 30) and `res/xml/data_extraction_rules.xml` (API 31+). Both files must be kept in sync — a cookie surface added to one and not the other leaks on half the supported API range.

First-run migration is automatic on both platforms:
- CLI: `Paths.MigrateLegacyDataDirs()` (called from `Program.cs`) moves `Snagger` → `Snag` → `SnagLite` for `%LOCALAPPDATA%`, `%APPDATA%`, and `%USERPROFILE%\Videos`. Idempotent — skips when target exists.
- Android: `MediaSink.migrateLegacyPublishedFiles()` (called from `SnagLiteApplication.onCreate`, gated by `SnagLitePrefs.K_MEDIA_MIGRATED`) re-writes MediaStore `RELATIVE_PATH` for `Movies/Snag/` → `Movies/SnagLite/` and `Music/Snag/` → `Music/SnagLite/`. Best-effort; rows owned by the old `com.patron.snag` install may not update (harmless).

## Key design choices

- **Shell out to yt-dlp, don't reimplement.** ytdlnis itself shells out; ~1800 extractors ship daily.
- **aria2c default.** `-x16 -s16 -k1M` gives 3–8× speedup over single-connection HTTP.
- **One Spectre `Progress` task per URL.** Multi-stage state (download → merge → extract) mutates the task description rather than spawning bars. yt-dlp uses `--progress-template` with a `PROG|` prefix so `ProgressParser` skips other newline output.
- **Generic-extractor retry.** On `Unsupported URL`, `DownloadCommand` re-runs once with `--force-generic-extractor` + inferred `Referer`. A short pre-known list of wrapper-host iframes is unwrapped upfront via `IframeUnwrapper`; the list is loaded at process start from gitignored `hosts.local.txt` (CLI: embedded resource; Android: APK asset). Missing file → empty list → feature silently disabled, normal extractor + generic fallback still handle everything.
- **Android background reliability.** `DownloadController.ensureLocks()` acquires a `PARTIAL_WAKE_LOCK` (2-hour cap) + `WIFI_MODE_FULL_HIGH_PERF` `WifiLock` while any item is active; both released by `releaseLocks()` on drain. `BatteryOptDialog` on first launch (gated by `SnagLitePrefs.battOptDontAsk`) fires `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to defeat doze on Android 6+. Foreground service type is `dataSync`.
- **Thumbnail prefetch.** `MetadataFetcher` runs `yt-dlp --dump-json --skip-download --no-playlist --no-warnings`; parses `title`, best `thumbnail`, `duration`, `uploader`/`channel`, `filesize`/`filesize_approx`. `DownloadController.enqueue` fires this on `applicationScope` as fire-and-forget — download isn't blocked. `DownloadItemCard` renders via Coil 3 `AsyncImage` (`coil-network-okhttp`; version lives in `libs.versions.toml`), falls back to a `PlayCircle` icon. Translucent scrim + `CircularProgressIndicator` overlays while running.
- **One `Scaffold`, in `SnagLiteScaffold.kt`.** Every screen renders through it. It owns the `TopAppBar`, the `SnackbarHost`, and `contentWindowInsets = WindowInsets.safeDrawing`, and hands its `PaddingValues` to the screen — which must apply them. Do not add a second `Scaffold` and do not apply inset padding anywhere else; `targetSdk 36` makes edge-to-edge mandatory, and having one inset owner is what keeps that a configuration detail rather than a per-screen audit. User feedback goes through the snackbar host, never `Toast`.
- **DayNight window theme + splash screen.** `MainActivity` calls `installSplashScreen()` before `super.onCreate()`, then `enableEdgeToEdge()`. `values/themes.xml` (API 26–28) and `values-night/` cover pre-29 dark mode; `values-v29/themes.xml` parents on `android:Theme.DeviceDefault.DayNight` so the platform theme itself follows system dark mode. Note: the framework has no `android:Theme.DeviceDefault.DayNight.NoActionBar` at any API level — `values-v29/themes.xml` reproduces `NoActionBar` manually with `windowActionBar=false` + `windowNoTitle=true`. The window background must track dark mode via `@color/snaglite_window_background` or a cold launch flashes the wrong color before Compose paints.

## Android release signing

`signingConfigs.release` reads four env vars: `SNAGLITE_KEYSTORE_PATH/_PASS/_ALIAS`, `SNAGLITE_KEY_PASS`. All four set + keystore exists → signed `app-{arm64-v8a,armeabi-v7a,x86_64,universal}-release.apk`. Otherwise Gradle prints one-line warning and falls back to local debug keystore so OSS clones still build. `proguard-rules.pro` keeps `com.yausername.**`, `io.github.junkfood02.**`, the commons-compress `AsiExtraField` + `ExtraFieldUtils` reflection surface, Compose runtime/ui, kotlinx.coroutines, Coil 3, OkHttp, and `com.patron.snaglite.**`. See `PRIVATE_NOTES.local.md` for `keytool` recipe + PowerShell env-var snippet.

R8 + resource shrinker are **enabled** (`isMinifyEnabled = true` + `isShrinkResources = true`). A prior launch-time crash on Android 14 traced to commons-compress reflective loading being stripped; the `AsiExtraField` keep rule (plus the broader keep set above) is what unblocked re-enabling. If a future launch regression surfaces under R8, the `proguardFiles(...)` line and the keep block are the things to widen first.

## Android diagnostics

A `Thread.setDefaultUncaughtExceptionHandler` is installed as the very first line of `SnagLiteApplication.onCreate` (before `super.onCreate()`), and writes a full stack trace to `getExternalFilesDir(null)/last_crash.txt`. The file is reachable without `adb` at `Android/data/com.patron.snaglite/files/last_crash.txt` via any file manager. If the new APK still crashes on launch, the file is the next thing to look at.

## Spectre.Console.Cli 0.55+ gotcha

`Execute`/`ExecuteAsync` overrides changed in 0.55: now require `CancellationToken` and are `protected`. All command classes use `protected override … (CommandContext, [Settings,] CancellationToken)`.

## Testing

No automated tests. Manual matrix (concrete URLs in `PRIVATE_NOTES.local.md`):
1. `snaglite where` — triggers tool fetch.
2. yt-dlp-native URL — native extractor path.
3. Configured wrapper-host URL — iframe unwrap → resolver pipeline.
4. Unknown-host URL — generic extractor fallback.
5. `--audio-only`, `-o`, `snaglite update`.
6. Build with `hosts.local.txt` absent — wrapper-host URL falls through to yt-dlp's generic extractor cleanly (no crash, no unwrap).
7. Android: install on top of a pre-existing `com.patron.snag` install — confirm `Movies/Snag/*` migrates to `Movies/SnagLite/` on first launch (best-effort).
8. Rotate the device mid-download — the list, its progress, and in-flight setup steps must survive.
9. Back gesture from Settings returns to the list; back again exits.
10. Cold launch in dark mode shows no white flash; cold launch in light mode shows no dark flash.
11. Edge-to-edge: app bar background reaches the top edge but its title clears the status bar; the Download button clears the navigation bar. Check in both gesture and 3-button navigation.

Items 8–11 were added by the 2026 toolchain modernization branch. All four were **run on hardware on 2026-08-03** (TECNO KM7k, Android 15 / API 35, arm64-v8a) against the release APK — R8 and the resource shrinker included — and all four passed, along with the release smoke test, the setup flow, the Snackbar migration, RTL, and an end-to-end download. Three checks remain unverified and are **not** claimed:

- **API 26–28 base theme** (`values/themes.xml`) and the parked API 26–28 night-mode sign-in header. Invisible on API 29+, which resolves `values-v29/themes.xml` instead. Needs an API 26–28 emulator image.
- **The `dataSync` timeout path** (Android 15's 6h/24h quota). No `adb` command spends the quota; it can only be observed on a genuinely long session. Now handled by WorkManager rather than the old `DownloadService.onTimeout`.
- **Predictive-back cancel** (start the gesture, hold, slide back, release below the commit threshold). `adb shell input swipe` only produces linear gestures, so this needs a human thumb.

The run turned up one real defect, since fixed: `YouTubeSignInActivity` did not call `enableEdgeToEdge()`, so it kept the platform default system-bar icon appearance and, in **light mode**, drew near-white status-bar icons (and near-white three-button nav-bar icons) on its near-white background. Content insets were never the problem. The activity now makes the same `enableEdgeToEdge()` call `MainActivity` does; re-verified on device across light/dark × gesture/three-button. Every View-based screen added here needs that call — the Compose screens get it from `MainActivity`.

Reaching that activity for a test is awkward: it is `exported="false"` and `am start` is refused even against a debug build. Flip `exported="true"`, build a throwaway debug APK, verify, then revert and confirm the tree is clean.

## Deliberately out of scope

- Cookies / login flows (no creds, per requirement) — except the YouTube WebView sign-in fallback on Android.
- Playlists on CLI (Android supports YouTube playlists via `YouTubePlaylist.enumerate`).
- Batch files, subtitles, cross-platform CLI.
- AGP 9.x / built-in Kotlin (see Toolchain versions for why).
- ~~WorkManager migration for the download queue~~ — **done**, in two phases. `QueueStore` mirrors the queue to `filesDir/queue.json` on every state change and `DownloadController` restores it synchronously in its constructor, so the list survives process death. Restored in-flight items come back as `Queued` — never `Running`, since no process is behind them — and resume through yt-dlp's `--continue` against the work directory, which is why `workDirFor` had to move off `cacheDir`. Progress percentages are deliberately not persisted. **`Paused` is the one non-terminal state that survives restore as itself**: every other one describes a process that is gone, while `Paused` describes a decision the user made, and silently resuming a download someone deliberately stopped is worst exactly when it matters most — on metered data, which is usually why they stopped it. `DownloadQueueWorker` then replaced `DownloadService`: one worker for the whole queue, holding the `dataSync` FGS and the aggregate notification and waiting for the drain. It does **not** run downloads — those stay as capped coroutines on the application scope, so `MAX_ACTIVE = 3` needs no reimplementation on a WorkManager that has no per-tag parallelism cap, and progress still flows through the existing `StateFlow` with no UI changes.

  **Measured limits — do not promise more than this.** Verified on hardware 2026-08-04:
  - **Reboot, after the user unlocks: resumes unattended.** The process restarts on its own with the launcher in the foreground; the app is never opened.
  - **Reboot, before unlock: cannot resume, ever.** The device sits in Direct Boot (`RUNNING_LOCKED`) and `/data/user/0/…` is credential-encrypted — that holds `queue.json`, the partials *and WorkManager's own job database*, so there is nothing to reschedule from. Fixing this would mean moving download metadata to device-encrypted storage, outside the user's credential protection. Deliberately not done.
  - **`am force-stop`: cannot resume until the app is opened.** It sets `stopped=true` on the package, which blocks every component including WorkManager's jobs. Jobs stay registered; they are simply not allowed to run.

  The worker is enqueued **expedited, with `RUN_AS_NON_EXPEDITED_WORK_REQUEST` as the fallback**. Expedited is what makes the foreground-service start from the background legal — the thing `ForegroundServiceStartNotAllowedException` blocked when `pumpQueue` tried it directly — but the quota is finite per 24 hours, so it is a request and not a guarantee. The fallback is deliberate: a drain that starts late is an inconvenience, one that is dropped is a queue the user has to notice and restart by hand. If the initial `setForeground` fails anyway, `doWork` still calls `pumpNow()` and only *then* returns `Result.retry()`. The pump order matters and is not incidental: gating it on a successful foreground start would mean a device that persistently refuses a background foreground-start never drains its queue at all, which is a regression against the old service — that one pumped regardless and merely logged. A queue draining unprotected is worse than draining protected, and far better than not draining. Mid-stream progress refreshes still swallow their failures; one dropped notification tick is not worth a retry.

  Note the division of labour: `DownloadQueueWorker` owns the notification, `DownloadController.refreshServiceLifecycle` owns only the wake lock / Wi-Fi lock and the guarantee that a worker exists. The controller does **not** build notifications — it did briefly, and the leftover code outlived its callers.

  Still open: `DownloadService.onTimeout`'s replacement path. The Android 15 6h/24h `dataSync` quota applies to a WorkManager foreground worker exactly as it did to the old service — the difference is WorkManager reschedules afterwards rather than dropping the work.
- Adaptive / large-screen layouts. The list is single-pane at every window size.
- Automated tests of any kind.

---

## How the user wants to collaborate

These are explicit preferences captured from prior sessions — apply them by default in future ones.

- **95% confidence rule.** Before starting any non-trivial change, you need to be ~95% confident in the approach. If you're not, **ask clarifying questions first** — don't dive in and hope. A one-round question is always cheaper than a wrong implementation.
- **Ask, don't assume.** When scope is ambiguous (which project, which file, which UX choice), ask. The user prefers being interrupted with a good question over silently choosing wrong.
- **Terseness.** Drop filler / pleasantries / hedging in chat replies. Fragments are fine. Code and commit messages stay normal English.
- **Verify end-to-end after changes.** Run tests, run the build, exercise the CLI / UI manually. Don't claim "done" without it. If you can't verify (no device, no network), say so explicitly.
- **Confirm before destructive or shared-state actions.** Deletes, force-pushes, anything that touches GitHub, anything that affects shared infra. Local file edits, builds, and tests do not need confirmation.
- **Update `README.md` as part of any feature change**, not as a follow-up commit.
- **Plan mode is the default for non-trivial work.** Present a plan in `C:\Users\Patron\.claude\plans\<slug>.md` and wait for explicit approval before editing the codebase. After approval, work the plan top-to-bottom and track progress with TodoWrite.
- **No secrets, no host names, no anything sensitive in committed files.** When in doubt, put it in a `*.local.md` (gitignored) and reference it from `CLAUDE.md`.
