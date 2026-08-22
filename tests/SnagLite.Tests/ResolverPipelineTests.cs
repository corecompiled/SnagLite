using SnagLite.Services.Resolvers;
using Xunit;

namespace SnagLite.Tests;

public class ResolverPipelineTests
{
    private sealed class FakeResolver(ResolvedMedia? result) : IResolver
    {
        public string Name => "fake";
        public bool Matches(Uri url) => url.Host == "fake.example";
        public Task<ResolvedMedia?> ResolveAsync(Uri url, CancellationToken ct) => Task.FromResult(result);
    }

    private static readonly Uri Url = new("https://fake.example/e/abc");

    private static ResolvedMedia MediaWith(string downloadUrl) =>
        new(downloadUrl, "https://fake.example/", "x.mp4", "UA");

    [Fact]
    public async Task ReturnsMediaWithHttpsDownloadUrl()
    {
        var media = MediaWith("https://cdn.example/v/abc.mp4");
        var pipeline = new ResolverPipeline([new FakeResolver(media)]);

        Assert.Same(media, await pipeline.ResolveAsync(Url, CancellationToken.None));
    }

    [Fact]
    public async Task ReturnsMediaWithHttpDownloadUrl()
    {
        var media = MediaWith("http://cdn.example/v/abc.mp4");
        var pipeline = new ResolverPipeline([new FakeResolver(media)]);

        Assert.Same(media, await pipeline.ResolveAsync(Url, CancellationToken.None));
    }

    [Theory]
    [InlineData("--on-download-complete=calc.exe")]
    [InlineData("file:///C:/Windows/win.ini")]
    [InlineData("javascript:alert(1)")]
    [InlineData("ftp://cdn.example/v.mp4")]
    [InlineData("/relative/v.mp4")]
    [InlineData("")]
    public async Task RejectsNonHttpDownloadUrl(string downloadUrl)
    {
        var pipeline = new ResolverPipeline([new FakeResolver(MediaWith(downloadUrl))]);

        Assert.Null(await pipeline.ResolveAsync(Url, CancellationToken.None));
    }

    [Fact]
    public async Task UnmatchedUrlReturnsNull()
    {
        var pipeline = new ResolverPipeline([new FakeResolver(MediaWith("https://cdn.example/v.mp4"))]);

        Assert.Null(pipeline.Match(new Uri("https://other.example/")));
        Assert.Null(await pipeline.ResolveAsync(new Uri("https://other.example/"), CancellationToken.None));
    }
}
