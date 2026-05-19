namespace SnagLite.Services.Resolvers;

public sealed class ResolverPipeline
{
    private readonly IReadOnlyList<IResolver> _resolvers;
    private readonly HttpClient _http;

    public ResolverPipeline()
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression =
                System.Net.DecompressionMethods.GZip |
                System.Net.DecompressionMethods.Deflate |
                System.Net.DecompressionMethods.Brotli,
        };
        _http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };

        _resolvers = new IResolver[]
        {
            new ResolverA(),
            new ResolverB(),
            new ResolverC(),
        };
    }

    public IResolver? Match(Uri url) => _resolvers.FirstOrDefault(r => r.Matches(url));

    public async Task<ResolvedMedia?> ResolveAsync(Uri url, CancellationToken ct)
    {
        var r = Match(url);
        if (r is null) return null;
        return await r.ResolveAsync(url, _http, ct);
    }
}
