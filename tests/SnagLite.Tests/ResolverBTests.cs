using SnagLite.Services.Resolvers;
using Xunit;

namespace SnagLite.Tests;

public class ResolverBTests
{
    private sealed record DecodeCase(string Id, string Encoded, string Decoded);
    private sealed record Rot13Case(string Input, string Expected);
    private sealed record FixtureFile(string Description, List<DecodeCase> Cases, List<Rot13Case> Rot13);

    private static FixtureFile F() => Fixtures.Load<FixtureFile>("resolver-b-decode.json");

    public static IEnumerable<object[]> DecodeIds() => F().Cases.Select(c => new object[] { c.Id });
    public static IEnumerable<object[]> Rot13Inputs() => F().Rot13.Select(c => new object[] { c.Input });

    [Theory]
    [MemberData(nameof(DecodeIds))]
    public void DecodesPayload(string id)
    {
        var c = F().Cases.Single(x => x.Id == id);

        Assert.Equal(c.Decoded, ResolverB.Decode(c.Encoded));
    }

    [Theory]
    [MemberData(nameof(Rot13Inputs))]
    public void Rot13RotatesLettersOnly(string input)
    {
        var c = F().Rot13.Single(x => x.Input == input);

        Assert.Equal(c.Expected, ResolverB.Rot13(input));
    }

    [Fact]
    public void Rot13IsAnInvolution()
    {
        const string s = "The quick brown fox 123";
        Assert.Equal(s, ResolverB.Rot13(ResolverB.Rot13(s)));
    }
}
