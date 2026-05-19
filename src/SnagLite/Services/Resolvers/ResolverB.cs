using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace SnagLite.Services.Resolvers;

// Wrapper-host resolver B.
//
// Decode algorithm:
//   1. Page contains <script type="application/json">["<encoded>"]</script>.
//   2. ROT13 the encoded string.
//   3. Strip noise digraphs: @$ ^^ ~@ %? *~ !! #&
//   4. Base64 decode.
//   5. Subtract 3 from each char's code point.
//   6. Reverse the string.
//   7. Base64 decode.
//   8. JSON parse — yields { source: m3u8, direct_access_url: mp4, title, ... }.
public sealed partial class ResolverB : IResolver
{
    public string Name => "b";

    private static readonly string[] Hosts =
    {
        "voe.sx", "voe-network.net", "voe-unblock.net",
        // Mirror/redirect hosts seen in the wild.
        "maryspecialwatch.com", "yodelsounds.com", "donaldlineelse.com",
        "sirdomainprovides.com", "tooseasydefenseable.com",
    };

    public bool Matches(Uri url)
    {
        var h = url.Host.ToLowerInvariant();
        foreach (var s in Hosts)
            if (h == s || h.EndsWith("." + s, StringComparison.Ordinal)) return true;
        return false;
    }

    public async Task<ResolvedMedia?> ResolveAsync(Uri url, HttpClient http, CancellationToken ct)
    {
        var page = await CurlClient.GetAsync(url.ToString(), referer: url.ToString(), ct: ct);
        if (page.StatusCode is < 200 or >= 400)
        {
            Console.Error.WriteLine($"[b] embed GET {page.StatusCode}");
            return null;
        }

        // First hit may be a JS-redirect stub.
        var redirectMatch = RedirectRegex().Match(page.Body);
        var landingUrl = page.EffectiveUrl;
        var html = page.Body;
        if (redirectMatch.Success)
        {
            var dst = redirectMatch.Groups[1].Value;
            var follow = await CurlClient.GetAsync(dst, referer: url.ToString(), ct: ct);
            if (follow.StatusCode is >= 200 and < 400)
            {
                html = follow.Body;
                landingUrl = follow.EffectiveUrl;
            }
        }

        var payloadMatch = PayloadRegex().Match(html);
        if (!payloadMatch.Success)
        {
            Console.Error.WriteLine("[b] no application/json payload found");
            return null;
        }

        string encoded;
        try
        {
            using var doc = JsonDocument.Parse(payloadMatch.Groups[1].Value);
            encoded = doc.RootElement[0].GetString() ?? "";
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[b] payload JSON parse failed: {ex.Message}");
            return null;
        }
        if (string.IsNullOrEmpty(encoded)) return null;

        string decoded;
        try { decoded = Decode(encoded); }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[b] decode failed: {ex.Message}");
            return null;
        }

        JsonElement root;
        try { using var d = JsonDocument.Parse(decoded); root = d.RootElement.Clone(); }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[b] decoded JSON parse failed: {ex.Message}");
            return null;
        }

        var direct = TryGetString(root, "direct_access_url");
        var source = TryGetString(root, "source");
        var title = TryGetString(root, "title") ?? TryGetString(root, "file_code") ?? "video";
        var name = SanitizeFilename(StripExt(title)) + ".mp4";

        var landing = Uri.TryCreate(landingUrl, UriKind.Absolute, out var lu) ? lu : url;
        var origin = $"{landing.Scheme}://{landing.Host}/";

        if (!string.IsNullOrEmpty(direct))
        {
            return new ResolvedMedia(
                DownloadUrl: direct,
                Referer: origin,
                SuggestedName: name,
                UserAgent: CurlClient.DefaultUa);
        }

        if (!string.IsNullOrEmpty(source))
        {
            // Aria2c can't decode m3u8, so a null return forces yt-dlp fallback.
            Console.Error.WriteLine("[b] only HLS source available — falling back to yt-dlp.");
            return null;
        }

        return null;
    }

    private static string Decode(string s)
    {
        s = Rot13(s);
        foreach (var pat in new[] { "@$", "^^", "~@", "%?", "*~", "!!", "#&" })
            s = s.Replace(pat, "");
        var bytes1 = Convert.FromBase64String(s);
        var step1 = Encoding.UTF8.GetString(bytes1);
        var sb = new StringBuilder(step1.Length);
        for (int i = 0; i < step1.Length; i++) sb.Append((char)(step1[i] - 3));
        var step2 = sb.ToString();
        var arr = step2.ToCharArray();
        Array.Reverse(arr);
        var step3 = new string(arr);
        var bytes2 = Convert.FromBase64String(step3);
        return Encoding.UTF8.GetString(bytes2);
    }

    private static string Rot13(string input)
    {
        var sb = new StringBuilder(input.Length);
        foreach (var c in input)
        {
            if (c >= 'a' && c <= 'z') sb.Append((char)((c - 'a' + 13) % 26 + 'a'));
            else if (c >= 'A' && c <= 'Z') sb.Append((char)((c - 'A' + 13) % 26 + 'A'));
            else sb.Append(c);
        }
        return sb.ToString();
    }

    private static string? TryGetString(JsonElement root, string key)
    {
        if (root.ValueKind != JsonValueKind.Object) return null;
        if (!root.TryGetProperty(key, out var v)) return null;
        return v.ValueKind == JsonValueKind.String ? v.GetString() : null;
    }

    private static string StripExt(string s)
    {
        var i = s.LastIndexOf('.');
        return (i > 0 && s.Length - i <= 5) ? s[..i] : s;
    }

    private static string SanitizeFilename(string s)
    {
        foreach (var c in Path.GetInvalidFileNameChars()) s = s.Replace(c, '_');
        s = Regex.Replace(s, @"\s+", " ").Trim();
        if (s.Length > 120) s = s[..120].Trim();
        return string.IsNullOrWhiteSpace(s) ? "video" : s;
    }

    [GeneratedRegex(@"window\.location\.href\s*=\s*['""]([^'""]+)['""]",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex RedirectRegex();

    [GeneratedRegex(@"<script[^>]+type=[""']application/json[""'][^>]*>(\[.*?\])</script>",
        RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.CultureInvariant)]
    private static partial Regex PayloadRegex();
}
