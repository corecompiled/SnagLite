using System.Text.RegularExpressions;

namespace SnagLite.Services;

/// Filename helpers for resolver-named outputs (page title → file name).
/// Uses an explicit Windows-invalid set rather than Path.GetInvalidFileNameChars()
/// so behaviour is identical on every platform the tests run on.
/// Semantics are pinned by tests/fixtures/filenames.json.
internal static partial class FileNames
{
    private const int MaxLength = 120;

    public static string Sanitize(string s)
    {
        var chars = s.ToCharArray();
        for (int i = 0; i < chars.Length; i++)
        {
            var c = chars[i];
            // Whitespace controls (\t \n \r) fall through to the collapse below;
            // every other control char and the Windows-reserved set become '_'.
            if ((c < ' ' && !char.IsWhiteSpace(c)) || c is '\\' or '/' or ':' or '*' or '?' or '"' or '<' or '>' or '|')
                chars[i] = '_';
        }
        var result = WhitespaceRun().Replace(new string(chars), " ").Trim();
        if (result.Length > MaxLength) result = result[..MaxLength].Trim();
        result = result.TrimEnd('.', ' ');
        if (result.Length == 0) return "video";
        var stem = result.Split('.', 2)[0];
        if (ReservedDeviceName().IsMatch(stem)) result = "_" + result;
        return result;
    }

    /// Drops a trailing ".ext" when ext is 1–4 characters and the dot is not
    /// the first character; anything else is returned unchanged.
    public static string StripExt(string s)
    {
        var i = s.LastIndexOf('.');
        return (i > 0 && s.Length - i <= 5) ? s[..i] : s;
    }

    [GeneratedRegex(@"\s+")]
    private static partial Regex WhitespaceRun();

    [GeneratedRegex(@"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex ReservedDeviceName();
}
