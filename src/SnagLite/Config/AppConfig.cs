using System.Text.Json;
using SnagLite.Services;

namespace SnagLite.Config;

public sealed class AppConfig
{
    public string? DefaultOutputPath { get; set; }

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static AppConfig Load()
    {
        try
        {
            if (File.Exists(Paths.ConfigFile))
            {
                var text = File.ReadAllText(Paths.ConfigFile);
                return JsonSerializer.Deserialize<AppConfig>(text) ?? new AppConfig();
            }
        }
        catch
        {
            // Corrupt config — start fresh rather than crash a download.
        }
        return new AppConfig();
    }

    public void Save()
    {
        Paths.EnsureDir(Paths.ConfigDir);
        File.WriteAllText(Paths.ConfigFile, JsonSerializer.Serialize(this, Json));
    }

    public string ResolveOutputDir(string? overrideDir)
    {
        var dir = overrideDir
            ?? (string.IsNullOrWhiteSpace(DefaultOutputPath) ? Paths.DefaultOutputDir : DefaultOutputPath);
        Paths.EnsureDir(dir);
        return dir;
    }
}
