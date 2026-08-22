namespace SnagLite.Services;

/// Host-name matching shared by the resolvers, IframeUnwrapper and YouTubeHost.
/// Every call site used to carry its own loop, and the variants drifted (one
/// was a bare EndsWith that matched "evilexample.com" for "example.com").
/// Semantics are pinned by tests/fixtures/host-suffix-cases.json.
internal static class HostMatch
{
    /// True when <paramref name="host"/> equals a candidate or is a subdomain of one.
    /// "www.example.com" matches "example.com"; "notexample.com" and
    /// "example.com.evil.net" do not. Case-insensitive.
    public static bool IsHostOrSubdomain(string host, IEnumerable<string> candidates)
    {
        if (string.IsNullOrEmpty(host)) return false;
        var h = host.ToLowerInvariant();
        foreach (var candidate in candidates)
        {
            var c = candidate.ToLowerInvariant();
            if (c.Length == 0) continue;
            if (h == c || h.EndsWith("." + c, StringComparison.Ordinal)) return true;
        }
        return false;
    }

    /// Resolver-A style matching for providers whose TLD changes constantly:
    /// a fragment ending in '.' matches a whole DNS label ("frag." → "frag.so",
    /// "www.frag.yt"), a fragment ending in '-' matches a label prefix ("frag-"
    /// → "frag-la.net"), anything else matches a whole label. Substrings inside
    /// other labels ("evilfrag.com", "fragment.com") do not match. Case-insensitive.
    public static bool MatchesLabelFragment(string host, IEnumerable<string> fragments)
    {
        if (string.IsNullOrEmpty(host)) return false;
        var labels = host.ToLowerInvariant().Split('.', StringSplitOptions.RemoveEmptyEntries);
        foreach (var fragment in fragments)
        {
            var f = fragment.ToLowerInvariant();
            if (f.Length == 0) continue;
            foreach (var label in labels)
            {
                if (f.EndsWith('.') ? label == f[..^1]
                  : f.EndsWith('-') ? label.StartsWith(f, StringComparison.Ordinal)
                  : label == f)
                    return true;
            }
        }
        return false;
    }
}
