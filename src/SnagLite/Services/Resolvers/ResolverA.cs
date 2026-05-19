using System.Text.RegularExpressions;

namespace SnagLite.Services.Resolvers;

// Wrapper-host resolver A.
// Algorithm:
//   GET embed page -> extract /pass_md5/<token> -> GET that with Referer ->
//   body is a base URL -> append 10-char random suffix + ?token=<lastSeg>&expiry=<unix-ms>.
public sealed partial class ResolverA : IResolver
{
    public string Name => "a";

    private static readonly string[] HostSuffixes =
    {
        "dood.", "doods.", "dood-", "ds2play.", "ds2video.",
        "dsvplay.", "playmogo.", "all3do.", "vidply.", "d000d.",
        "d0000d.", "d0o0d.", "doply.", "dooood.", "vide0.",
    };

    public bool Matches(Uri url)
    {
        var h = url.Host.ToLowerInvariant();
        foreach (var s in HostSuffixes)
            if (h.Contains(s, StringComparison.Ordinal)) return true;
        return false;
    }

    public async Task<ResolvedMedia?> ResolveAsync(Uri url, HttpClient http, CancellationToken ct)
    {
        // Normalize to /e/<id> if /d/<id> given.
        var embed = url;
        if (embed.AbsolutePath.StartsWith("/d/", StringComparison.Ordinal))
            embed = new Uri($"{embed.Scheme}://{embed.Host}/e/{embed.AbsolutePath[3..]}");

        var pageRes = await CurlClient.GetAsync(embed.ToString(), referer: embed.ToString(), ct: ct);
        if (pageRes.StatusCode is < 200 or >= 400)
        {
            Console.Error.WriteLine($"[a] embed GET {pageRes.StatusCode} -> {pageRes.EffectiveUrl}");
            return null;
        }

        var landing = Uri.TryCreate(pageRes.EffectiveUrl, UriKind.Absolute, out var lu) ? lu : embed;
        var landingOrigin = $"{landing.Scheme}://{landing.Host}";
        var html = pageRes.Body;

        var passMatch = PassMd5Regex().Match(html);
        if (!passMatch.Success)
        {
            Console.Error.WriteLine($"[a] no pass_md5 in HTML (len={html.Length}) at {landing}");
            return null;
        }
        var passPath = passMatch.Value;
        var token = passPath[(passPath.LastIndexOf('/') + 1)..];

        var passUrl = $"{landingOrigin}{passPath}";
        var passHeaders = new Dictionary<string, string> { ["X-Requested-With"] = "XMLHttpRequest" };
        var passRes = await CurlClient.GetAsync(passUrl, referer: landing.ToString(),
            extraHeaders: passHeaders, ct: ct);
        if (passRes.StatusCode is < 200 or >= 400)
        {
            Console.Error.WriteLine($"[a] pass_md5 GET {passRes.StatusCode} at {passUrl}");
            return null;
        }
        var baseUrl = passRes.Body.Trim();
        if (string.IsNullOrWhiteSpace(baseUrl) || !baseUrl.StartsWith("http", StringComparison.Ordinal))
        {
            Console.Error.WriteLine($"[a] pass_md5 body not URL: '{baseUrl[..Math.Min(120, baseUrl.Length)]}'");
            return null;
        }

        var rand = RandomAlnum(10);
        var expiryMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var finalUrl = $"{baseUrl}{rand}?token={token}&expiry={expiryMs}";

        var title = TitleRegex().Match(html).Groups[1].Value.Trim();
        if (string.IsNullOrWhiteSpace(title)) title = token;
        var safe = SanitizeFilename(title) + ".mp4";

        return new ResolvedMedia(
            DownloadUrl: finalUrl,
            Referer: landingOrigin + "/",
            SuggestedName: safe,
            UserAgent: CurlClient.DefaultUa);
    }

    private static string RandomAlnum(int n)
    {
        const string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        var buf = new char[n];
        var rng = Random.Shared;
        for (int i = 0; i < n; i++) buf[i] = chars[rng.Next(chars.Length)];
        return new string(buf);
    }

    private static string SanitizeFilename(string s)
    {
        foreach (var c in Path.GetInvalidFileNameChars()) s = s.Replace(c, '_');
        s = Regex.Replace(s, @"\s+", " ").Trim();
        if (s.Length > 120) s = s[..120].Trim();
        return string.IsNullOrWhiteSpace(s) ? "video" : s;
    }

    [GeneratedRegex(@"/pass_md5/[A-Za-z0-9_\-/]+", RegexOptions.CultureInvariant)]
    private static partial Regex PassMd5Regex();

    [GeneratedRegex(@"<title>([^<]+)</title>", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex TitleRegex();
}
