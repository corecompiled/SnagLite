using System.ComponentModel;
using SnagLite.Config;
using SnagLite.Services;
using SnagLite.Services.Resolvers;
using SnagLite.Services.YouTube;
using Spectre.Console;
using Spectre.Console.Cli;

namespace SnagLite.Commands;

public sealed class DownloadCommand : AsyncCommand<DownloadCommand.Settings>
{
    public sealed class Settings : CommandSettings
    {
        [Description("URL to download.")]
        [CommandArgument(0, "<URL>")]
        public string Url { get; init; } = "";

        [Description("Output directory (overrides default and saved config).")]
        [CommandOption("-o|--output <PATH>")]
        public string? OutputDir { get; init; }

        [Description("Audio-only (m4a).")]
        [CommandOption("--audio-only")]
        public bool AudioOnly { get; init; }

        [Description("Custom yt-dlp -f format spec (overrides default best mp4).")]
        [CommandOption("-f|--format <SPEC>")]
        public string? Format { get; init; }

        [Description("Disable aria2c, use yt-dlp native downloader.")]
        [CommandOption("--no-aria2")]
        public bool NoAria2 { get; init; }

        public override ValidationResult Validate()
        {
            if (string.IsNullOrWhiteSpace(Url))
                return ValidationResult.Error("URL is required.");
            if (!Uri.TryCreate(Url, UriKind.Absolute, out var u) ||
                (u.Scheme != Uri.UriSchemeHttp && u.Scheme != Uri.UriSchemeHttps))
                return ValidationResult.Error("URL must be http(s).");
            return ValidationResult.Success();
        }
    }

    // Exit codes: 0 ok · 1 usage/config · 2 download failed · 3 tool setup · 130 cancelled.
    public const int ExitCancelled = 130;

    // Ctrl+C is wired once in Program.cs; the token arrives here through Spectre.
    protected override async Task<int> ExecuteAsync(CommandContext context, Settings settings, CancellationToken ct)
    {
        try
        {
            if (!await ToolSetup.TryEnsureAllAsync(ct)) return ToolSetup.ExitToolSetup;

            // Prefetch a newer yt-dlp in the background; it is swapped in *after*
            // the download so the engine is never replaced under a running process
            // (and never missing when one starts).
            var engine = EngineUpdateChecker.Start(ct);
            try
            {
                return await DownloadAsync(settings, ct);
            }
            finally
            {
                if (!ct.IsCancellationRequested)
                {
                    var r = await engine.CommitAsync(TimeSpan.FromSeconds(10));
                    if (r.Outcome == EngineUpdateOutcome.Updated)
                        AnsiConsole.MarkupLine("[grey]yt-dlp refreshed to the latest release.[/]");
                    else if (r.Outcome == EngineUpdateOutcome.Failed)
                        AnsiConsole.MarkupLineInterpolated($"[grey]yt-dlp background refresh failed: {r.Message} (will retry in 24h)[/]");
                }
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            AnsiConsole.MarkupLine("[yellow]Cancelled.[/]");
            return ExitCancelled;
        }
    }

    private static async Task<int> DownloadAsync(Settings settings, CancellationToken ct)
    {
        var cfg = AppConfig.Load();
        string outDir;
        try
        {
            outDir = cfg.ResolveOutputDir(settings.OutputDir);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            AnsiConsole.MarkupLineInterpolated($"[red]Cannot use output directory:[/] {ex.Message}");
            AnsiConsole.MarkupLine("[grey]Pass a different one with -o, or fix the saved default with `snaglite config set-output <path>`.[/]");
            return 1;
        }
        AnsiConsole.MarkupLineInterpolated($"[grey]Output:[/] {outDir}");

        var workUrl = new Uri(settings.Url);

        // Some sites are listing pages that embed a third-party player via <iframe>.
        // Unwrap upfront so the resolver/yt-dlp engines see the real provider host
        // instead of the listing page.
        if (IframeUnwrapper.ShouldUnwrap(workUrl))
        {
            try
            {
                var inner = await IframeUnwrapper.UnwrapAsync(workUrl, ct);
                if (inner is not null)
                {
                    AnsiConsole.MarkupLineInterpolated($"[grey]Unwrapped iframe:[/] {inner}");
                    workUrl = inner;
                }
                else
                {
                    AnsiConsole.MarkupLine("[yellow]Iframe unwrap found no inner src.[/]");
                }
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception ex)
            {
                AnsiConsole.MarkupLineInterpolated($"[yellow]Iframe unwrap failed: {ex.Message}[/]");
            }
        }

        // Try custom resolver pipeline (handles wrapper hosts yt-dlp doesn't recognize).
        if (!settings.AudioOnly && string.IsNullOrEmpty(settings.Format))
        {
            var pipeline = new ResolverPipeline();
            var resolver = pipeline.Match(workUrl);
            if (resolver is not null)
            {
                AnsiConsole.MarkupLineInterpolated($"[grey]Resolver:[/] {resolver.Name}");
                ResolvedMedia? media = null;
                try { media = await pipeline.ResolveAsync(workUrl, ct); }
                catch (OperationCanceledException) { throw; }
                catch (Exception ex)
                {
                    AnsiConsole.MarkupLineInterpolated($"[yellow]Resolver failed: {ex.Message}[/]");
                }

                if (media is not null)
                {
                    var aria = await Aria2cDownloader.RunAsync(media, outDir, ct);
                    if (aria.ExitCode == 0)
                    {
                        AnsiConsole.MarkupLineInterpolated($"[green]Saved:[/] {aria.OutputPath}");
                        return 0;
                    }
                    AnsiConsole.MarkupLine("[yellow]Direct download failed — falling back to yt-dlp.[/]");
                    if (!string.IsNullOrWhiteSpace(aria.LastError))
                        AnsiConsole.WriteLine(aria.LastError);
                }
                else
                {
                    AnsiConsole.MarkupLine("[yellow]Resolver returned no media — falling back to yt-dlp.[/]");
                }
            }
        }

        var opts = new YtDlpOptions
        {
            Url = workUrl.ToString(),
            OutputDir = outDir,
            AudioOnly = settings.AudioOnly,
            FormatOverride = settings.Format,
            UseAria2 = !settings.NoAria2,
            ForceGeneric = false,
            ExtraArgs = YouTubeArgsInjector.ExtraArgs(workUrl.ToString(), useFallbackClients: false),
        };

        var result = await YtDlpRunner.RunAsync(opts, ct);

        if (result.ExitCode != 0)
        {
            if (YouTubeHost.IsYouTube(opts.Url) &&
                (YouTubeHost.Is403Error(result.LastError) || YouTubeHost.IsSignInError(result.LastError)))
            {
                AnsiConsole.MarkupLine("[yellow]YouTube 403 / sign-in — refreshing engine and retrying with fallback player_client chain...[/]");
                try { await ToolResolver.ForceUpdateYtDlpAsync(ct); }
                catch (OperationCanceledException) { throw; }
                catch (Exception ex)
                {
                    AnsiConsole.MarkupLineInterpolated($"[yellow]Engine refresh failed ({ex.Message}); retrying anyway.[/]");
                }
                result = await YtDlpRunner.RunAsync(opts with
                {
                    UseAria2 = false,
                    ExtraArgs = YouTubeArgsInjector.ExtraArgs(opts.Url, useFallbackClients: true),
                }, ct);
            }
            else if (result.LastError.Contains("Unsupported URL", StringComparison.OrdinalIgnoreCase))
            {
                AnsiConsole.MarkupLine("[yellow]Unsupported URL — retrying with generic extractor...[/]");
                result = await YtDlpRunner.RunAsync(opts with { ForceGeneric = true }, ct);
            }
            else if (opts.UseAria2 && YtDlpRunner.IsExternalDownloaderFailure(result.LastError))
            {
                AnsiConsole.MarkupLine("[yellow]aria2c failed — retrying with yt-dlp's native downloader...[/]");
                result = await YtDlpRunner.RunAsync(opts with { UseAria2 = false }, ct);
            }
        }

        if (result.ExitCode == 0)
        {
            AnsiConsole.MarkupLine("[green]Done.[/]");
            return 0;
        }

        AnsiConsole.MarkupLine("[red]Download failed.[/]");
        if (!string.IsNullOrWhiteSpace(result.LastError))
            AnsiConsole.WriteLine(result.LastError);
        return 2;
    }
}
