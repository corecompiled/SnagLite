using SnagLite.Services.YouTube;
using Xunit;

namespace SnagLite.Tests;

public class YouTubeHostTests
{
    private sealed record ErrorCase(string Id, string Text, bool Is403, bool IsSignIn);
    private sealed record ErrorFile(string Description, List<ErrorCase> Cases);
    private sealed record HostCase(string Url, bool IsYouTube);
    private sealed record HostFile(string Description, List<HostCase> Cases);

    private static List<ErrorCase> Errors() => Fixtures.Load<ErrorFile>("youtube-errors.json").Cases;
    private static List<HostCase> Hosts() => Fixtures.Load<HostFile>("youtube-hosts.json").Cases;

    public static IEnumerable<object[]> ErrorIds() => Errors().Select(c => new object[] { c.Id });
    public static IEnumerable<object[]> HostUrls() => Hosts().Select(c => new object[] { c.Url });

    [Theory]
    [MemberData(nameof(ErrorIds))]
    public void ClassifiesErrorText(string id)
    {
        var c = Errors().Single(x => x.Id == id);

        Assert.Equal(c.Is403, YouTubeHost.Is403Error(c.Text));
        Assert.Equal(c.IsSignIn, YouTubeHost.IsSignInError(c.Text));
    }

    [Fact]
    public void NullTextIsNeitherError()
    {
        Assert.False(YouTubeHost.Is403Error(null));
        Assert.False(YouTubeHost.IsSignInError(null));
    }

    [Theory]
    [MemberData(nameof(HostUrls))]
    public void RecognisesYouTubeHosts(string url)
    {
        var c = Hosts().Single(x => x.Url == url);

        Assert.Equal(c.IsYouTube, YouTubeHost.IsYouTube(url));
    }
}
