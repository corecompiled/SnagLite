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

        var cfg = AppConfig.Load();
        cfg.DefaultOutputPath = System.IO.Path.GetFullPath(settings.Path);
        cfg.Save();
        Paths.EnsureDir(cfg.DefaultOutputPath);
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
