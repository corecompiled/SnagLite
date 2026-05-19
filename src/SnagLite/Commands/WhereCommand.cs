using SnagLite.Config;
using SnagLite.Services;
using Spectre.Console;
using Spectre.Console.Cli;

namespace SnagLite.Commands;

public sealed class WhereCommand : Command
{
    protected override int Execute(CommandContext context, CancellationToken cancellationToken)
    {
        var cfg = AppConfig.Load();
        var table = new Table().AddColumn("Key").AddColumn("Path");
        table.AddRow("bin dir", Paths.BinDir);
        table.AddRow("yt-dlp", Exists(Paths.YtDlp));
        table.AddRow("ffmpeg", Exists(Paths.Ffmpeg));
        table.AddRow("aria2c", Exists(Paths.Aria2c));
        table.AddRow("config", Paths.ConfigFile);
        table.AddRow("default output",
            string.IsNullOrWhiteSpace(cfg.DefaultOutputPath) ? Paths.DefaultOutputDir : cfg.DefaultOutputPath!);
        AnsiConsole.Write(table);
        return 0;
    }

    private static string Exists(string p) => File.Exists(p) ? p : $"{p} [red](missing)[/]";
}
