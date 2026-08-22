using SnagLite.Services;
using Xunit;

namespace SnagLite.Tests;

public class FileNamesTests
{
    private sealed record SanitizeCase(string Id, string Input, string Expected);
    private sealed record StripCase(string Input, string Expected);
    private sealed record FixtureFile(string Description, List<SanitizeCase> Sanitize, List<StripCase> StripExt);

    private static FixtureFile F() => Fixtures.Load<FixtureFile>("filenames.json");

    public static IEnumerable<object[]> SanitizeIds() => F().Sanitize.Select(c => new object[] { c.Id });
    public static IEnumerable<object[]> StripInputs() => F().StripExt.Select(c => new object[] { c.Input });

    [Theory]
    [MemberData(nameof(SanitizeIds))]
    public void Sanitize(string id)
    {
        var c = F().Sanitize.Single(x => x.Id == id);

        Assert.Equal(c.Expected, FileNames.Sanitize(c.Input));
    }

    [Theory]
    [MemberData(nameof(StripInputs))]
    public void StripExt(string input)
    {
        var c = F().StripExt.Single(x => x.Input == input);

        Assert.Equal(c.Expected, FileNames.StripExt(input));
    }
}
