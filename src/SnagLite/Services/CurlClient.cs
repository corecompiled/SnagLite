using System.Diagnostics;
using System.Text;

namespace SnagLite.Services;

// Wraps the OS curl binary. .NET HttpClient gets Cloudflare-403'd on some hosts
// (TLS fingerprint mismatch); curl's Schannel/openssl handshake passes through.
// Windows 10+ ships curl.exe at C:\Windows\System32\curl.exe — always available.
public static class CurlClient
{
    public const string DefaultUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    public sealed record CurlResponse(int StatusCode, string EffectiveUrl, string Body);

    public static async Task<CurlResponse> GetAsync(
        string url,
        string? referer = null,
        IDictionary<string, string>? extraHeaders = null,
        string userAgent = DefaultUa,
        CancellationToken ct = default)
    {
        var args = new List<string>
        {
            "-sSL",
            "--max-time", "30",
            "--compressed",
            "-A", userAgent,
            "-w", "\n__SNAG_HTTP_META__:%{http_code}|%{url_effective}",
        };
        if (!string.IsNullOrWhiteSpace(referer))
        {
            args.Add("-e");
            args.Add(referer);
        }
        AddHeader(args, "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        AddHeader(args, "Accept-Language", "en-US,en;q=0.9");
        if (extraHeaders is not null)
            foreach (var kv in extraHeaders) AddHeader(args, kv.Key, kv.Value);
        args.Add(url);

        var psi = new ProcessStartInfo
        {
            FileName = ResolveCurlPath(),
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);

        using var p = Process.Start(psi)!;
        var stdoutTask = p.StandardOutput.ReadToEndAsync(ct);
        var stderrTask = p.StandardError.ReadToEndAsync(ct);
        await p.WaitForExitAsync(ct);
        var stdout = await stdoutTask;
        _ = await stderrTask; // discard

        var marker = "\n__SNAG_HTTP_META__:";
        var idx = stdout.LastIndexOf(marker, StringComparison.Ordinal);
        if (idx < 0) return new CurlResponse(0, url, stdout);
        var body = stdout[..idx];
        var meta = stdout[(idx + marker.Length)..].Trim();
        var pipe = meta.IndexOf('|');
        int code = 0;
        string effective = url;
        if (pipe > 0)
        {
            int.TryParse(meta[..pipe], out code);
            effective = meta[(pipe + 1)..];
        }
        return new CurlResponse(code, effective, body);
    }

    private static void AddHeader(List<string> args, string name, string value)
    {
        args.Add("-H");
        args.Add($"{name}: {value}");
    }

    private static string ResolveCurlPath()
    {
        var sys = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.System), "curl.exe");
        if (File.Exists(sys)) return sys;
        return "curl"; // fall back to PATH
    }
}
