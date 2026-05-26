using System.Text.RegularExpressions;

namespace SnagLite.Services.YouTube;

public static class YouTubeHost
{
    private static readonly HashSet<string> Hosts = new(StringComparer.OrdinalIgnoreCase)
    {
        "youtube.com",
        "youtu.be",
        "m.youtube.com",
        "music.youtube.com",
        "youtube-nocookie.com",
        "www.youtube.com",
        "www.youtube-nocookie.com",
    };

    private static readonly Regex SignInRegex = new(
        "Sign in to confirm you.?re not a bot|Please sign in|members-only|age-restricted|This video may be inappropriate|Login required|This video requires payment|Private video",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex ForbiddenRegex = new(
        @"HTTP Error 403|\b403 Forbidden\b|HTTP/[\d.]+ 403|status code 403|errorCode=403",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static bool IsYouTube(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u)) return false;
        var host = u.Host.ToLowerInvariant();
        if (Hosts.Contains(host)) return true;
        foreach (var h in Hosts)
        {
            if (host.EndsWith("." + h, StringComparison.Ordinal)) return true;
        }
        return false;
    }

    public static bool IsSignInError(string? text) =>
        !string.IsNullOrWhiteSpace(text) && SignInRegex.IsMatch(text);

    public static bool Is403Error(string? text) =>
        !string.IsNullOrWhiteSpace(text) && ForbiddenRegex.IsMatch(text);
}
