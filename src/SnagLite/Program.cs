using SnagLite.Commands;
using SnagLite.Services;
using Spectre.Console.Cli;

Paths.MigrateLegacyDataDirs();

var app = new CommandApp<DownloadCommand>();
app.Configure(config =>
{
    config.SetApplicationName("snaglite");
    config.SetApplicationVersion("2.0.0");

    config.AddCommand<DownloadCommand>("get")
        .WithDescription("Download a video URL (also the default if you just pass a URL).")
        .WithExample(["https://www.youtube.com/watch?v=dQw4w9WgXcQ"])
        .WithExample(["https://www.youtube.com/watch?v=dQw4w9WgXcQ", "-o", "D:\\Stuff"]);

    config.AddCommand<UpdateCommand>("update")
        .WithDescription("Re-download yt-dlp to the latest release.");

    config.AddCommand<WhereCommand>("where")
        .WithDescription("Show resolved tool + output paths.");

    config.AddBranch("config", cfg =>
    {
        cfg.SetDescription("Persisted user settings.");
        cfg.AddCommand<ConfigSetOutputCommand>("set-output")
            .WithDescription("Set persistent default output directory.");
        cfg.AddCommand<ConfigShowCommand>("show")
            .WithDescription("Print current config.");
    });
});

return await app.RunAsync(args);
