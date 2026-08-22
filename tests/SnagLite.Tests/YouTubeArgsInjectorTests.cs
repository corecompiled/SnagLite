using SnagLite.Services.YouTube;
using Xunit;

namespace SnagLite.Tests;

public class YouTubeArgsInjectorTests
{
    private const string Yt = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    [Fact]
    public void NonYouTubeUrlGetsNoExtraArgs()
    {
        Assert.Empty(YouTubeArgsInjector.ExtraArgs("https://vimeo.com/123"));
    }

    [Fact]
    public void YouTubeUrlGetsUserAgentLanguageAndPrimaryClients()
    {
        var args = YouTubeArgsInjector.ExtraArgs(Yt).ToList();

        Assert.Equal(6, args.Count);
        Assert.Equal("--user-agent", args[0]);
        Assert.Contains("Mozilla/5.0", args[1]);
        Assert.Equal("--add-header", args[2]);
        Assert.StartsWith("Accept-Language:", args[3]);
        Assert.Equal("--extractor-args", args[4]);
        Assert.Equal("youtube:" + YouTubeArgsInjector.Primary, args[5]);
    }

    [Fact]
    public void FallbackFlagSwitchesPlayerClientChain()
    {
        var args = YouTubeArgsInjector.ExtraArgs(Yt, useFallbackClients: true).ToList();

        Assert.Equal("youtube:" + YouTubeArgsInjector.Fallback, args[^1]);
        Assert.NotEqual(YouTubeArgsInjector.Primary, YouTubeArgsInjector.Fallback);
    }
}
