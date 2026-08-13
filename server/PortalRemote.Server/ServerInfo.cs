using System.Reflection;

namespace PortalRemote;

/// <summary>Static identity of this build, reported to clients on connect.</summary>
public static class ServerInfo
{
    /// <summary>Read off the assembly rather than hard-coded, so a CI release stamped
    /// with <c>-p:Version=</c> from its tag reports that tag — the updater compares
    /// this against the newest release, and a frozen constant would never see one.
    /// The <c>+commit</c> suffix the SDK appends is dropped.</summary>
    public static readonly string Version =
        (typeof(ServerInfo).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion ?? "0.1.0-dev").Split('+')[0];
    public const string Name = "Portal Remote";
}
