using System.Text.Json;

namespace SnagLite.Tests;

/// Loads a JSON fixture from tests/fixtures (copied next to the test assembly).
/// Fixtures are platform-neutral: the Android unit tests read the same files.
internal static class Fixtures
{
    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    public static T Load<T>(string name)
    {
        var path = Path.Combine(AppContext.BaseDirectory, "fixtures", name);
        var json = File.ReadAllText(path);
        return JsonSerializer.Deserialize<T>(json, Options)
            ?? throw new InvalidOperationException($"Fixture {name} deserialized to null.");
    }
}
