namespace SnagLite.Services.Resolvers;

public sealed class ResolverPipeline
{
    private readonly IReadOnlyList<IResolver> _resolvers;

    public ResolverPipeline() : this([new ResolverA(), new ResolverB(), new ResolverC()]) { }

    internal ResolverPipeline(IReadOnlyList<IResolver> resolvers) => _resolvers = resolvers;

    public IResolver? Match(Uri url) => _resolvers.FirstOrDefault(r => r.Matches(url));

    /// Runs the first matching resolver. A result whose DownloadUrl is not an
    /// absolute http(s) URL is dropped: it came from a remote page and is about
    /// to become an aria2c argument, so anything else is not worth trusting.
    public async Task<ResolvedMedia?> ResolveAsync(Uri url, CancellationToken ct)
    {
        var r = Match(url);
        if (r is null) return null;
        var media = await r.ResolveAsync(url, ct);
        if (media is null) return null;
        if (!IsHttpUrl(media.DownloadUrl))
        {
            Console.Error.WriteLine($"[{r.Name}] resolved URL is not http(s) — ignoring.");
            return null;
        }
        return media;
    }

    private static bool IsHttpUrl(string s) =>
        Uri.TryCreate(s, UriKind.Absolute, out var u) &&
        (u.Scheme == Uri.UriSchemeHttp || u.Scheme == Uri.UriSchemeHttps);
}
