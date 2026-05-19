# SnagLite

> **Paste a URL. Get the file.** Highest available quality, fastest sane speed, zero setup.

[![.NET CLI build](https://github.com/corecompiled/SnagLite/actions/workflows/dotnet-cli.yml/badge.svg)](https://github.com/corecompiled/SnagLite/actions/workflows/dotnet-cli.yml)
[![Android debug build](https://github.com/corecompiled/SnagLite/actions/workflows/android-debug.yml/badge.svg)](https://github.com/corecompiled/SnagLite/actions/workflows/android-debug.yml)
![Windows](https://img.shields.io/badge/Windows-x64-0078D6)
![Android](https://img.shields.io/badge/Android-API%2026%2B-3DDC84)
![.NET 10](https://img.shields.io/badge/.NET-10-512BD4)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF)

SnagLite is a tiny video downloader that does the obvious thing well: you give it a link, it saves the best-quality file to your downloads folder. Under the hood it wraps the three best tools in the space — [`yt-dlp`](https://github.com/yt-dlp/yt-dlp), [`ffmpeg`](https://ffmpeg.org/), and [`aria2c`](https://aria2.github.io/) — and handles the parts that usually trip people up: finding the binaries, picking sane formats, gluing video and audio together, retrying when a site changes its mind. You shouldn't have to think about any of that, and with SnagLite you don't.

There are two builds in this repo:

- **`snaglite.exe`** — a single-file Windows CLI ([`src/SnagLite/`](src/SnagLite/)).
- **SnagLite Android** — a Kotlin + Jetpack Compose app ([`android/`](android/)) with a foreground download service, a download queue, pause/resume, and a one-time setup screen that bootstraps everything on first launch.

Both builds share the same philosophy: no accounts, no telemetry, no nags, no PATH wrangling. They support [the ~1800 sites yt-dlp supports](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md) out of the box, plus a small generic resolver pipeline that fills in for a few common iframe-wrapper hosts the bundled extractors don't recognise.

## Quick start

**Windows CLI** — grab `snaglite.exe` from a GitHub Actions artifact (or build it yourself), drop it on your `PATH`, and:

```powershell
snaglite https://www.youtube.com/watch?v=dQw4w9WgXcQ
```

First invocation auto-downloads `yt-dlp.exe`, `ffmpeg.exe`, and `aria2c.exe` to `%LOCALAPPDATA%\SnagLite\bin`. From then on it's instant.

**Android** — build the APK with `./gradlew :app:assembleDebug` (or pull one from CI), sideload, paste a URL into the input box, tap **Download**. The setup screen on first launch handles the rest. Files land in `Movies/SnagLite` or `Music/SnagLite` and show up in your gallery without any storage permission prompts on Android 10+.

## What makes it different

- **Genuinely zero-config.** No accounts. No tokens. No "configure your downloader" wizard. The first `snaglite <url>` works.
- **Fast by default.** `aria2c -x16 -s16 -k1M` pulls files in parallel chunks — usually a 3–8× speedup over single-connection HTTP.
- **Handles awkward sites.** When yt-dlp can't crack a host, SnagLite retries once with the generic extractor, and a small custom resolver pipeline handles a handful of common iframe-wrapper sites the bundled extractors miss.
- **Android background reliability.** Holds a partial wake lock + high-perf Wi-Fi lock for the duration of a download, and prompts to add itself to the battery-optimisation exemption list on first launch so long downloads survive doze mode.
- **Stays current.** `snaglite update` pulls the latest yt-dlp release; the Android app does the same automatically every week (yt-dlp ships extractor fixes daily).
- **No telemetry, no accounts, no nags.** Ever.

> Inspired by [ytdlnis](https://github.com/deniscerri/ytdlnis). Previously known as **Snag** — see [Migrating from Snag](#migrating-from-snag) below for the rename details.

---

## Reference

---

## SnagLite CLI (Windows)

### What it does

- Takes any URL, downloads the best video+audio merged into a single `.mp4`.
- Auto-installs `yt-dlp.exe`, `ffmpeg.exe`, `aria2c.exe` on first run — no manual setup, no PATH wrangling.
- Uses `aria2c` with 16 connections for fast parallel chunk downloading.
- Live progress bar in the terminal (percent, speed, ETA).
- No prompts, no auth, no tokens, no telemetry.

### Supported sites

- All [yt-dlp built-in extractors](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md) — `youtube.com` and ~1800 others.
- Sites without a dedicated extractor are auto-retried with yt-dlp's generic extractor (HTML scrape + HLS/DASH/iframe sniff).
- A few listing-page hosts that embed third-party players via `<iframe>` are auto-unwrapped before extraction so the underlying provider host is what reaches the resolver pipeline.

If a site's player obfuscates the stream URL beyond what the bundled extractors can find, yt-dlp's stderr is surfaced verbatim. Run `snaglite update` first — yt-dlp ships extractor fixes daily.

### Install

1. Grab `snaglite.exe` from `publish/` (after running the publish command below) or from a release.
2. Drop it anywhere on your `PATH` (e.g. `C:\Tools\snaglite.exe`).
3. First invocation auto-downloads helper binaries to `%LOCALAPPDATA%\SnagLite\bin`.

#### Build from source

```bash
dotnet publish src/SnagLite/SnagLite.csproj -c Release -r win-x64 --self-contained -o publish
```

Produces `publish/snaglite.exe` (single-file, .NET runtime embedded).

### Usage

```bash
snaglite <url>                           # download with all defaults
snaglite <url> -o D:\Downloads           # custom output dir for this download
snaglite <url> --audio-only              # extract audio only (m4a)
snaglite <url> -f "bv[height<=1080]+ba"  # custom format spec (yt-dlp syntax)
snaglite <url> --no-aria2                # use yt-dlp's native downloader instead
```

### Commands

| Command | Description |
|---|---|
| `snaglite <url>` | Download URL with defaults. Same as `snaglite get <url>`. |
| `snaglite get <url> [opts]` | Explicit form. |
| `snaglite update` | Re-pull latest `yt-dlp.exe`. |
| `snaglite where` | Show resolved tool paths and current output dir. |
| `snaglite config show` | Print saved config. |
| `snaglite config set-output <PATH>` | Persist a new default output directory. |

### Options (for `get` / default)

| Flag | Description |
|---|---|
| `-o, --output <PATH>` | One-shot output directory override. |
| `--audio-only` | Audio-only (m4a). |
| `-f, --format <SPEC>` | Custom yt-dlp format selector. |
| `--no-aria2` | Disable aria2c, fall back to yt-dlp native downloader. |

### Defaults

| Setting | Value |
|---|---|
| Output dir | `%USERPROFILE%\Videos\SnagLite` (override with `--output` or `snaglite config set-output`) |
| Format | `bv*+ba/b`, merged to `.mp4` |
| Filename | `<title> [<id>].mp4` (sanitized) |
| Downloader | `aria2c -x16 -s16 -k1M` |

### Paths

| Purpose | Location |
|---|---|
| Helper binaries | `%LOCALAPPDATA%\SnagLite\bin` |
| Config | `%APPDATA%\SnagLite\config.json` |
| Default downloads | `%USERPROFILE%\Videos\SnagLite` |

### Troubleshooting

- **`Unsupported URL`** — `snaglite update` to refresh yt-dlp, then retry. The CLI auto-retries once with the generic extractor on this error before giving up.
- **Geo-blocked** — yt-dlp's `--geo-bypass` is on by default; if a site requires actual VPN-level bypass it will still fail. Use a VPN.
- **Slow downloads** — confirm aria2c is in use via `snaglite where`. Pass `--no-aria2` only if a CDN throttles parallel connections.
- **Tool download fails** — corporate proxy, firewall, or GitHub rate limit. Manually drop `yt-dlp.exe`, `ffmpeg.exe`, and `aria2c.exe` into `%LOCALAPPDATA%\SnagLite\bin`.

### Out of scope (CLI)

- Cookies / login-gated content (no `--cookies-from-browser` passthrough yet).
- Playlists / batch input files.
- Subtitles.
- Cross-platform (win-x64 only).

---

## SnagLite Android

Kotlin + Jetpack Compose APK. Wraps the same `yt-dlp` + `ffmpeg` + `aria2c` engine via the [youtubedl-android](https://github.com/yausername/youtubedl-android) library, with native resolvers bolted on for embed hosts the generic extractor can't crack.

### What it does

- Paste any video URL, tap **Download** — saves a merged `.mp4` (or `.m4a` audio-only) to `Movies/SnagLite` (or `Music/SnagLite`) via scoped-storage MediaStore.
- **Multi-download queue** — up to 3 downloads run in parallel; extra submissions wait as `Queued` and are promoted automatically as slots free. Each download card shows a 16:9 thumbnail (fetched lazily from yt-dlp metadata) with an overlaid progress ring while running, plus the title, uploader, duration, file size, and a thin linear progress bar with the latest speed/ETA line. Swipe a card to remove it (with an optional delete-from-device checkbox).
- **Pause / resume** — tap the pause icon on any active download. yt-dlp picks up the `.part` file on resume; direct-host downloads from the custom resolver pipeline restart from byte 0 (no per-chunk progress is persisted).
- **One-time setup screen** on first launch:
  1. Extracts `yt-dlp` / `ffmpeg` / `aria2c` from APK assets to internal storage.
  2. Auto-updates `yt-dlp` to latest stable.
  3. Primes a YouTube session in a hidden WebView (harvests `visitor_data` + cookies).
  4. Requests `POST_NOTIFICATIONS` permission.
- Subsequent launches: instant. Background re-check of `yt-dlp` weekly.
- Live progress bar on the download card (percent + last yt-dlp/aria2c stdout line).
- Foreground service keeps long downloads alive when the app is backgrounded.
- **Background reliability** — SnagLite holds a `PARTIAL_WAKE_LOCK` so the CPU stays awake while downloads run, and a `WIFI_MODE_FULL_HIGH_PERF` `WifiLock` so the Wi-Fi radio doesn't sleep mid-stream when the screen is off. On first launch the app prompts to add itself to the battery-optimization exemption list — this prevents doze-mode throttling on Android 6+ and is what keeps a long download going while the phone is locked.
- No telemetry. No accounts.

### Supported sites

- All yt-dlp built-in extractors (~1800 sites) — same list as the CLI.
- **Native resolvers** — a small pipeline of generic resolvers bypasses yt-dlp entirely for a handful of common iframe-wrapper hosts the bundled extractors don't handle. The resolvers download via OkHttp + 8-chunk parallel `Range` GETs.
- **Iframe wrappers** — a few listing-page hosts are auto-unwrapped before resolution so the underlying provider host is what reaches the resolver pipeline.

### YouTube auth (automatic)

YouTube actively blocks anonymous requests. SnagLite handles this in three layers:

1. **Auto-update yt-dlp** so latest extractor logic is in play.
2. **Per-request injection** of `--user-agent`, `--cookies`, `--add-header Accept-Language`, and `--extractor-args youtube:player_client=tv_simply,default,mweb;formats=missing_pot;visitor_data=…` (harvested via hidden WebView during setup).
3. **Auto-retry on bot/sign-in error** with a fallback `player_client` chain (`web_safari,android_vr,mweb`). Invisible. No user action.
4. **Sign-in fallback** — if both chains fail, the error card surfaces a **Sign in to YouTube** button. Tapping opens a full-screen WebView at Google login. On success SnagLite exports the cookies to `cookies.txt` and auto-retries the download. Cached forever.

Most public videos work without sign-in. Age-gated, members-only, or aggressively bot-checked videos fall through to step 4.

### Install

Build artifacts land in `android/app/build/outputs/apk/`:

| APK | Use case |
|---|---|
| `app-arm64-v8a-{debug,release}.apk` | 64-bit modern devices (recommended) |
| `app-armeabi-v7a-{debug,release}.apk` | 32-bit / older devices |
| `app-x86_64-{debug,release}.apk` | Emulators / x86_64 Chromebooks |
| `app-universal-{debug,release}.apk` | All ABIs in one APK (~150 MB) — sideload-friendly fallback |

Sideload via `adb install` or transfer + install.

#### Build from source

```powershell
cd android
.\gradlew.bat :app:assembleDebug          # debug APKs in app/build/outputs/apk/debug/
.\gradlew.bat :app:assembleRelease        # release APKs in app/build/outputs/apk/release/
```

The release build is signed with a release keystore if four env vars are set; otherwise it falls back to the local debug keystore (with a Gradle warning). R8 / resource-shrink is currently disabled in `release` while a launch-time crash on Android 14 is being diagnosed — see `CLAUDE.md` for context.

| Env var | Purpose |
|---|---|
| `SNAGLITE_KEYSTORE_PATH` | Absolute path to `.jks` / `.keystore`. |
| `SNAGLITE_KEYSTORE_PASS` | Store password. |
| `SNAGLITE_KEY_ALIAS` | Key alias. |
| `SNAGLITE_KEY_PASS` | Key password. |

Generate a keystore once with `keytool -genkeypair -v -keystore $HOME\snaglite-release.jks -keyalg RSA -keysize 2048 -validity 36500 -alias snaglite`.

Requires JDK 17 + Android SDK (API 34).

### Settings

Tap the gear icon on the main screen.

| Action | Effect |
|---|---|
| **Sign in to YouTube** | Opens Google login WebView; persists cookies. |
| **Update yt-dlp now** | Force-pulls latest stable yt-dlp. |
| **Re-run setup** | Wipes setup flag; returns to first-launch SetupScreen. |
| **Clear YouTube cookies** | Deletes `yt-cookies.txt` + WebView cookies; resets sign-in state. |
| **Delete file from device when removing** | Toggles the default state of the "also delete file" checkbox in the swipe-to-remove dialog. Per-swipe override always available. |
| Footer | Shows installed yt-dlp version. |

### Defaults

| Setting | Value |
|---|---|
| Output dir | `Movies/SnagLite` (video) or `Music/SnagLite` (audio-only), via MediaStore |
| Format | `bv*+ba/b`, merged to `.mp4` (video) / `ba/b` extracted to `.m4a` (audio) |
| Filename | `<title> [<id>].(mp4|m4a)` (yt-dlp `--restrict-filenames`) |
| Downloader (yt-dlp path) | `aria2c -x16 -s16 -k1M` |
| Downloader (resolver path) | OkHttp 8-chunk parallel `Range` GETs |
| Cache dir | `<cacheDir>/snaglite-dl/<download-id>/` (per-download subdir; kept across pause/resume, removed when the item is dismissed) |
| Concurrency | 3 active downloads; extras queued |

### Paths (on-device)

| Purpose | Location |
|---|---|
| Native binaries | `/data/data/com.patron.snaglite/files/` (libpython, ffmpeg, aria2c, yt-dlp.zip) |
| YouTube cookies | `<filesDir>/yt-cookies.txt` (Netscape format) |
| Prefs | `getSharedPreferences("snaglite_yt", MODE_PRIVATE)` + `getSharedPreferences("snaglite_app", MODE_PRIVATE)` |

### Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (Android 13+, requested at first-launch setup), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `ACCESS_WIFI_STATE` (for the high-perf Wi-Fi lock during downloads), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (prompted on first launch — see Background reliability above), `WRITE_EXTERNAL_STORAGE` (Android ≤9 only). No storage permission needed on Android 10+ (scoped storage / MediaStore).

### Troubleshooting

- **YouTube fails with "Please sign in" / 400 errors** — usually means primary + fallback `player_client` chains both regressed. Settings → **Update yt-dlp now**, then retry. If still failing, tap **Sign in to YouTube**.
- **`Unsupported URL`** — auto-retries with `--force-generic-extractor`. If still fails, the embed host has no resolver yet — open an issue with the URL.
- **`Couldn't extract video from <host>`** — host changed its template. Pull `adb logcat -s ResolverA ResolverB ResolverC Packer DirectDownloader` and report the URL. Resolver patterns live under [`android/app/src/main/java/com/patron/snaglite/download/resolvers/`](android/app/src/main/java/com/patron/snaglite/download/resolvers/) and updates land in minutes.
- **First-launch setup hangs at "Updating yt-dlp"** — slow network or GitHub throttling. Failure is non-fatal; the bundled yt-dlp will still work. Retry from Settings later.
- **Saved file not visible in gallery** — MediaStore indexes asynchronously; takes a few seconds. Pull to refresh in the gallery app.
- **App "keeps stopping" on launch** — the uncaught-exception handler writes a full stack trace to `Android/data/com.patron.snaglite/files/last_crash.txt`. Pull that file via any file manager (Files by Google, the system Files app, USB MTP) and share it; that's the fastest path to a fix.

---

## Migrating from Snag

The project was previously called **Snag**; the rebrand to **SnagLite** is purely a naming change — engine, features, and supported sites are unchanged.

- **CLI users (Windows):** on first run of `snaglite.exe`, the binary auto-moves your existing `%APPDATA%\Snag`, `%LOCALAPPDATA%\Snag`, and `%USERPROFILE%\Videos\Snag` directories to the new `SnagLite` names. No manual action needed; if the new directories already exist the move is skipped (idempotent).
- **Android users:** the `applicationId` changed from `com.patron.snag` to `com.patron.snaglite`, so the new APK installs alongside the old app rather than upgrading it. Uninstall the old `Snag` APK after confirming the new one works. On first launch, SnagLite moves any `Movies/Snag/*` and `Music/Snag/*` MediaStore rows it can update into `Movies/SnagLite/` and `Music/SnagLite/` (best-effort — rows owned by the old package may stay in place; this is harmless). Existing app preferences and the YouTube sign-in cookie are not carried over — you'll see the first-launch setup screen and battery-optimization prompt again.

### Out of scope (Android)

- Playlists / batch input files.
- Subtitles.
- Sharing intent (`Send to → SnagLite` from YouTube/etc.).
- Persistence of the download list across process death (in-memory only; queued/paused items are lost if the OS kills the process).
