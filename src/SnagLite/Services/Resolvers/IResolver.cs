namespace SnagLite.Services.Resolvers;

public sealed record ResolvedMedia(
    string DownloadUrl,
    string Referer,
    string SuggestedName,
    string UserAgent);

public interface IResolver
{
    string Name { get; }
    bool Matches(Uri url);
    Task<ResolvedMedia?> ResolveAsync(Uri url, HttpClient http, CancellationToken ct);
}
