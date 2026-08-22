using SnagLite.Services;
using SnagLite.Services.Resolvers;
using Xunit;

namespace SnagLite.Tests;

public class Aria2cDownloaderTests
{
    private sealed record Case(string Id, string Line, bool Parses, double? Percent, string? Speed, string? Eta);
    private sealed record FixtureFile(string Description, List<Case> Cases);

    private static List<Case> All() => Fixtures.Load<FixtureFile>("aria2-lines.json").Cases;
    public static IEnumerable<object[]> Ids() => All().Select(c => new object[] { c.Id });

    [Theory]
    [MemberData(nameof(Ids))]
    public void ParsesProgressLine(string id)
    {
        var c = All().Single(x => x.Id == id);

        var ok = Aria2cDownloader.TryParseProgressLine(c.Line, out var tick);

        Assert.Equal(c.Parses, ok);
        if (!c.Parses) return;
        Assert.Equal(c.Percent!.Value, tick.Percent, precision: 3);
        Assert.Equal(c.Speed, tick.Speed);
        Assert.Equal(c.Eta, tick.Eta);
    }

    private static readonly ResolvedMedia Media = new(
        DownloadUrl: "https://cdn.example/v/abc.mp4?token=x",
        Referer: "https://wrapper.example/",
        SuggestedName: "Sample.mp4",
        UserAgent: "UA/1.0");

    [Fact]
    public void UrlIsLastArgumentAfterEndOfOptionsMarker()
    {
        var args = Aria2cDownloader.BuildArgs(Media, @"C:\out");

        Assert.Equal(Media.DownloadUrl, args[^1]);
        Assert.Equal("--", args[^2]);
    }

    [Fact]
    public void OptionValuedUrlCannotBecomeAnOption()
    {
        // A hostile wrapper page could hand back an aria2 option as the "URL".
        // With the end-of-options marker in place it is passed as a bare URI
        // (and aria2c will simply fail to fetch it) instead of being honoured.
        var hostile = Media with { DownloadUrl = "--on-download-complete=calc.exe" };

        var args = Aria2cDownloader.BuildArgs(hostile, @"C:\out");

        Assert.Equal("--", args[^2]);
        Assert.Equal(hostile.DownloadUrl, args[^1]);
    }

    [Fact]
    public void PassesOutputDirNameRefererAndUserAgent()
    {
        var args = Aria2cDownloader.BuildArgs(Media, @"C:\out");

        Assert.Contains(args, a => a == "-d");
        Assert.Equal(@"C:\out", args[args.IndexOf("-d") + 1]);
        Assert.Equal("Sample.mp4", args[args.IndexOf("-o") + 1]);
        Assert.Contains("--referer=https://wrapper.example/", args);
        Assert.Contains("--user-agent=UA/1.0", args);
    }
}
