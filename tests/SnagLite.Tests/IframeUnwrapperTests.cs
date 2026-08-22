using SnagLite.Services.Resolvers;
using Xunit;

namespace SnagLite.Tests;

public class IframeUnwrapperTests
{
    [Theory]
    [InlineData("<iframe src=\"https://player.example/e/abc\"></iframe>", "https://player.example/e/abc")]
    [InlineData("<IFRAME allowfullscreen SRC='https://player.example/e/abc'>", "https://player.example/e/abc")]
    [InlineData("<iframe src=\"//player.example/e/abc\">", "https://player.example/e/abc")]
    [InlineData("<iframe src=\"http://player.example/e/abc\">", "http://player.example/e/abc")]
    [InlineData("<p>x</p><iframe width=1 src=\" https://player.example/e/abc \">", "https://player.example/e/abc")]
    public void ReturnsFirstHttpIframeSrc(string html, string expected)
    {
        Assert.Equal(new Uri(expected), IframeUnwrapper.ParseIframeSrc(html));
    }

    [Theory]
    [InlineData("<iframe src=\"javascript:alert(1)\">")]
    [InlineData("<iframe src=\"file:///C:/Windows/win.ini\">")]
    [InlineData("<iframe src=\"data:text/html,hi\">")]
    [InlineData("<iframe src=\"ftp://player.example/e/abc\">")]
    [InlineData("<iframe src=\"/relative/path\">")]
    [InlineData("<iframe src=\"\">")]
    [InlineData("<div>no iframe here</div>")]
    [InlineData("")]
    public void RejectsNonHttpOrMissingSrc(string html)
    {
        Assert.Null(IframeUnwrapper.ParseIframeSrc(html));
    }
}
