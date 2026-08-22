using System.IO.Compression;
using System.Security.Cryptography;
using Spectre.Console;

namespace SnagLite.Services;

/// A downloaded tool did not match its published SHA-256.
public sealed class ToolIntegrityException(string message) : Exception(message);

public static class ToolResolver
{
    private const string YtDlpUrl =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
    private const string YtDlpSumsUrl =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/SHA2-256SUMS";

    private const string FfmpegZipUrl =
        "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
    private const string FfmpegZipSha256Url = FfmpegZipUrl + ".sha256";

    private const string Aria2ZipUrl =
        "https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0-win-64bit-build1.zip";
    // The aria2 GitHub release publishes no checksums. This value was computed
    // from a fresh download on 2026-08-22 and matches the x64 InstallerSha256 in
    // the winget-pkgs manifest manifests/a/aria2/aria2/1.37.0/aria2.aria2.installer.yaml.
    // Bumping Aria2ZipUrl means recomputing this the same way.
    private const string Aria2ZipSha256 =
        "67d015301eef0b612191212d564c5bb0a14b5b9c4796b76454276a4d28d9b288";

    private static readonly HttpClient Http = CreateHttp();

    private static HttpClient CreateHttp()
    {
        var c = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true })
        {
            Timeout = TimeSpan.FromMinutes(10),
        };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("SnagLite/2.0 (+windows)");
        return c;
    }

    public static async Task EnsureAllAsync(CancellationToken ct = default)
    {
        Paths.EnsureDir(Paths.BinDir);
        var jobs = new List<(string Name, string Path, Func<CancellationToken, Task> Fetch)>();
        if (!File.Exists(Paths.YtDlp))
            jobs.Add(("yt-dlp", Paths.YtDlp, InstallYtDlpAsync));
        if (!File.Exists(Paths.Ffmpeg))
            jobs.Add(("ffmpeg", Paths.Ffmpeg, FetchFfmpegAsync));
        if (!File.Exists(Paths.Aria2c))
            jobs.Add(("aria2c", Paths.Aria2c, FetchAria2Async));

        if (jobs.Count == 0) return;

        await AnsiConsole.Status()
            .Spinner(Spinner.Known.Dots)
            .StartAsync("Setting up tools...", async ctx =>
            {
                foreach (var (name, _, fetch) in jobs)
                {
                    ctx.Status($"Downloading {name}...");
                    await fetch(ct);
                    AnsiConsole.MarkupLine($"[green]✓[/] {name} ready");
                }
            });
    }

    /// `snaglite update`: fetch the latest yt-dlp and swap it in. The existing
    /// binary is only replaced once the new one has downloaded and verified, so
    /// a failed update leaves the engine you had.
    public static async Task ForceUpdateYtDlpAsync(CancellationToken ct = default)
    {
        Paths.EnsureDir(Paths.BinDir);
        await AnsiConsole.Status()
            .Spinner(Spinner.Known.Dots)
            .StartAsync("Updating yt-dlp...", async _ => await InstallYtDlpAsync(ct));
        AnsiConsole.MarkupLine("[green]✓[/] yt-dlp updated");
    }

    /// Silent (no UI) yt-dlp refresh. Downloads to a side file, verifies, then
    /// swaps in. If yt-dlp.exe is currently executing the swap fails with
    /// IOException — that's expected and surfaced to the caller.
    public static Task SilentRefreshYtDlpAsync(CancellationToken ct = default) => InstallYtDlpAsync(ct);

    private static async Task InstallYtDlpAsync(CancellationToken ct)
    {
        var tmp = await PrefetchYtDlpAsync(ct);
        CommitYtDlp(tmp);
    }

    /// Downloads the latest yt-dlp.exe to a verified side file and returns its
    /// path; nothing in bin/ is touched until <see cref="CommitYtDlp"/>.
    /// SHA2-256SUMS is fetched before the binary — both resolve through
    /// "latest", so the other order could straddle a release and produce a
    /// spurious mismatch. One mismatch is retried once for the same reason.
    internal static async Task<string> PrefetchYtDlpAsync(CancellationToken ct)
    {
        Paths.EnsureDir(Paths.BinDir);
        var tmp = Paths.YtDlp + ".new";
        for (var attempt = 1; ; attempt++)
        {
            var sums = await Http.GetStringAsync(YtDlpSumsUrl, ct);
            var expected = ParseSha256Sums(sums, "yt-dlp.exe")
                ?? throw new ToolIntegrityException("yt-dlp: SHA2-256SUMS has no entry for yt-dlp.exe");
            await DownloadToAsync(YtDlpUrl, tmp, ct);
            try
            {
                await VerifyAsync(tmp, expected, "yt-dlp", ct);
                return tmp;
            }
            catch (ToolIntegrityException) when (attempt == 1)
            {
                // fall through and try once more
            }
        }
    }

    /// Moves a prefetched, verified yt-dlp.exe.new over yt-dlp.exe.
    internal static void CommitYtDlp(string tmp) => File.Move(tmp, Paths.YtDlp, overwrite: true);

    private static async Task FetchFfmpegAsync(CancellationToken ct)
    {
        var expected = ParseBareSha256(await Http.GetStringAsync(FfmpegZipSha256Url, ct))
            ?? throw new ToolIntegrityException($"ffmpeg: could not read the published SHA-256 from {FfmpegZipSha256Url}");
        await FetchZipEntryAsync(FfmpegZipUrl, expected, "ffmpeg", "/bin/ffmpeg.exe", Paths.Ffmpeg, ct);
    }

    private static Task FetchAria2Async(CancellationToken ct) =>
        FetchZipEntryAsync(Aria2ZipUrl, Aria2ZipSha256, "aria2c", "/aria2c.exe", Paths.Aria2c, ct);

    /// Download a zip, verify it, extract one entry to <paramref name="dest"/>
    /// via a side file so an interrupted extract never leaves a truncated exe.
    private static async Task FetchZipEntryAsync(
        string url, string expectedSha256, string toolName, string entrySuffix, string dest, CancellationToken ct)
    {
        var zipPath = Path.Combine(Paths.BinDir, toolName + ".zip");
        try
        {
            await DownloadToAsync(url, zipPath, ct);
            await VerifyAsync(zipPath, expectedSha256, toolName, ct);
            using var archive = ZipFile.OpenRead(zipPath);
            var entry = archive.Entries.FirstOrDefault(e =>
                e.FullName.EndsWith(entrySuffix, StringComparison.OrdinalIgnoreCase))
                ?? throw new InvalidOperationException($"{Path.GetFileName(dest)} not found in archive");
            var tmp = dest + ".new";
            entry.ExtractToFile(tmp, overwrite: true);
            File.Move(tmp, dest, overwrite: true);
        }
        finally
        {
            if (File.Exists(zipPath)) File.Delete(zipPath);
        }
    }

    /// Streams a URL to <paramref name="dest"/>; a failed or cancelled transfer
    /// deletes the partial file rather than leaving it to be mistaken for a
    /// complete one.
    private static async Task DownloadToAsync(string url, string dest, CancellationToken ct)
    {
        try
        {
            await using var src = await Http.GetStreamAsync(url, ct);
            await using var dst = File.Create(dest);
            await src.CopyToAsync(dst, ct);
        }
        catch
        {
            try { if (File.Exists(dest)) File.Delete(dest); } catch { }
            throw;
        }
    }

    /// Hashes <paramref name="path"/>; on mismatch deletes it and throws.
    internal static async Task VerifyAsync(string path, string expectedSha256Hex, string toolName, CancellationToken ct)
    {
        string actual;
        await using (var fs = File.OpenRead(path))
            actual = Convert.ToHexStringLower(await SHA256.HashDataAsync(fs, ct));
        if (string.Equals(actual, expectedSha256Hex, StringComparison.OrdinalIgnoreCase)) return;

        try { File.Delete(path); } catch { }
        throw new ToolIntegrityException(
            $"{toolName}: SHA-256 mismatch — expected {expectedSha256Hex.ToLowerInvariant()}, got {actual}. " +
            $"The download may be corrupt or tampered with; retry, or drop a known-good {Path.GetFileName(path).Replace(".new", "")} into {Paths.BinDir} manually.");
    }

    /// Verify a side file and move it over its destination in one step.
    internal static async Task CommitVerifiedAsync(string tmp, string dest, string expectedSha256Hex, string toolName, CancellationToken ct)
    {
        await VerifyAsync(tmp, expectedSha256Hex, toolName, ct);
        File.Move(tmp, dest, overwrite: true);
    }

    /// Finds <paramref name="fileName"/> in a sha256sum-style listing
    /// ("&lt;hex&gt;  &lt;name&gt;" or "&lt;hex&gt; *&lt;name&gt;" per line). Lower-case hex, or null.
    internal static string? ParseSha256Sums(string sumsText, string fileName)
    {
        if (string.IsNullOrEmpty(fileName)) return null;
        foreach (var raw in sumsText.Split('\n'))
        {
            var line = raw.Trim();
            var sep = line.IndexOf(' ');
            if (sep != 64) continue;
            var name = line[(sep + 1)..].TrimStart(' ', '*');
            if (!string.Equals(name, fileName, StringComparison.Ordinal)) continue;
            var hex = line[..64];
            return IsHex(hex) ? hex.ToLowerInvariant() : null;
        }
        return null;
    }

    /// A bare 64-hex-digit body (gyan.dev's *.sha256 sidecar). Lower-case hex, or null.
    internal static string? ParseBareSha256(string text)
    {
        var t = text.Trim();
        return t.Length == 64 && IsHex(t) ? t.ToLowerInvariant() : null;
    }

    private static bool IsHex(string s) => s.All(Uri.IsHexDigit);
}
