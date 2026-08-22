using System.Globalization;
using CliWrap;
using CliWrap.EventStream;
using SnagLite.Services.Resolvers;
using Spectre.Console;

namespace SnagLite.Services;

public sealed record Aria2cResult(int ExitCode, string OutputPath, string LastError);

public readonly record struct Aria2Tick(double Percent, string Speed, string Eta);

public static class Aria2cDownloader
{
    private static readonly char[] ProgressDelimiters = { ' ', ']' };

    public static async Task<Aria2cResult> RunAsync(
        ResolvedMedia media,
        string outputDir,
        CancellationToken ct)
    {
        Directory.CreateDirectory(outputDir);
        var outPath = Path.Combine(outputDir, media.SuggestedName);
        var args = BuildArgs(media, outputDir);

        AnsiConsole.MarkupLineInterpolated($"[grey]→ direct: {media.DownloadUrl[..Math.Min(80, media.DownloadUrl.Length)]}…[/]");

        // Spectre's live renderer owns the console while Progress runs; stderr is
        // collected here and printed once the bar is gone.
        var errors = new List<string>();
        var code = await AnsiConsole.Progress()
            .AutoRefresh(true)
            .HideCompleted(false)
            .Columns(
                new TaskDescriptionColumn(),
                new ProgressBarColumn(),
                new PercentageColumn(),
                new RemainingTimeColumn(),
                new SpinnerColumn())
            .StartAsync(async pctx =>
            {
                var task = pctx.AddTask("downloading", maxValue: 100);
                task.IsIndeterminate = true;

                var cmd = Cli.Wrap(Paths.Aria2c)
                    .WithArguments(args)
                    .WithWorkingDirectory(outputDir)
                    .WithValidation(CommandResultValidation.None);

                int exitCode = -1;
                await foreach (var ev in cmd.ListenAsync(ct))
                {
                    switch (ev)
                    {
                        case StandardOutputCommandEvent stdout:
                            HandleAria2Line(stdout.Text, task);
                            break;
                        case StandardErrorCommandEvent stderr:
                            if (!string.IsNullOrWhiteSpace(stderr.Text))
                                errors.Add(stderr.Text);
                            break;
                        case ExitedCommandEvent exited:
                            exitCode = exited.ExitCode;
                            if (exitCode == 0) { task.Value = 100; task.StopTask(); }
                            break;
                    }
                }
                return exitCode;
            });

        foreach (var e in errors)
            AnsiConsole.MarkupLineInterpolated($"[red]{e}[/]");

        return new Aria2cResult(code, outPath, errors.Count > 0 ? errors[^1] : "");
    }

    /// The URL comes last, after "--": it was extracted from a remote page, and
    /// aria2c accepts options anywhere on the command line, so without the
    /// end-of-options marker a hostile page could hand back e.g.
    /// --on-download-complete=... and have it honoured.
    internal static List<string> BuildArgs(ResolvedMedia media, string outputDir) =>
    [
        "-x16", "-s16", "-k1M",
        "--console-log-level=warn",
        "--summary-interval=1",
        "--allow-overwrite=true",
        "--auto-file-renaming=false",
        "--file-allocation=none",
        $"--user-agent={media.UserAgent}",
        $"--referer={media.Referer}",
        "-d", outputDir,
        "-o", media.SuggestedName,
        "--",
        media.DownloadUrl,
    ];

    private static void HandleAria2Line(string line, ProgressTask task)
    {
        if (!TryParseProgressLine(line, out var tick)) return;
        task.IsIndeterminate = false;
        task.Value = tick.Percent;
        task.Description = $"[green]download[/] {tick.Speed}/s • ETA {tick.Eta}";
    }

    // aria2c progress line shape:
    // [#abcd12 1.2GiB/4.5GiB(26%) CN:8 DL:12MiB ETA:4m12s]
    internal static bool TryParseProgressLine(string line, out Aria2Tick tick)
    {
        tick = default;
        if (string.IsNullOrWhiteSpace(line)) return false;
        var pctIdx = line.IndexOf('%');
        if (pctIdx <= 0) return false;
        // Walk back to find '(' before the percent.
        var open = line.LastIndexOf('(', pctIdx);
        if (open < 0) return false;
        var pctStr = line.Substring(open + 1, pctIdx - open - 1);
        if (!double.TryParse(pctStr, NumberStyles.Any, CultureInfo.InvariantCulture, out var pct)) return false;

        tick = new Aria2Tick(pct, Token(line, "DL:"), Token(line, "ETA:"));
        return true;
    }

    private static string Token(string line, string prefix)
    {
        var idx = line.IndexOf(prefix, StringComparison.Ordinal);
        if (idx < 0) return "";
        var start = idx + prefix.Length;
        var end = line.IndexOfAny(ProgressDelimiters, start);
        if (end < 0) end = line.Length;
        return line[start..end];
    }
}
