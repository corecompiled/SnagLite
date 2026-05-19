using System.Text;
using System.Text.RegularExpressions;

namespace SnagLite.Services.Resolvers;

// Wrapper-host resolver C.
//
// Algorithm:
//   1. GET /e/<id> embed page (curl, follow redirects).
//   2. Find Dean-Edwards packed block: eval(function(p,a,c,k,e,d){...}('P',A,C,'K|K|K'.split('|'),0,{}))
//   3. Unpack: replace each \b<base_a(N)>\b in P with K[N].
//   4. Read MDCore.wurl (preferred) or MDCore.furl from unpacked code — that's the progressive MP4.
//   5. Prepend https: to protocol-relative URL.
public sealed partial class ResolverC : IResolver
{
    public string Name => "c";

    private static readonly string[] Hosts =
    {
        "mixdrop.ag", "mixdrop.co", "mixdrop.to", "mixdrop.club",
        "mixdrop.is", "mixdrop.sx", "mixdrop.ch", "mixdrop.bz",
        "mixdrop.gl", "mixdrop.si", "mixdrop.nu", "mixdrop.ws",
        "m1xdrop.click", "m1xdrop.net", "m1xdrop.co", "mxdrop.to",
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
        var embed = url;
        if (embed.AbsolutePath.StartsWith("/f/", StringComparison.Ordinal))
            embed = new Uri($"{embed.Scheme}://{embed.Host}/e/{embed.AbsolutePath[3..]}");

        var page = await CurlClient.GetAsync(embed.ToString(), referer: embed.ToString(), ct: ct);
        if (page.StatusCode is < 200 or >= 400)
        {
            Console.Error.WriteLine($"[c] embed GET {page.StatusCode} -> {page.EffectiveUrl}");
            return null;
        }

        var packed = PackedRegex().Match(page.Body);
        if (!packed.Success)
        {
            Console.Error.WriteLine("[c] no packed eval block found");
            return null;
        }

        string unpacked;
        try
        {
            var p = UnescapeJsString(packed.Groups["p"].Value);
            var a = int.Parse(packed.Groups["a"].Value);
            var c = int.Parse(packed.Groups["c"].Value);
            var k = packed.Groups["k"].Value.Split('|');
            unpacked = Unpack(p, a, c, k);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[c] unpack failed: {ex.Message}");
            return null;
        }

        var media = ExtractMdString(unpacked, "wurl");
        if (string.IsNullOrWhiteSpace(media))
            media = ExtractMdString(unpacked, "furl");

        if (string.IsNullOrWhiteSpace(media))
        {
            Console.Error.WriteLine("[c] no MDCore.wurl/furl in unpacked code");
            return null;
        }

        if (media.StartsWith("//", StringComparison.Ordinal)) media = "https:" + media;
        else if (media.StartsWith("/", StringComparison.Ordinal))
        {
            var origin = Uri.TryCreate(page.EffectiveUrl, UriKind.Absolute, out var eu)
                ? $"{eu.Scheme}://{eu.Host}"
                : $"{embed.Scheme}://{embed.Host}";
            media = origin + media;
        }

        var title = ExtractTitle(page.Body);
        if (string.IsNullOrWhiteSpace(title))
        {
            var vfile = ExtractMdString(unpacked, "vfile");
            title = !string.IsNullOrWhiteSpace(vfile) ? StripExt(vfile!) : embed.AbsolutePath.TrimEnd('/').Split('/')[^1];
        }

        var name = SanitizeFilename(StripExt(title!)) + ".mp4";
        var refererOrigin = Uri.TryCreate(page.EffectiveUrl, UriKind.Absolute, out var lu)
            ? $"{lu.Scheme}://{lu.Host}/"
            : embed.ToString();

        return new ResolvedMedia(
            DownloadUrl: media,
            Referer: refererOrigin,
            SuggestedName: name,
            UserAgent: CurlClient.DefaultUa);
    }

    private const string Base62 =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static string Unpack(string p, int a, int c, string[] k)
    {
        if (a < 2 || a > 62) throw new ArgumentOutOfRangeException(nameof(a));
        for (int i = c - 1; i >= 0; i--)
        {
            if (i >= k.Length) continue;
            var key = k[i];
            if (string.IsNullOrEmpty(key)) continue;
            var token = ToBase(i, a);
            p = Regex.Replace(p, @"\b" + Regex.Escape(token) + @"\b", _ => key);
        }
        return p;
    }

    private static string ToBase(int n, int radix)
    {
        if (n == 0) return "0";
        var sb = new StringBuilder();
        while (n > 0)
        {
            sb.Insert(0, Base62[n % radix]);
            n /= radix;
        }
        return sb.ToString();
    }

    private static string UnescapeJsString(string s)
    {
        var sb = new StringBuilder(s.Length);
        for (int i = 0; i < s.Length; i++)
        {
            var ch = s[i];
            if (ch == '\\' && i + 1 < s.Length)
            {
                var next = s[++i];
                switch (next)
                {
                    case '\\': sb.Append('\\'); break;
                    case '\'': sb.Append('\''); break;
                    case '"': sb.Append('"'); break;
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case '/': sb.Append('/'); break;
                    case 'x' when i + 2 < s.Length:
                        sb.Append((char)Convert.ToInt32(s.Substring(i + 1, 2), 16));
                        i += 2;
                        break;
                    case 'u' when i + 4 < s.Length:
                        sb.Append((char)Convert.ToInt32(s.Substring(i + 1, 4), 16));
                        i += 4;
                        break;
                    default: sb.Append(next); break;
                }
            }
            else sb.Append(ch);
        }
        return sb.ToString();
    }

    private static string? ExtractMdString(string code, string field)
    {
        var m = Regex.Match(code, @"MDCore\." + Regex.Escape(field) + @"\s*=\s*""([^""]*)""");
        if (!m.Success) return null;
        var v = m.Groups[1].Value;
        return string.IsNullOrWhiteSpace(v) ? null : v;
    }

    private static string? ExtractTitle(string html)
    {
        var m = Regex.Match(html, @"<title>([^<]+)</title>", RegexOptions.IgnoreCase);
        if (!m.Success) return null;
        var t = m.Groups[1].Value.Trim();
        return string.IsNullOrWhiteSpace(t) ? null : t;
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

    [GeneratedRegex(
        @"eval\(function\(p,a,c,k,e,d\)\{.*?\}\s*\(\s*'(?<p>(?:\\.|[^'\\])*)'\s*,\s*(?<a>\d+)\s*,\s*(?<c>\d+)\s*,\s*'(?<k>(?:\\.|[^'\\])*)'\.split\('\|'\)",
        RegexOptions.Singleline | RegexOptions.CultureInvariant)]
    private static partial Regex PackedRegex();
}
