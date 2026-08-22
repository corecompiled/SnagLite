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

    private const string MetaMarker = "\n__SNAG_HTTP_META__:";

    public sealed record CurlResponse(int StatusCode, string EffectiveUrl, string Body);

    /// GET a page through curl. HTTP error statuses come back in the response;
    /// a transport-level failure (DNS, TLS, timeout — curl exit != 0 with no
    /// status trailer) throws HttpRequestException carrying curl's stderr.
    public static async Task<CurlResponse> GetAsync(
        string url,
        string? referer = null,
        IDictionary<string, string>? extraHeaders = null,
        string userAgent = DefaultUa,
        CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo
        {
            FileName = ResolveCurlPath(),
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            // Without these the pipes decode with the console OEM code page and
            // every non-ASCII title turns to mojibake — then into the filename.
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var a in BuildArgs(url, referer, extraHeaders, userAgent)) psi.ArgumentList.Add(a);

        using var p = Process.Start(psi) ?? throw new InvalidOperationException("curl failed to start");
        // Disposing the Process object does not end the process; cancellation
        // has to kill it or Ctrl+C during resolution leaves curl.exe orphaned.
        using var killOnCancel = ct.Register(() => { try { p.Kill(entireProcessTree: true); } catch { } });

        var stdoutTask = p.StandardOutput.ReadToEndAsync(ct);
        var stderrTask = p.StandardError.ReadToEndAsync(ct);
        await p.WaitForExitAsync(ct);
        var stdout = await stdoutTask;
        var stderr = await stderrTask;

        var res = ParseResponse(url, stdout);
        if (p.ExitCode != 0 && res.StatusCode == 0)
            throw new HttpRequestException($"curl exit {p.ExitCode}: {stderr.Trim()}");
        return res;
    }

    /// The URL goes last, after "--": redirect targets and API paths are read
    /// out of remote pages, and a value starting with "-" must not be parsed
    /// as a curl option.
    internal static List<string> BuildArgs(
        string url, string? referer, IDictionary<string, string>? extraHeaders, string userAgent)
    {
        var args = new List<string>
        {
            "-sSL",
            "--max-time", "30",
            "--compressed",
            "-A", userAgent,
            "-w", MetaMarker + "%{http_code}|%{url_effective}",
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
        args.Add("--");
        args.Add(url);
        return args;
    }

    /// Splits curl's stdout into body and the "-w" trailer. No trailer (curl
    /// died before writing it) yields status 0 and the raw output as body.
    internal static CurlResponse ParseResponse(string requestUrl, string stdout)
    {
        var idx = stdout.LastIndexOf(MetaMarker, StringComparison.Ordinal);
        if (idx < 0) return new CurlResponse(0, requestUrl, stdout);
        var body = stdout[..idx];
        var meta = stdout[(idx + MetaMarker.Length)..].Trim();
        var pipe = meta.IndexOf('|');
        int code = 0;
        string effective = requestUrl;
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
