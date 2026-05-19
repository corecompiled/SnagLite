namespace SnagLite.Services;

public static class Paths
{
    public static string AppData =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SnagLite");

    public static string BinDir => Path.Combine(AppData, "bin");

    public static string ConfigDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SnagLite");

    public static string ConfigFile => Path.Combine(ConfigDir, "config.json");

    public static string DefaultOutputDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Videos", "SnagLite");

    public static string YtDlp => Path.Combine(BinDir, "yt-dlp.exe");
    public static string Ffmpeg => Path.Combine(BinDir, "ffmpeg.exe");
    public static string Aria2c => Path.Combine(BinDir, "aria2c.exe");

    public static void EnsureDir(string path) => Directory.CreateDirectory(path);

    public static void MigrateLegacyDataDirs()
    {
        var lad = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var ad  = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        var up  = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

        // Legacy → previous → current. Each call is best-effort no-op when source missing or target exists.
        TryMove(Path.Combine(lad, "Snagger"), Path.Combine(lad, "Snag"));
        TryMove(Path.Combine(ad,  "Snagger"), Path.Combine(ad,  "Snag"));
        TryMove(Path.Combine(up,  "Videos", "Snagger"), Path.Combine(up, "Videos", "Snag"));

        TryMove(Path.Combine(lad, "Snag"), Path.Combine(lad, "SnagLite"));
        TryMove(Path.Combine(ad,  "Snag"), Path.Combine(ad,  "SnagLite"));
        TryMove(Path.Combine(up,  "Videos", "Snag"), Path.Combine(up, "Videos", "SnagLite"));
    }

    private static void TryMove(string oldPath, string newPath)
    {
        try
        {
            if (Directory.Exists(oldPath) && !Directory.Exists(newPath))
                Directory.Move(oldPath, newPath);
        }
        catch { }
    }
}
