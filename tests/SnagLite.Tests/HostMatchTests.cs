using SnagLite.Services;
using Xunit;

namespace SnagLite.Tests;

public class HostMatchTests
{
    private sealed record Case(string Host, bool Match);
    private sealed record SuffixBlock(List<string> Candidates, List<Case> Cases);
    private sealed record FragmentBlock(List<string> Fragments, List<Case> Cases);
    private sealed record FixtureFile(string Description, SuffixBlock ExactOrSubdomain, FragmentBlock LabelFragments);

    private static FixtureFile F() => Fixtures.Load<FixtureFile>("host-suffix-cases.json");

    public static IEnumerable<object[]> SuffixHosts() => F().ExactOrSubdomain.Cases.Select(c => new object[] { c.Host });
    public static IEnumerable<object[]> FragmentHosts() => F().LabelFragments.Cases.Select(c => new object[] { c.Host });

    [Theory]
    [MemberData(nameof(SuffixHosts))]
    public void ExactOrSubdomain(string host)
    {
        var block = F().ExactOrSubdomain;
        var c = block.Cases.Single(x => x.Host == host);

        Assert.Equal(c.Match, HostMatch.IsHostOrSubdomain(host, block.Candidates));
    }

    [Theory]
    [MemberData(nameof(FragmentHosts))]
    public void LabelFragments(string host)
    {
        var block = F().LabelFragments;
        var c = block.Cases.Single(x => x.Host == host);

        Assert.Equal(c.Match, HostMatch.MatchesLabelFragment(host, block.Fragments));
    }
}
