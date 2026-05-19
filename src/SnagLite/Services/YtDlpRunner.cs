using CliWrap;
using CliWrap.EventStream;
using Spectre.Console;

namespace SnagLite.Services;

public sealed record YtDlpOptions
{
    public required string Url { get; init; }
    public required string OutputDir { get; init; }
    public bool AudioOnly { get; init; }
    public string? FormatOverride { get; init; }
    public bool UseAria2 { get; init; } = true;
    public bool ForceGeneric { get; init; }
}

public sealed class YtDlpResult
{
    public int ExitCode { get; init; }
    public string LastError { get; init; } = "";
}

public static class YtDlpRunner
{
    public static async Task<YtDlpResult> RunAsync(YtDlpOptions opt, CancellationToken ct)
    {
        var args = BuildArgs(opt);

        var lastError = "";
        AnsiConsole.MarkupLine($"[grey]→ {opt.Url}[/]");

        var exit = await AnsiConsole.Progress()
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
                var task = pctx.AddTask("Preparing", maxValue: 100);
                task.IsIndeterminate = true;

                var cmd = Cli.Wrap(Paths.YtDlp)
                    .WithArguments(args)
                    .WithWorkingDirectory(opt.OutputDir)
                    .WithValidation(CommandResultValidation.None);

                int code = -1;
                await foreach (var ev in cmd.ListenAsync(ct))
                {
                    switch (ev)
                    {
                        case StandardOutputCommandEvent stdout:
                            HandleLine(stdout.Text, task);
                            break;
                        case StandardErrorCommandEvent stderr:
                            if (!string.IsNullOrWhiteSpace(stderr.Text))
                            {
                                lastError = stderr.Text;
                                if (stderr.Text.StartsWith("ERROR", StringComparison.OrdinalIgnoreCase))
                                    AnsiConsole.MarkupLineInterpolated($"[red]{stderr.Text}[/]");
                            }
                            break;
                        case ExitedCommandEvent exited:
                            code = exited.ExitCode;
                            if (code == 0)
                            {
                                task.Value = 100;
                                task.StopTask();
                            }
                            break;
                    }
                }
                return code;
            });

        return new YtDlpResult { ExitCode = exit, LastError = lastError };
    }

    private static void HandleLine(string line, ProgressTask task)
    {
        if (ProgressParser.TryParse(line, out var tick))
        {
            task.IsIndeterminate = false;
            task.Value = tick.Percent;
            task.Description = $"[green]download[/] {tick.Speed} • ETA {tick.Eta}";
            return;
        }

        if (line.Contains("[Merger]", StringComparison.Ordinal) ||
            line.Contains("[ffmpeg]", StringComparison.Ordinal))
        {
            task.IsIndeterminate = true;
            task.Description = "[yellow]merging[/]";
        }
        else if (line.Contains("[ExtractAudio]", StringComparison.Ordinal))
        {
            task.IsIndeterminate = true;
            task.Description = "[yellow]extracting audio[/]";
        }
        else if (line.StartsWith("[download] Destination:", StringComparison.Ordinal))
        {
            task.Description = "[green]downloading[/]";
        }
    }

    private static List<string> BuildArgs(YtDlpOptions opt)
    {
        var args = new List<string>
        {
            opt.Url,
            "--ffmpeg-location", Paths.BinDir,
            "--no-mtime",
            "--restrict-filenames",
            "--no-part",
            "--newline",
            "--progress-template",
            "PROG|%(progress._percent_str)s|%(progress._total_bytes_str)s|%(progress._speed_str)s|%(progress._eta_str)s",
            "-o", "%(title)s [%(id)s].%(ext)s",
            "-P", opt.OutputDir,
        };

        if (opt.UseAria2)
        {
            // yt-dlp accepts a full path as the downloader name; this avoids the
            // `--external-downloader-path` option which doesn't exist in stable yt-dlp.
            args.Add("--downloader");
            args.Add(Paths.Aria2c);
            args.Add("--downloader-args");
            args.Add("aria2c:-x16 -s16 -k1M --console-log-level=warn");
        }

        if (opt.ForceGeneric)
        {
            args.Add("--force-generic-extractor");
            args.Add("--add-header");
            args.Add($"Referer:{InferOrigin(opt.Url)}");
        }

        if (!string.IsNullOrWhiteSpace(opt.FormatOverride))
        {
            args.Add("-f");
            args.Add(opt.FormatOverride);
        }
        else if (opt.AudioOnly)
        {
            args.Add("-f"); args.Add("ba/b");
            args.Add("-x");
            args.Add("--audio-format"); args.Add("m4a");
        }
        else
        {
            args.Add("-f"); args.Add("bv*+ba/b");
            args.Add("--merge-output-format"); args.Add("mp4");
        }

        return args;
    }

    private static string InferOrigin(string url)
    {
        try { var u = new Uri(url); return $"{u.Scheme}://{u.Host}/"; }
        catch { return ""; }
    }
}
