using CliWrap;
using CliWrap.EventStream;
using SnagLite.Services.Resolvers;
using Spectre.Console;

namespace SnagLite.Services;

public sealed record Aria2cResult(int ExitCode, string OutputPath, string LastError);

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

        var args = new List<string>
        {
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
            media.DownloadUrl,
        };

        AnsiConsole.MarkupLine($"[grey]→ direct: {media.DownloadUrl[..Math.Min(80, media.DownloadUrl.Length)]}…[/]");

        var lastError = "";
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
                            {
                                lastError = stderr.Text;
                                AnsiConsole.MarkupLineInterpolated($"[red]{stderr.Text}[/]");
                            }
                            break;
                        case ExitedCommandEvent exited:
                            exitCode = exited.ExitCode;
                            if (exitCode == 0) { task.Value = 100; task.StopTask(); }
                            break;
                    }
                }
                return exitCode;
            });

        return new Aria2cResult(code, outPath, lastError);
    }

    // aria2c progress line shape:
    // [#abcd12 1.2GiB/4.5GiB(26%) CN:8 DL:12MiB ETA:4m12s]
    private static void HandleAria2Line(string line, ProgressTask task)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        var pctIdx = line.IndexOf('%');
        if (pctIdx <= 0) return;
        // Walk back to find '(' before the percent.
        var open = line.LastIndexOf('(', pctIdx);
        if (open < 0) return;
        var pctStr = line.Substring(open + 1, pctIdx - open - 1);
        if (!double.TryParse(pctStr, System.Globalization.NumberStyles.Any,
                System.Globalization.CultureInfo.InvariantCulture, out var pct)) return;

        task.IsIndeterminate = false;
        task.Value = pct;

        var dlIdx = line.IndexOf("DL:", StringComparison.Ordinal);
        var etaIdx = line.IndexOf("ETA:", StringComparison.Ordinal);
        string speed = "", eta = "";
        if (dlIdx >= 0)
        {
            var end = line.IndexOfAny(ProgressDelimiters, dlIdx + 3);
            if (end < 0) end = line.Length;
            speed = line.Substring(dlIdx + 3, end - dlIdx - 3);
        }
        if (etaIdx >= 0)
        {
            var end = line.IndexOfAny(ProgressDelimiters, etaIdx + 4);
            if (end < 0) end = line.Length;
            eta = line.Substring(etaIdx + 4, end - etaIdx - 4);
        }
        task.Description = $"[green]download[/] {speed}/s • ETA {eta}";
    }
}
