---
title: "Cross-platform duplicated core (CLI and Android)"
kind: architecture
created: 2026-08-21
updated: 2026-08-21
status: completed
confidence: 90%
provenance:
  repo: SnagLite
  sha: e904cc7
  branch: main
  sources:
    - src/SnagLite/Commands/DownloadCommand.cs
    - src/SnagLite/Services/YtDlpRunner.cs
    - src/SnagLite/Services/Resolvers/IResolver.cs
    - src/SnagLite/Services/Resolvers/ResolverPipeline.cs
    - src/SnagLite/Services/Resolvers/IframeUnwrapper.cs
    - src/SnagLite/Services/YouTube/YouTubeHost.cs
    - src/SnagLite/Services/YouTube/YouTubeArgsInjector.cs
    - src/SnagLite/Services/YouTube/EngineUpdateChecker.cs
    - src/SnagLite/Services/ToolResolver.cs
    - android/app/src/main/java/com/patron/snaglite/download/Downloader.kt
    - android/app/src/main/java/com/patron/snaglite/download/resolvers/ResolverPipeline.kt
    - android/app/src/main/java/com/patron/snaglite/download/resolvers/IframeUnwrapper.kt
    - android/app/src/main/java/com/patron/snaglite/yt/YouTubeHost.kt
    - android/app/src/main/java/com/patron/snaglite/yt/YouTubeArgsInjector.kt
    - android/app/src/main/java/com/patron/snaglite/yt/EngineUpdateChecker.kt
    - android/app/src/main/java/com/patron/snaglite/yt/YouTubePrefs.kt
    - android/app/src/main/java/com/patron/snaglite/yt/YouTubeUpdater.kt
  derived_by: dev
---

# Cross-platform duplicated core (CLI and Android)

## Summary

SnagLite ships two independent front-ends over the same three external binaries, and they share
no code: a .NET 10 Windows CLI under `src/SnagLite/` and a Kotlin/Compose app under `android/`.
Six units of non-trivial logic exist twice, once per language, as a hand-port — the resolver
pipeline, the iframe unwrapper, YouTube host detection, YouTube argument injection, the engine
update checker, and the download-attempt/retry ladder. The duplication is deliberate and
structurally unavoidable here (no shared runtime between a self-contained win-x64 executable and
an Android APK), but **nothing mechanically enforces parity between the two copies**, and there
is no test suite on either side. Anyone changing one copy is responsible for finding and
changing the other by hand.

## How it works

Both platforms implement the same four-stage ladder for a single URL. The stages are in the same
order and the fallback semantics match; only the surrounding machinery differs.

**Stage 1 — iframe unwrap, host-gated.** A short list of wrapper hosts is loaded from a
gitignored `hosts.local.txt`; a missing file yields an empty list and silently disables the
feature. CLI reads it as an embedded assembly resource (`src/SnagLite/Services/Resolvers/IframeUnwrapper.cs:21`,
wired at `src/SnagLite/SnagLite.csproj` via a conditional `EmbeddedResource`); Android reads it as
an APK asset through `IframeUnwrapper.init` called from `SnagLiteApplication.kt:76`. Both then
regex the page for the first `<iframe src=…>` and continue against the inner URL.

**Stage 2 — custom resolver pipeline.** Both define the same interface shape, modulo language
types — a `name`, a host-match predicate, and a resolve call returning a media URL plus referer,
suggested filename and user-agent (`src/SnagLite/Services/Resolvers/IResolver.cs:3-14`). The
predicate signatures differ: the CLI matches on a `Uri` (`IResolver.cs:12`), Android on an
already-lowercased host string (`resolvers/ResolverPipeline.kt:28-29`). Both register the same
three resolvers under the same names `a`, `b`, `c`, in the same order
(`src/SnagLite/Services/Resolvers/ResolverPipeline.cs:20-25`;
`android/app/src/main/java/com/patron/snaglite/download/resolvers/ResolverPipeline.kt:10-14`).
A hit downloads directly through aria2c rather than yt-dlp.

**Stage 3 — yt-dlp.** Both build broadly the same argument set: `--no-mtime`,
`--restrict-filenames`, `--newline`, `-f bv*+ba/b` with `--merge-output-format mp4`, or `-f ba/b`
with `-x --audio-format m4a` for audio, plus aria2c as the external downloader with
`-x16 -s16 -k1M` (`src/SnagLite/Services/YtDlpRunner.cs:112-160`; `download/Downloader.kt:224-254`).

**Stage 4 — retry.** Two retries on each side, and only two. `Unsupported URL` re-runs with
`--force-generic-extractor` plus an inferred `Referer`
(`src/SnagLite/Commands/DownloadCommand.cs:149-153`; `download/Downloader.kt:162-166`). A YouTube
403 or sign-in error refreshes the engine and re-runs with the fallback `player_client` chain
(`DownloadCommand.cs:134-148`; `Downloader.kt:180-195`).

**The parity surface that matters most is the YouTube extractor arguments**, because they are
tuned against a moving target and are the thing most likely to need an urgent edit. Both files
currently carry byte-identical constants:

- `player_client=tv_simply,default,mweb;formats=missing_pot` (primary)
- `player_client=web_safari,android_vr,mweb;formats=missing_pot` (fallback)

at `src/SnagLite/Services/YouTube/YouTubeArgsInjector.cs:5-6` and
`android/app/src/main/java/com/patron/snaglite/yt/YouTubeArgsInjector.kt:10-11`. The seven-entry
host set and both regexes are mirrored character-for-character too: sign-in at
`YouTubeHost.cs:18-20` / `YouTubeHost.kt:21-24`, and 403 at `YouTubeHost.cs:22-24` /
`YouTubeHost.kt:26-29` (same alternation, differing only in Kotlin's doubled backslashes). The
iframe pattern is likewise identical — `IframeUnwrapper.cs:55-56` and
`resolvers/IframeUnwrapper.kt:32-34` both match the first `<iframe … src="…">`, case-insensitively.

## Contracts and invariants

- **Resolver identity is a cross-platform contract.** The names `a`, `b`, `c` appear in Android
  user-facing error text (`download/Downloader.kt:111`, `:120`) and in CLI console output
  (`DownloadCommand.cs:92`). Renaming or reordering a resolver on one platform without the other
  makes the same failure report a different resolver name depending on where it was hit.
- **A resolver hit is committed to, not a first attempt** — on Android. When a resolver matches
  the host, `Downloader.kt:117-123` returns a specific failure rather than falling through to
  yt-dlp, deliberately: yt-dlp's bundled extractors for these embed hosts are stale, so falling
  through swaps a specific error for a misleading generic one. The CLI does fall through
  (`DownloadCommand.cs:108-115`). This is a genuine divergence in the shared ladder, not a port
  gap — the Android comment states the reasoning.
- **Resolvers return progressive mp4 only.** Neither platform can serve `--audio-only` /
  audio-format through a resolver. Android says so explicitly (`Downloader.kt:83-89`); the CLI
  instead skips the pipeline entirely whenever `--audio-only` or `-f` is set
  (`DownloadCommand.cs:86`), which silently degrades a wrapper-host URL to yt-dlp.
- **The wrapper-host list is build-time input, not runtime config.** Both platforms accept its
  absence as normal. Any conclusion about which hosts are handled cannot be drawn from this
  repository — the list is gitignored on purpose.
- **Only Android suppresses `YouTubeArgsInjector` when the generic extractor is forced. The CLI
  does not, and this is a genuine asymmetry rather than a mirrored rule.** Android returns early
  at `YouTubeArgsInjector.kt:23`. The CLI has no equivalent gate: `ExtraArgs` is built once at
  `DownloadCommand.cs:127`, the generic retry re-runs with `opts with { ForceGeneric = true }`
  (`:152`) which carries the same enumerable through untouched, and `BuildArgs` appends it
  unconditionally — `YtDlpRunner.cs:162` is `if (opt.ExtraArgs is not null)`, with no
  `ForceGeneric` condition (the only `ForceGeneric` branch, `:138`, adds the generic args and
  nothing else). So a YouTube URL reaching the CLI's generic retry *would* still be passed
  `--extractor-args`. The reason this has never been observed is incidental, not structural: the
  injector's own guard is `if (!YouTubeHost.IsYouTube(url)) yield break`
  (`YouTubeArgsInjector.cs:13`), which keys on the URL alone, and a real YouTube URL essentially
  never yields `Unsupported URL` in the first place. Do not rely on the CLI having this
  suppression — if a YouTube host ever does reach that retry, the behaviour differs from Android.

## Gotchas

- **The two copies are already permitted to diverge, so "they match" is not a safe default
  assumption.** Confirmed intentional divergences at this SHA: Android layers cookies and a
  harvested `visitor_data` onto the extractor args (`YouTubeArgsInjector.kt:31-40`) where the CLI
  has neither, by scope decision; and the engine-update paths differ in kind, not just cadence
  (next bullet). Neither is a bug. The point is that a reader cannot tell an intentional
  divergence from a missed port by inspection alone.
- **The engine updaters are not the same operation, and Android carries a dead second copy of the
  CLI's version.** The CLI does a *blind* refresh: after a 7-day debounce
  (`src/SnagLite/Services/YouTube/EngineUpdateChecker.cs:7`) it re-downloads yt-dlp
  unconditionally, without asking whether the installed build is already current
  (`ToolResolver.SilentRefreshYtDlpAsync`, called at `EngineUpdateChecker.cs:27`). Live Android
  does a *check-then-update*: a 24 h debounce (`YouTubePrefs.kt:20`,
  `ENGINE_CHECK_INTERVAL_MS`), a metered-network skip (`EngineUpdateChecker.kt:51-54`), then a
  GitHub releases-API version comparison (`EngineUpdateChecker.kt:73-124`) that only installs when
  the remote tag is actually newer. Reaching for the two debounce constants and reading a simple
  "24 h vs 7 days" frequency difference is therefore wrong twice over.
  **Additionally, `YouTubeUpdater.maybeUpdate` (`yt/YouTubeUpdater.kt:30`) is dead code** — a
  blind, 7-day-gated update that mirrors the CLI's design exactly, gated on
  `YouTubePrefs.UPDATE_INTERVAL_MS` (`YouTubePrefs.kt:19`, 7 days), with **zero call sites** in
  `android/`. Its presence makes the two platforms look more aligned than they are, and
  `UPDATE_INTERVAL_MS` looks like a live 7-day Android debounce when nothing reads it. The live
  entry points are `SnagLiteApplication.kt:92` and `UpdateGate.kt:25`, both calling
  `runSilentUpdateIfDue`. Only `maybeUpdate` is orphaned — `YouTubeUpdater.updateNow` is live at
  four call sites (`Downloader.kt:189`, `DownloadController.kt:162`, `SettingsScreen.kt:136`,
  `SetupViewModel.kt:80`), so deleting the dead function does not touch the updater itself.
- **Only Android's yt-dlp invocation can resume.** Android passes `--continue`
  (`Downloader.kt:229`); the CLI passes `--no-part` (`YtDlpRunner.cs:120`), which precludes it.
  Whether that should be aligned is an open decision, not a finding — recorded here only because
  it is invisible from the CLI side and looks like an oversight when read from the Android side.
- **Grepping for a symbol finds one platform, not both.** The names differ by language
  convention — `IResolver.Matches` vs `IResolver.match`, `ResolverPipeline.ResolveAsync` vs
  `resolve`, `IsYouTube` vs `isYouTube`. A search for the C# spelling returns no Kotlin hits and
  reads as "this only exists once."
- **There is no test suite on either platform, and this is deliberate.**
  `Directory.Build.props:11-19` declines to promote analyzer warnings to errors specifically
  because there is no safety net to justify a build break. So a parity regression will not be
  caught by CI; it will be caught by a user.

## Open questions

- **Whether resolvers `a`, `b`, `c` are behaviourally equivalent across platforms is
  unverified.** Only the interface shape, the registration order and the names were checked. The
  implementation bodies (~700 lines combined across `ResolverA/B/C` in both languages) were not
  read, and their extraction logic could have drifted arbitrarily without any signal.
- No build was run and no download was performed while deriving this note. Every statement above
  is a claim about source text at the recorded SHA, not about observed behaviour.

## Session Log

- 2026-08-21 — Created from survey analysis at e904cc7. Working tree had one uncommitted edit to
  `README.md` at time of writing, unrelated to the sources listed above. Confidence 90% —
  resolver implementation bodies unread, no build or run performed. dev
- 2026-08-21 — Re-checked four citations asserted from partial reads before closing. Corrected
  `Directory.Build.props` and `EngineUpdateChecker.kt` line ranges; confirmed the `YouTubeHost`
  regexes and the Android resolver registration order rather than assuming them; sharpened the
  `IResolver` claim from "identical" to "same shape, differing predicate types". Resolved the
  open question on the iframe regexes — they match. dev
- 2026-08-21 — Blind verifier run against this note; three findings, all reproduced against live
  code before acting. **Refuted and rewritten:** the claim that the CLI suppresses
  `YouTubeArgsInjector` on a forced-generic retry "by design" — it has no such gate
  (`YtDlpRunner.cs:162` appends `ExtraArgs` unconditionally), and the observed behaviour is
  incidental to the injector's URL-only guard. Corrected `--no-part` citation 121 → 120. Added
  the engine-updater bullet: the two platforms run different operations (blind refresh vs
  check-then-update), and `YouTubeUpdater.maybeUpdate` is dead code that makes them look aligned.
  Sources extended with `ToolResolver.cs` and `YouTubeUpdater.kt`. Confidence held at 90% —
  the refutation was in a section the verifier could check, and the section it could not
  (resolver bodies) is unchanged. dev
