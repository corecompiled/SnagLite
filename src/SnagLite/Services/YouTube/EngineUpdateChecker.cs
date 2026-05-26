using System.Text.Json;

namespace SnagLite.Services.YouTube;

public static class EngineUpdateChecker
{
    private static readonly TimeSpan UpdateInterval = TimeSpan.FromDays(7);

    private static string StateFile =>
        Path.Combine(Paths.ConfigDir, "engine-update.json");

    private sealed class State
    {
        public DateTime LastChecked { get; set; }
    }

    public static Task MaybeUpdateAsync(CancellationToken ct = default)
    {
        return Task.Run(async () =>
        {
            try
            {
                Paths.EnsureDir(Paths.ConfigDir);
                var state = LoadState();
                if (DateTime.UtcNow - state.LastChecked < UpdateInterval) return;

                try { await ToolResolver.SilentRefreshYtDlpAsync(ct); }
                catch { }

                state.LastChecked = DateTime.UtcNow;
                SaveState(state);
            }
            catch
            {
            }
        }, ct);
    }

    private static State LoadState()
    {
        try
        {
            if (!File.Exists(StateFile)) return new State();
            var json = File.ReadAllText(StateFile);
            return JsonSerializer.Deserialize<State>(json) ?? new State();
        }
        catch
        {
            return new State();
        }
    }

    private static void SaveState(State state)
    {
        try
        {
            var json = JsonSerializer.Serialize(state);
            File.WriteAllText(StateFile, json);
        }
        catch { }
    }
}
