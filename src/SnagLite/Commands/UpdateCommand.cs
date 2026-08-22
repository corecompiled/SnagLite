using SnagLite.Services;
using Spectre.Console;
using Spectre.Console.Cli;

namespace SnagLite.Commands;

public sealed class UpdateCommand : AsyncCommand
{
    protected override async Task<int> ExecuteAsync(CommandContext context, CancellationToken ct)
    {
        try
        {
            if (!await ToolSetup.TryEnsureAllAsync(ct)) return ToolSetup.ExitToolSetup;
            await ToolResolver.ForceUpdateYtDlpAsync(ct);
            return 0;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            AnsiConsole.MarkupLine("[yellow]Cancelled.[/]");
            return DownloadCommand.ExitCancelled;
        }
        catch (Exception ex) when (ToolSetup.IsSetupFailure(ex))
        {
            AnsiConsole.MarkupLineInterpolated($"[red]yt-dlp update failed:[/] {ex.Message}");
            ToolSetup.PrintHint();
            return ToolSetup.ExitToolSetup;
        }
    }
}
