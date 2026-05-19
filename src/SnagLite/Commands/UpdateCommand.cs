using SnagLite.Services;
using Spectre.Console.Cli;

namespace SnagLite.Commands;

public sealed class UpdateCommand : AsyncCommand
{
    protected override async Task<int> ExecuteAsync(CommandContext context, CancellationToken cancellationToken)
    {
        await ToolResolver.EnsureAllAsync(cancellationToken);
        await ToolResolver.ForceUpdateYtDlpAsync(cancellationToken);
        return 0;
    }
}
