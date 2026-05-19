using System.ComponentModel;
using SnagLite.Config;
using SnagLite.Services;
using SnagLite.Services.Resolvers;
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

    protected override async Task<int> ExecuteAsync(CommandContext context, Settings settings, CancellationToken cancellationToken)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

        await ToolResolver.EnsureAllAsync(cts.Token);

        var cfg = AppConfig.Load();
        var outDir = cfg.ResolveOutputDir(settings.OutputDir);
        AnsiConsole.MarkupLineInterpolated($"[grey]Output:[/] {outDir}");

        var workUrl = new Uri(settings.Url);

        // Some sites are listing pages that embed a third-party player via <iframe>.
        // Unwrap upfront so the resolver/yt-dlp engines see the real provider host
        // instead of the listing page.
        if (IframeUnwrapper.ShouldUnwrap(workUrl))
        {
            try
            {
                var inner = await IframeUnwrapper.UnwrapAsync(workUrl, cts.Token);
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
                try { media = await pipeline.ResolveAsync(workUrl, cts.Token); }
                catch (Exception ex)
                {
                    AnsiConsole.MarkupLineInterpolated($"[yellow]Resolver failed: {ex.Message}[/]");
                }

                if (media is not null)
                {
                    var aria = await Aria2cDownloader.RunAsync(media, outDir, cts.Token);
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
        };

        var result = await YtDlpRunner.RunAsync(opts, cts.Token);

        if (result.ExitCode != 0 &&
            result.LastError.Contains("Unsupported URL", StringComparison.OrdinalIgnoreCase))
        {
            AnsiConsole.MarkupLine("[yellow]Unsupported URL — retrying with generic extractor...[/]");
            result = await YtDlpRunner.RunAsync(opts with { ForceGeneric = true }, cts.Token);
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
