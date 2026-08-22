using System.ComponentModel;
using SnagLite.Config;
using SnagLite.Services;
using Spectre.Console;
using Spectre.Console.Cli;

namespace SnagLite.Commands;

public sealed class ConfigSetOutputCommand : Command<ConfigSetOutputCommand.Settings>
{
    public sealed class Settings : CommandSettings
    {
        [Description("New default output directory.")]
        [CommandArgument(0, "<PATH>")]
        public string Path { get; init; } = "";
    }

    protected override int Execute(CommandContext context,Settings settings, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(settings.Path))
        {
            AnsiConsole.MarkupLine("[red]Path required.[/]");
            return 1;
        }

        // Validate (and create) the directory before anything is persisted; a
        // saved-but-unusable path would break every later `snaglite <url>`.
        string full;
        try
        {
            full = System.IO.Path.GetFullPath(settings.Path);
            Paths.EnsureDir(full);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            AnsiConsole.MarkupLineInterpolated($"[red]Cannot use that directory:[/] {ex.Message}");
            return 1;
        }

        var cfg = AppConfig.Load();
        cfg.DefaultOutputPath = full;
        cfg.Save();
        AnsiConsole.MarkupLineInterpolated($"[green]✓[/] default output → {cfg.DefaultOutputPath}");
        return 0;
    }
}

public sealed class ConfigShowCommand : Command
{
    protected override int Execute(CommandContext context,CancellationToken cancellationToken)
    {
        var cfg = AppConfig.Load();
        AnsiConsole.MarkupLineInterpolated($"config file: {Paths.ConfigFile}");
        AnsiConsole.MarkupLineInterpolated(
            $"default output: {(string.IsNullOrWhiteSpace(cfg.DefaultOutputPath) ? Paths.DefaultOutputDir + " (built-in)" : cfg.DefaultOutputPath!)}");
        return 0;
    }
}
