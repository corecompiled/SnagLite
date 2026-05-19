# SnagLite — agent notes

.NET 10 Windows CLI (`snaglite.exe`) + Kotlin/Compose Android app. Both wrap `yt-dlp` + `ffmpeg` + `aria2c`. End-user docs in `README.md`. Previously named "Snag" — first-run migration code keeps old data dirs/files in scope.

## Layout

```
SnagLite/
├── SnagLite.slnx
├── README.md / CLAUDE.md / PRIVATE_NOTES.local.md (gitignored)
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
    └── app/src/main/
        ├── AndroidManifest.xml              # FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, ACCESS_WIFI_STATE, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        ├── assets/hosts.local.txt           # gitignored, loaded by IframeUnwrapper.init
        └── java/com/patron/snaglite/
            ├── SnagLiteApplication.kt       # channel "snaglite_downloads", media migration on first run
            ├── MainActivity.kt
            ├── download/                    # DownloadController, Downloader (yt-dlp path), DirectDownloader (resolver path),
            │                                # MediaSink (publishes to Movies/SnagLite or Music/SnagLite via MediaStore),
            │                                # MetadataFetcher, SnagLitePrefs, DownloadItem, resolvers/
            ├── ui/                          # Compose: MainScreen, SettingsScreen, SetupScreen, DownloadItemCard, BatteryOptDialog, Theme (SnagLiteTheme), UpdateGate
            ├── yt/                          # YouTubeHost, YouTubeArgsInjector, YouTubeBootstrapper (WebView visitor_data harvest),
            │                                # YouTubePlaylist, YouTubePrefs ("snaglite_yt"), YouTubeUpdater, EngineUpdateChecker, CookieStore
            ├── service/DownloadService.kt   # foreground service, dataSync type
            └── webview/YouTubeSignInActivity.kt
```

## Build / run

```bash
dotnet publish src/SnagLite/SnagLite.csproj -c Release -r win-x64 --self-contained -o publish   # → publish/snaglite.exe (~37 MB)
cd android && .\gradlew.bat :app:assembleRelease                                                  # → app-{arm64-v8a,armeabi-v7a,x86_64,universal}-release.apk
```

## Runtime data

| Platform | Binaries | Config / prefs | Default output |
|---|---|---|---|
| CLI | `%LOCALAPPDATA%\SnagLite\bin\` (auto-installed yt-dlp / ffmpeg / aria2c) | `%APPDATA%\SnagLite\config.json` | `%USERPROFILE%\Videos\SnagLite\` |
| Android | extracted from APK assets to internal storage | `SharedPreferences("snaglite_yt")` + `SharedPreferences("snaglite_app")` | `Movies/SnagLite` (video) / `Music/SnagLite` (audio) |

First-run migration is automatic on both platforms:
- CLI: `Paths.MigrateLegacyDataDirs()` (called from `Program.cs`) moves `Snagger` → `Snag` → `SnagLite` for `%LOCALAPPDATA%`, `%APPDATA%`, and `%USERPROFILE%\Videos`. Idempotent — skips when target exists.
- Android: `MediaSink.migrateLegacyPublishedFiles()` (called from `SnagLiteApplication.onCreate`, gated by `SnagLitePrefs.K_MEDIA_MIGRATED`) re-writes MediaStore `RELATIVE_PATH` for `Movies/Snag/` → `Movies/SnagLite/` and `Music/Snag/` → `Music/SnagLite/`. Best-effort; rows owned by the old `com.patron.snag` install may not update (harmless).

## Key design choices

- **Shell out to yt-dlp, don't reimplement.** ytdlnis itself shells out; ~1800 extractors ship daily.
- **aria2c default.** `-x16 -s16 -k1M` gives 3–8× speedup over single-connection HTTP.
- **One Spectre `Progress` task per URL.** Multi-stage state (download → merge → extract) mutates the task description rather than spawning bars. yt-dlp uses `--progress-template` with a `PROG|` prefix so `ProgressParser` skips other newline output.
- **Generic-extractor retry.** On `Unsupported URL`, `DownloadCommand` re-runs once with `--force-generic-extractor` + inferred `Referer`. A short pre-known list of wrapper-host iframes is unwrapped upfront via `IframeUnwrapper`; the list is loaded at process start from gitignored `hosts.local.txt` (CLI: embedded resource; Android: APK asset). Missing file → empty list → feature silently disabled, normal extractor + generic fallback still handle everything.
- **Android background reliability.** `DownloadController.ensureLocks()` acquires a `PARTIAL_WAKE_LOCK` (2-hour cap) + `WIFI_MODE_FULL_HIGH_PERF` `WifiLock` while any item is active; both released by `releaseLocks()` on drain. `BatteryOptDialog` on first launch (gated by `SnagLitePrefs.battOptDontAsk`) fires `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to defeat doze on Android 6+. Foreground service type is `dataSync`.
- **Thumbnail prefetch.** `MetadataFetcher` runs `yt-dlp --dump-json --skip-download --no-playlist --no-warnings`; parses `title`, best `thumbnail`, `duration`, `uploader`/`channel`, `filesize`/`filesize_approx`. `DownloadController.enqueue` fires this on `applicationScope` as fire-and-forget — download isn't blocked. `DownloadItemCard` renders via Coil 3 `AsyncImage` (`coil-network-okhttp:3.0.4`), falls back to a `PlayCircle` icon. Translucent scrim + `CircularProgressIndicator` overlays while running.

## Android release signing

`signingConfigs.release` reads four env vars: `SNAGLITE_KEYSTORE_PATH/_PASS/_ALIAS`, `SNAGLITE_KEY_PASS`. All four set + keystore exists → signed `app-{arm64-v8a,armeabi-v7a,x86_64,universal}-release.apk`. Otherwise Gradle prints one-line warning and falls back to local debug keystore so OSS clones still build. `proguard-rules.pro` already keeps `com.yausername.**` and `io.github.junkfood02.**`; Coil 3 + OkHttp 4 ship consumer rules. See `PRIVATE_NOTES.local.md` for `keytool` recipe + PowerShell env-var snippet.

> **R8 currently disabled.** Release has `isMinifyEnabled = false` + `isShrinkResources = false` while diagnosing a launch-time crash on Android 14. Re-enable both alongside additional keep rules (Coil 3, Compose runtime reflection, kotlinx.coroutines, OkHttp) once the baseline is confirmed good. The `proguardFiles(...)` line is kept in place so flipping the flags back is a one-line revert.

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

## Deliberately out of scope

- Cookies / login flows (no creds, per requirement) — except the YouTube WebView sign-in fallback on Android.
- Playlists on CLI (Android supports YouTube playlists via `YouTubePlaylist.enumerate`).
- Batch files, subtitles, cross-platform CLI.

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
