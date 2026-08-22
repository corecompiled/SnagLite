using SnagLite.Services.Resolvers;
using Xunit;

namespace SnagLite.Tests;

public class ResolverCTests
{
    private sealed record UnpackCase(string Id, string P, int A, int C, string K, string Expected);
    private sealed record UnescapeCase(string Input, string Expected);
    private sealed record FixtureFile(string Description, List<UnpackCase> Unpack, List<UnescapeCase> UnescapeJs);

    private static FixtureFile F() => Fixtures.Load<FixtureFile>("packer-cases.json");

    public static IEnumerable<object[]> UnpackIds() => F().Unpack.Select(c => new object[] { c.Id });
    public static IEnumerable<object[]> UnescapeInputs() => F().UnescapeJs.Select(c => new object[] { c.Input });

    [Theory]
    [MemberData(nameof(UnpackIds))]
    public void UnpacksSinglePass(string id)
    {
        var c = F().Unpack.Single(x => x.Id == id);

        var result = ResolverC.Unpack(c.P, c.A, c.C, c.K.Split('|'));

        Assert.Equal(c.Expected, result);
    }

    [Theory]
    [MemberData(nameof(UnescapeInputs))]
    public void UnescapesJsStringLiterals(string input)
    {
        var c = F().UnescapeJs.Single(x => x.Input == input);

        Assert.Equal(c.Expected, ResolverC.UnescapeJsString(input));
    }

    [Theory]
    [InlineData(0, 62, "0")]
    [InlineData(9, 62, "9")]
    [InlineData(10, 62, "a")]
    [InlineData(35, 62, "z")]
    [InlineData(36, 62, "A")]
    [InlineData(61, 62, "Z")]
    [InlineData(62, 62, "10")]
    [InlineData(10, 10, "10")]
    [InlineData(36, 36, "10")]
    public void ToBaseMatchesPackerAlphabet(int n, int radix, string expected)
    {
        Assert.Equal(expected, ResolverC.ToBase(n, radix));
    }

    [Theory]
    [InlineData(1)]
    [InlineData(63)]
    public void UnpackRejectsRadixOutsidePackerRange(int radix)
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => ResolverC.Unpack("0", radix, 1, ["x"]));
    }
}
