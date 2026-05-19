using System.Text.RegularExpressions;

namespace SnagLite.Services.Resolvers;

// Some hosts are pure listing pages that embed a third-party player via <iframe>.
// Unwrap to the inner URL before resolver/yt-dlp routing so the engine sees the
// real provider.
//
// The list of wrapper hosts is loaded at runtime from `hosts.local.txt` — a
// gitignored file embedded as a resource at build time when present. If the
// file is missing (e.g. a fresh OSS clone), the list is empty and the unwrap
// feature is silently disabled; downloads still work through yt-dlp's normal
// extractor + generic-fallback chain. See PRIVATE_NOTES.local.md.
public static partial class IframeUnwrapper
{
    private static readonly Lazy<string[]> WrapperHosts = new(LoadHostsFromEmbeddedResource);

    private static string[] LoadHostsFromEmbeddedResource()
    {
        var asm = typeof(IframeUnwrapper).Assembly;
        using var s = asm.GetManifestResourceStream("hosts.local.txt");
        if (s is null) return Array.Empty<string>();
        using var r = new StreamReader(s);
        var list = new List<string>();
        string? line;
        while ((line = r.ReadLine()) != null)
        {
            var t = line.Trim();
            if (t.Length == 0 || t.StartsWith('#')) continue;
            list.Add(t.ToLowerInvariant());
        }
        return list.ToArray();
    }

    public static bool ShouldUnwrap(Uri url)
    {
        var h = url.Host.ToLowerInvariant();
        foreach (var w in WrapperHosts.Value)
            if (h.EndsWith(w, StringComparison.Ordinal)) return true;
        return false;
    }

    public static async Task<Uri?> UnwrapAsync(Uri url, CancellationToken ct)
    {
        var res = await CurlClient.GetAsync(url.ToString(), ct: ct);
        if (res.StatusCode is < 200 or >= 400) return null;

        var m = IframeRegex().Match(res.Body);
        if (!m.Success) return null;
        var src = m.Groups[1].Value.Trim();
        if (src.StartsWith("//", StringComparison.Ordinal)) src = "https:" + src;
        return Uri.TryCreate(src, UriKind.Absolute, out var u) ? u : null;
    }

    [GeneratedRegex(@"<iframe[^>]+src=[""']([^""']+)[""']",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex IframeRegex();
}
