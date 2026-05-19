using System.IO.Compression;
using Spectre.Console;

namespace SnagLite.Services;

public static class ToolResolver
{
    private const string YtDlpUrl =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";

    private const string FfmpegZipUrl =
        "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

    private const string Aria2ZipUrl =
        "https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0-win-64bit-build1.zip";

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
            jobs.Add(("yt-dlp", Paths.YtDlp, FetchYtDlpAsync));
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

    public static async Task ForceUpdateYtDlpAsync(CancellationToken ct = default)
    {
        Paths.EnsureDir(Paths.BinDir);
        await AnsiConsole.Status()
            .Spinner(Spinner.Known.Dots)
            .StartAsync("Updating yt-dlp...", async _ =>
            {
                if (File.Exists(Paths.YtDlp)) File.Delete(Paths.YtDlp);
                await FetchYtDlpAsync(ct);
            });
        AnsiConsole.MarkupLine("[green]✓[/] yt-dlp updated");
    }

    private static async Task FetchYtDlpAsync(CancellationToken ct)
    {
        await using var src = await Http.GetStreamAsync(YtDlpUrl, ct);
        await using var dst = File.Create(Paths.YtDlp);
        await src.CopyToAsync(dst, ct);
    }

    private static async Task FetchFfmpegAsync(CancellationToken ct)
    {
        var zipPath = Path.Combine(Paths.BinDir, "ffmpeg.zip");
        await DownloadAsync(FfmpegZipUrl, zipPath, ct);
        try
        {
            using var archive = ZipFile.OpenRead(zipPath);
            var entry = archive.Entries.FirstOrDefault(e =>
                e.FullName.EndsWith("/bin/ffmpeg.exe", StringComparison.OrdinalIgnoreCase))
                ?? throw new InvalidOperationException("ffmpeg.exe not found in archive");
            entry.ExtractToFile(Paths.Ffmpeg, overwrite: true);
        }
        finally
        {
            if (File.Exists(zipPath)) File.Delete(zipPath);
        }
    }

    private static async Task FetchAria2Async(CancellationToken ct)
    {
        var zipPath = Path.Combine(Paths.BinDir, "aria2.zip");
        await DownloadAsync(Aria2ZipUrl, zipPath, ct);
        try
        {
            using var archive = ZipFile.OpenRead(zipPath);
            var entry = archive.Entries.FirstOrDefault(e =>
                e.FullName.EndsWith("/aria2c.exe", StringComparison.OrdinalIgnoreCase))
                ?? throw new InvalidOperationException("aria2c.exe not found in archive");
            entry.ExtractToFile(Paths.Aria2c, overwrite: true);
        }
        finally
        {
            if (File.Exists(zipPath)) File.Delete(zipPath);
        }
    }

    private static async Task DownloadAsync(string url, string dest, CancellationToken ct)
    {
        await using var src = await Http.GetStreamAsync(url, ct);
        await using var dst = File.Create(dest);
        await src.CopyToAsync(dst, ct);
    }
}
