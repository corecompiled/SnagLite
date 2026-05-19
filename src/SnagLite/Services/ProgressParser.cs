using System.Globalization;
using System.Text.RegularExpressions;

namespace SnagLite.Services;

public readonly record struct ProgressTick(double Percent, string Speed, string Eta, string Total);

public static class ProgressParser
{
    private static readonly Regex AnsiCodes = new(@"\x1B\[[0-9;]*[A-Za-z]", RegexOptions.Compiled);
    private static readonly Regex PercentRx = new(@"([\d.]+)\s*%", RegexOptions.Compiled);

    public static bool TryParse(string line, out ProgressTick tick)
    {
        tick = default;
        if (string.IsNullOrWhiteSpace(line)) return false;
        var clean = AnsiCodes.Replace(line, string.Empty).Trim();
        if (!clean.StartsWith("PROG|", StringComparison.Ordinal)) return false;

        var parts = clean.Split('|');
        if (parts.Length < 5) return false;

        var pctMatch = PercentRx.Match(parts[1]);
        if (!pctMatch.Success) return false;
        if (!double.TryParse(pctMatch.Groups[1].Value, NumberStyles.Float, CultureInfo.InvariantCulture, out var pct))
            return false;

        tick = new ProgressTick(
            Percent: pct,
            Total: parts[2].Trim(),
            Speed: parts[3].Trim(),
            Eta: parts[4].Trim());
        return true;
    }
}
