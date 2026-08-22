using System.Text.RegularExpressions;
using SnagLite.Services;
using Xunit;

namespace SnagLite.Tests;

public class AppVersionTests
{
    [Fact]
    public void ShortVersionIsSemverFromCsproj()
    {
        // <Version> in SnagLite.csproj is the single source of truth; the
        // placeholder "0.0.0" means the attribute was not stamped.
        Assert.Matches(new Regex(@"^\d+\.\d+\.\d+$"), AppVersion.Short);
        Assert.NotEqual("0.0.0", AppVersion.Short);
        Assert.StartsWith(AppVersion.Short, AppVersion.Informational);
    }
}
