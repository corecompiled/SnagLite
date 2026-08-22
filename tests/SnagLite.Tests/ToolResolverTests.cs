using System.Security.Cryptography;
using System.Text;
using SnagLite.Services;
using Xunit;

namespace SnagLite.Tests;

public class ToolResolverTests
{
    private const string Sums =
        "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6  yt-dlp\n" +
        "66674953FE251B89F4D08C5F0E35E0728679BD67AB3D7D05C0562AF101DD3E7A  yt-dlp.exe\n" +
        "072aad4f2a7604e92155f61a275a4752dc64046c8f6d90df3710525d94cd37c1 *yt-dlp.tar.gz\n";

    [Fact]
    public void ParseSha256SumsFindsNamedFileCaseInsensitiveHex()
    {
        Assert.Equal("66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a",
            ToolResolver.ParseSha256Sums(Sums, "yt-dlp.exe"));
    }

    [Fact]
    public void ParseSha256SumsDoesNotMatchPrefixOfAnotherName()
    {
        // "yt-dlp" must not resolve to the yt-dlp.exe or yt-dlp.tar.gz line.
        Assert.Equal("1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6",
            ToolResolver.ParseSha256Sums(Sums, "yt-dlp"));
    }

    [Fact]
    public void ParseSha256SumsAcceptsBinaryMarker()
    {
        Assert.Equal("072aad4f2a7604e92155f61a275a4752dc64046c8f6d90df3710525d94cd37c1",
            ToolResolver.ParseSha256Sums(Sums, "yt-dlp.tar.gz"));
    }

    [Theory]
    [InlineData("missing.exe")]
    [InlineData("")]
    public void ParseSha256SumsReturnsNullWhenAbsent(string name)
    {
        Assert.Null(ToolResolver.ParseSha256Sums(Sums, name));
        Assert.Null(ToolResolver.ParseSha256Sums("", "yt-dlp.exe"));
        Assert.Null(ToolResolver.ParseSha256Sums("not a sums file\n", "yt-dlp.exe"));
    }

    [Theory]
    [InlineData("fec81ae03971d9dd4be3ebe02e263bd2ec1d789483f931bdba5f5715e65da2e9")]
    [InlineData("FEC81AE03971D9DD4BE3EBE02E263BD2EC1D789483F931BDBA5F5715E65DA2E9\n")]
    [InlineData("  fec81ae03971d9dd4be3ebe02e263bd2ec1d789483f931bdba5f5715e65da2e9 \r\n")]
    public void ParseBareSha256NormalisesToLowerHex(string text)
    {
        Assert.Equal("fec81ae03971d9dd4be3ebe02e263bd2ec1d789483f931bdba5f5715e65da2e9",
            ToolResolver.ParseBareSha256(text));
    }

    [Theory]
    [InlineData("")]
    [InlineData("<html>404</html>")]
    [InlineData("fec81ae03971d9dd4be3ebe02e263bd2ec1d789483f931bdba5f5715e65da2e")]
    [InlineData("fec81ae03971d9dd4be3ebe02e263bd2ec1d789483f931bdba5f5715e65da2e9  file.zip")]
    [InlineData("zzc81ae03971d9dd4be3ebe02e263bd2ec1d789483f931bdba5f5715e65da2e9")]
    public void ParseBareSha256RejectsAnythingElse(string text)
    {
        Assert.Null(ToolResolver.ParseBareSha256(text));
    }

    private static string Sha256Hex(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));

    private sealed class TempDir : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "snaglite-tests-" + Guid.NewGuid().ToString("N"));
        public TempDir() => Directory.CreateDirectory(Path);
        public string File(string name) => System.IO.Path.Combine(Path, name);
        public void Dispose() { try { Directory.Delete(Path, recursive: true); } catch { } }
    }

    [Fact]
    public async Task CommitVerifiedMovesTmpOverDestWhenHashMatches()
    {
        using var dir = new TempDir();
        var payload = Encoding.UTF8.GetBytes("new binary");
        await File.WriteAllBytesAsync(dir.File("tool.exe.new"), payload);
        await File.WriteAllTextAsync(dir.File("tool.exe"), "old binary");

        await ToolResolver.CommitVerifiedAsync(dir.File("tool.exe.new"), dir.File("tool.exe"), Sha256Hex(payload), "tool", CancellationToken.None);

        Assert.False(File.Exists(dir.File("tool.exe.new")));
        Assert.Equal(payload, await File.ReadAllBytesAsync(dir.File("tool.exe")));
    }

    [Fact]
    public async Task CommitVerifiedDeletesTmpAndKeepsDestWhenHashMismatches()
    {
        using var dir = new TempDir();
        await File.WriteAllTextAsync(dir.File("tool.exe.new"), "tampered");
        await File.WriteAllTextAsync(dir.File("tool.exe"), "old binary");
        var expected = Sha256Hex(Encoding.UTF8.GetBytes("new binary"));

        var ex = await Assert.ThrowsAsync<ToolIntegrityException>(() =>
            ToolResolver.CommitVerifiedAsync(dir.File("tool.exe.new"), dir.File("tool.exe"), expected, "tool", CancellationToken.None));

        Assert.Contains("tool", ex.Message);
        Assert.Contains(expected, ex.Message);
        Assert.False(File.Exists(dir.File("tool.exe.new")));
        Assert.Equal("old binary", await File.ReadAllTextAsync(dir.File("tool.exe")));
    }

    [Fact]
    public async Task CommitVerifiedComparesHexCaseInsensitively()
    {
        using var dir = new TempDir();
        var payload = Encoding.UTF8.GetBytes("x");
        await File.WriteAllBytesAsync(dir.File("t.new"), payload);

        await ToolResolver.CommitVerifiedAsync(dir.File("t.new"), dir.File("t"), Sha256Hex(payload).ToUpperInvariant(), "t", CancellationToken.None);

        Assert.True(File.Exists(dir.File("t")));
    }
}
