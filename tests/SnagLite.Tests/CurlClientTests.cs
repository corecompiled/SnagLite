using SnagLite.Services;
using Xunit;

namespace SnagLite.Tests;

public class CurlClientTests
{
    [Fact]
    public void UrlIsLastAfterEndOfOptionsMarker()
    {
        // Redirect targets and pass_md5 paths come from remote pages; a value
        // starting with "-" must reach curl as a URL, never as an option.
        var args = CurlClient.BuildArgs("-o C:/evil", referer: null, extraHeaders: null, userAgent: "UA");

        Assert.Equal("-o C:/evil", args[^1]);
        Assert.Equal("--", args[^2]);
    }

    [Fact]
    public void RefererAndExtraHeadersArePassed()
    {
        var args = CurlClient.BuildArgs(
            "https://x.example/",
            referer: "https://r.example/",
            extraHeaders: new Dictionary<string, string> { ["X-Requested-With"] = "XMLHttpRequest" },
            userAgent: "UA/1");

        Assert.Equal("https://r.example/", args[args.IndexOf("-e") + 1]);
        Assert.Equal("UA/1", args[args.IndexOf("-A") + 1]);
        Assert.Contains("X-Requested-With: XMLHttpRequest", args);
        Assert.Contains(args, a => a.StartsWith("Accept:"));
        Assert.Contains(args, a => a.StartsWith("Accept-Language:"));
    }

    [Fact]
    public void NoRefererMeansNoRefererFlag()
    {
        var args = CurlClient.BuildArgs("https://x.example/", referer: null, extraHeaders: null, userAgent: "UA");

        Assert.DoesNotContain("-e", args);
    }

    [Fact]
    public void WritesHttpCodeAndEffectiveUrlTrailer()
    {
        var args = CurlClient.BuildArgs("https://x.example/", referer: null, extraHeaders: null, userAgent: "UA");

        var w = args[args.IndexOf("-w") + 1];
        Assert.Contains("%{http_code}", w);
        Assert.Contains("%{url_effective}", w);
    }

    [Theory]
    [InlineData("<html>body</html>\n__SNAG_HTTP_META__:200|https://final.example/", 200, "https://final.example/", "<html>body</html>")]
    [InlineData("\n__SNAG_HTTP_META__:404|https://x.example/", 404, "https://x.example/", "")]
    [InlineData("no trailer at all", 0, "https://req.example/", "no trailer at all")]
    public void ParsesTrailer(string stdout, int code, string effective, string body)
    {
        var r = CurlClient.ParseResponse("https://req.example/", stdout);

        Assert.Equal(code, r.StatusCode);
        Assert.Equal(effective, r.EffectiveUrl);
        Assert.Equal(body, r.Body);
    }
}
