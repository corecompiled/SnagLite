using SnagLite.Services;
using Xunit;

namespace SnagLite.Tests;

public class YtDlpRunnerArgsTests
{
    private static YtDlpOptions Opts(
        string url = "https://video.example/watch?v=1",
        bool audioOnly = false,
        string? format = null,
        bool useAria2 = true,
        bool forceGeneric = false,
        IEnumerable<string>? extra = null) =>
        new()
        {
            Url = url,
            OutputDir = @"C:\out",
            AudioOnly = audioOnly,
            FormatOverride = format,
            UseAria2 = useAria2,
            ForceGeneric = forceGeneric,
            ExtraArgs = extra,
        };

    [Fact]
    public void UrlIsTheFirstArgument()
    {
        Assert.Equal("https://video.example/watch?v=1", YtDlpRunner.BuildArgs(Opts())[0]);
    }

    [Fact]
    public void SingleVideoOnlyAndResumablePartials()
    {
        var args = YtDlpRunner.BuildArgs(Opts());

        // A pasted playlist link must not silently start a multi-GB job —
        // playlists are documented as out of scope on the CLI.
        Assert.Contains("--no-playlist", args);
        // Partial downloads stay as .part so a cancelled run leaves no
        // truncated .mp4 that looks complete, and a re-run can resume.
        Assert.DoesNotContain("--no-part", args);
        Assert.Contains("--continue", args);
    }

    [Fact]
    public void DefaultFormatIsBestMp4Merge()
    {
        var args = YtDlpRunner.BuildArgs(Opts());

        Assert.Equal("bv*+ba/b", args[args.IndexOf("-f") + 1]);
        Assert.Equal("mp4", args[args.IndexOf("--merge-output-format") + 1]);
        Assert.Contains("--restrict-filenames", args);
        Assert.Equal("%(title)s [%(id)s].%(ext)s", args[args.IndexOf("-o") + 1]);
        Assert.Equal(@"C:\out", args[args.IndexOf("-P") + 1]);
    }

    [Fact]
    public void AudioOnlyExtractsM4a()
    {
        var args = YtDlpRunner.BuildArgs(Opts(audioOnly: true));

        Assert.Equal("ba/b", args[args.IndexOf("-f") + 1]);
        Assert.Contains("-x", args);
        Assert.Equal("m4a", args[args.IndexOf("--audio-format") + 1]);
        Assert.DoesNotContain("--merge-output-format", args);
    }

    [Fact]
    public void FormatOverrideWinsOverAudioOnly()
    {
        var args = YtDlpRunner.BuildArgs(Opts(audioOnly: true, format: "best[height<=720]"));

        Assert.Equal("best[height<=720]", args[args.IndexOf("-f") + 1]);
        Assert.DoesNotContain("-x", args);
        Assert.DoesNotContain("--merge-output-format", args);
    }

    [Fact]
    public void Aria2IsTheDownloaderByDefaultAndOptional()
    {
        Assert.Contains("--downloader", YtDlpRunner.BuildArgs(Opts()));
        Assert.DoesNotContain("--downloader", YtDlpRunner.BuildArgs(Opts(useAria2: false)));
    }

    [Fact]
    public void ForceGenericAddsExtractorAndRefererFromUrlOrigin()
    {
        var args = YtDlpRunner.BuildArgs(Opts(url: "https://video.example/a/b?c=d", forceGeneric: true));

        Assert.Contains("--force-generic-extractor", args);
        Assert.Equal("Referer:https://video.example/", args[args.IndexOf("--add-header") + 1]);
    }

    [Fact]
    public void ExtraArgsAreAppendedLast()
    {
        var args = YtDlpRunner.BuildArgs(Opts(extra: ["--extractor-args", "youtube:x"]));

        Assert.Equal("--extractor-args", args[^2]);
        Assert.Equal("youtube:x", args[^1]);
    }
}
