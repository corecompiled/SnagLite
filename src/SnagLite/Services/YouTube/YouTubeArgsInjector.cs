namespace SnagLite.Services.YouTube;

public static class YouTubeArgsInjector
{
    public const string Primary = "player_client=tv_simply,default,mweb;formats=missing_pot";
    public const string Fallback = "player_client=web_safari,android_vr,mweb;formats=missing_pot";

    private const string Ua =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    public static IEnumerable<string> ExtraArgs(string url, bool useFallbackClients = false)
    {
        if (!YouTubeHost.IsYouTube(url)) yield break;

        yield return "--user-agent";
        yield return Ua;
        yield return "--add-header";
        yield return "Accept-Language:en-US,en;q=0.9";
        yield return "--extractor-args";
        yield return $"youtube:{(useFallbackClients ? Fallback : Primary)}";
    }
}
