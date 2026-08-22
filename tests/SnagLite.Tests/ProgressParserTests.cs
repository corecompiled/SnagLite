using SnagLite.Services;
using Xunit;

namespace SnagLite.Tests;

public class ProgressParserTests
{
    private sealed record Case(string Id, string Line, bool Parses, double? Percent, string? Total, string? Speed, string? Eta);
    private sealed record FixtureFile(string Description, List<Case> Cases);

    private static List<Case> All() => Fixtures.Load<FixtureFile>("progress-lines.json").Cases;

    public static IEnumerable<object[]> Ids() => All().Select(c => new object[] { c.Id });

    [Theory]
    [MemberData(nameof(Ids))]
    public void ParsesFixtureLine(string id)
    {
        var c = All().Single(x => x.Id == id);

        var ok = ProgressParser.TryParse(c.Line, out var tick);

        Assert.Equal(c.Parses, ok);
        if (!c.Parses) return;
        Assert.Equal(c.Percent!.Value, tick.Percent, precision: 3);
        Assert.Equal(c.Total, tick.Total);
        Assert.Equal(c.Speed, tick.Speed);
        Assert.Equal(c.Eta, tick.Eta);
    }
}
