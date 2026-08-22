using SnagLite.Services;
using Spectre.Console;

namespace SnagLite.Commands;

/// Shared first-run guard: turns a failed tool fetch into an actionable
/// message and exit code instead of Spectre's default stack trace.
internal static class ToolSetup
{
    // Exit codes: 0 ok · 1 usage/config · 2 download failed · 3 tool setup · 130 cancelled.
    public const int ExitToolSetup = 3;

    /// Returns true when all tools are present; false (after printing why) when
    /// they could not be fetched. Cancellation propagates.
    public static async Task<bool> TryEnsureAllAsync(CancellationToken ct)
    {
        try
        {
            await ToolResolver.EnsureAllAsync(ct);
            return true;
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception ex) when (IsSetupFailure(ex))
        {
            AnsiConsole.MarkupLineInterpolated($"[red]Tool setup failed:[/] {ex.Message}");
            PrintHint();
            return false;
        }
    }

    public static bool IsSetupFailure(Exception ex) =>
        ex is HttpRequestException or IOException or ToolIntegrityException or InvalidOperationException or TaskCanceledException;

    public static void PrintHint() =>
        AnsiConsole.MarkupLineInterpolated(
            $"[grey]Check your network / proxy and retry. As a workaround, drop yt-dlp.exe, ffmpeg.exe and aria2c.exe into {Paths.BinDir} yourself (see README → Troubleshooting).[/]");
}
