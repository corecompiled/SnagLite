using System.Text.Json;

namespace SnagLite.Services.YouTube;

/// Weekly background refresh of yt-dlp, split in two so it never races the
/// download it is meant to support: <see cref="Start"/> prefetches a verified
/// yt-dlp.exe.new while the run proceeds, and <see cref="EngineUpdateHandle.CommitAsync"/>
/// swaps it in after the child process has exited. The command must call
/// CommitAsync on every exit path (it is cheap when nothing was started).
///
/// Stamping: LastChecked only advances on a successful swap. A failure writes
/// LastFailed and backs off for 24 h — without that, a machine where the swap
/// always fails (or the network is blocked) would re-download ~18 MB on every
/// run; with the old "stamp regardless" logic it silently hid for a week.
public static class EngineUpdateChecker
{
    private static readonly TimeSpan UpdateInterval = TimeSpan.FromDays(7);
    private static readonly TimeSpan FailureBackoff = TimeSpan.FromHours(24);

    private static string StateFile =>
        Path.Combine(Paths.ConfigDir, "engine-update.json");

    internal sealed class State
    {
        public DateTime LastChecked { get; set; }
        public DateTime LastFailed { get; set; }
    }

    internal static bool IsDue(State state, DateTime nowUtc)
    {
        // Timestamps in the future mean a clock jump or a corrupt file; never
        // let them suppress the check indefinitely.
        if (state.LastChecked > nowUtc || state.LastFailed > nowUtc) return true;
        if (nowUtc - state.LastChecked < UpdateInterval) return false;
        if (nowUtc - state.LastFailed < FailureBackoff) return false;
        return true;
    }

    /// Begins a background prefetch when one is due. Never throws.
    public static EngineUpdateHandle Start(CancellationToken ct)
    {
        State state;
        try
        {
            Paths.EnsureDir(Paths.ConfigDir);
            state = LoadState();
        }
        catch
        {
            state = new State();
        }
        if (!IsDue(state, DateTime.UtcNow)) return EngineUpdateHandle.Skipped;
        return new EngineUpdateHandle(Task.Run(() => ToolResolver.PrefetchYtDlpAsync(ct), ct));
    }

    internal static void RecordSuccess()
    {
        var s = LoadStateSafe();
        s.LastChecked = DateTime.UtcNow;
        s.LastFailed = default;
        SaveState(s);
    }

    internal static void RecordFailure()
    {
        var s = LoadStateSafe();
        s.LastFailed = DateTime.UtcNow;
        SaveState(s);
    }

    private static State LoadStateSafe()
    {
        try { return LoadState(); } catch { return new State(); }
    }

    private static State LoadState()
    {
        if (!File.Exists(StateFile)) return new State();
        var json = File.ReadAllText(StateFile);
        return JsonSerializer.Deserialize<State>(json) ?? new State();
    }

    private static void SaveState(State state)
    {
        try
        {
            Paths.EnsureDir(Paths.ConfigDir);
            File.WriteAllText(StateFile, JsonSerializer.Serialize(state));
        }
        catch { }
    }
}

public enum EngineUpdateOutcome { Skipped, Updated, Failed }

public readonly record struct EngineUpdateResult(EngineUpdateOutcome Outcome, string? Message = null);

public sealed class EngineUpdateHandle
{
    public static readonly EngineUpdateHandle Skipped = new(null);

    private readonly Task<string>? _prefetch;

    internal EngineUpdateHandle(Task<string>? prefetch) => _prefetch = prefetch;

    public bool IsActive => _prefetch is not null;

    /// Waits up to <paramref name="wait"/> for the prefetch, then swaps the new
    /// binary in. Never throws; a failure (including a timeout — the fetch is
    /// abandoned and the side file left for the next attempt to overwrite) is
    /// reported in the result and backs the next check off by 24 h.
    public async Task<EngineUpdateResult> CommitAsync(TimeSpan wait)
    {
        if (_prefetch is null) return new(EngineUpdateOutcome.Skipped);
        try
        {
            var tmp = await _prefetch.WaitAsync(wait);
            ToolResolver.CommitYtDlp(tmp);
            EngineUpdateChecker.RecordSuccess();
            return new(EngineUpdateOutcome.Updated);
        }
        catch (TimeoutException)
        {
            EngineUpdateChecker.RecordFailure();
            return new(EngineUpdateOutcome.Failed, $"still downloading after {wait.TotalSeconds:0}s");
        }
        catch (Exception ex)
        {
            EngineUpdateChecker.RecordFailure();
            return new(EngineUpdateOutcome.Failed, ex.Message);
        }
    }
}
