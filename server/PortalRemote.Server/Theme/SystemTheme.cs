using Microsoft.Win32;

namespace PortalRemote.Theme;

/// <summary>
/// Reads the Windows "Apps use light/dark mode" setting so desktop surfaces can
/// pick the matching §3 palette. No live-notification plumbing here — QrForm is
/// short-lived enough to just re-check on every <c>Refresh</c>; TrayIcon, which is
/// genuinely long-lived for the app's whole session, subscribes to
/// <see cref="SystemEvents.UserPreferenceChanged"/> itself.
/// </summary>
public static class SystemTheme
{
    private const string PersonalizeKeyPath =
        @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";

    public static bool IsDark()
    {
        using var key = Registry.CurrentUser.OpenSubKey(PersonalizeKeyPath);
        // Missing key (pre-Win10 1809) or unreadable value: default to light,
        // matching the OS default from before this setting existed.
        return key?.GetValue("AppsUseLightTheme") is int value && value == 0;
    }

    public static PaletteColors Colors => IsDark() ? Palette.Dark : Palette.Light;
}
