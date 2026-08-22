using SnagLite.Services.YouTube;
using Xunit;

namespace SnagLite.Tests;

public class EngineUpdateCheckerTests
{
    private static readonly DateTime Now = new(2026, 8, 22, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void FreshStateIsDue()
    {
        Assert.True(EngineUpdateChecker.IsDue(new EngineUpdateChecker.State(), Now));
    }

    [Fact]
    public void CheckedWithinSevenDaysIsNotDue()
    {
        var s = new EngineUpdateChecker.State { LastChecked = Now.AddDays(-6.9) };
        Assert.False(EngineUpdateChecker.IsDue(s, Now));
    }

    [Fact]
    public void CheckedMoreThanSevenDaysAgoIsDue()
    {
        var s = new EngineUpdateChecker.State { LastChecked = Now.AddDays(-7.1) };
        Assert.True(EngineUpdateChecker.IsDue(s, Now));
    }

    [Fact]
    public void FailedWithinTwentyFourHoursBacksOff()
    {
        // A permanently failing refresh used to re-download ~18 MB on every run
        // (or, worse, silently stamp LastChecked and hide for a week).
        var s = new EngineUpdateChecker.State { LastChecked = Now.AddDays(-30), LastFailed = Now.AddHours(-23) };
        Assert.False(EngineUpdateChecker.IsDue(s, Now));
    }

    [Fact]
    public void FailedMoreThanTwentyFourHoursAgoIsDueAgain()
    {
        var s = new EngineUpdateChecker.State { LastChecked = Now.AddDays(-30), LastFailed = Now.AddHours(-25) };
        Assert.True(EngineUpdateChecker.IsDue(s, Now));
    }

    [Fact]
    public void FutureTimestampsDoNotSuppressForever()
    {
        // Clock moved backwards (or a corrupt file): treat as due rather than
        // silently skipping until the far future.
        var s = new EngineUpdateChecker.State { LastChecked = Now.AddYears(1) };
        Assert.True(EngineUpdateChecker.IsDue(s, Now));
    }
}
