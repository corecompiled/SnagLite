using SnagLite.Services;
using Xunit;

namespace SnagLite.Tests;

public class YtDlpRunnerErrorsTests
{
    [Theory]
    [InlineData("ERROR: aria2c exited with code 1")]
    [InlineData("WARNING: x\nERROR: aria2c exited with code 22\n")]
    [InlineData("error: ARIA2C exited with code 1")]
    public void RecognisesExternalDownloaderFailure(string stderr)
    {
        Assert.True(YtDlpRunner.IsExternalDownloaderFailure(stderr));
    }

    [Theory]
    [InlineData("ERROR: [youtube] abc: Video unavailable")]
    [InlineData("ERROR: Unsupported URL: https://x.example/")]
    [InlineData("HTTP Error 403: Forbidden")]
    [InlineData("")]
    public void OtherErrorsAreNotExternalDownloaderFailures(string stderr)
    {
        Assert.False(YtDlpRunner.IsExternalDownloaderFailure(stderr));
    }
}
