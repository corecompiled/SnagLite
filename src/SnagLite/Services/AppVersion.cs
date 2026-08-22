using System.Reflection;

namespace SnagLite.Services;

/// The one source of truth for the CLI's version is &lt;Version&gt; in SnagLite.csproj;
/// release.yml asserts the release tag equals "v" + that value.
internal static class AppVersion
{
    /// e.g. "0.2.0+a1b2c3d" (SourceLink appends the commit).
    public static string Informational { get; } =
        typeof(AppVersion).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
        ?? "0.0.0";

    /// e.g. "0.2.0".
    public static string Short { get; } = Informational.Split('+', 2)[0];
}
