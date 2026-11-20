using System.IO.Pipes;
using System.Text;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json;

namespace PortalRemote.Secure;

/// <summary>
/// The names, the wire format and — the part that actually matters — the access
/// control for the two pipes that carry input across to the secure desktop.
///
/// Windows puts the lock screen, UAC and Ctrl+Alt+Del on their own desktop
/// (<c>Winlogon</c>), and <c>SendInput</c> only ever lands on the desktop the calling
/// thread is attached to. A tray app in the interactive session cannot attach to
/// <c>Winlogon</c> — only SYSTEM can — so input has to be handed to a process that
/// can. That hand-off is this file, and it is a privilege boundary: everything on the
/// far side runs as LocalSystem and can click <b>Yes</b> on an elevation prompt.
/// Two rules keep it honest, and neither is optional:
///
/// <list type="number">
///   <item>Only <see cref="InputVerbs"/> may cross. The control socket also carries
///   <c>cast</c>, <c>power</c>, <c>open_url</c> and the assistant; forwarding those
///   would turn this into a "make SYSTEM run arbitrary verbs" channel.</item>
///   <item>The pipes are created by the SYSTEM service, never by the tray, and carry
///   the DACL from <see cref="Dacl"/>. A pipe the tray created first could be squatted
///   by any process running as the user; a pipe open to <c>Everyone</c> would be a
///   clean local escalation to SYSTEM-on-the-secure-desktop.</item>
/// </list>
/// </summary>
public static class SecureChannel
{
    /// <summary>Re-launch switches. Same shape as <c>Reachability.InstallFirewallArg</c>.</summary>
    public const string ServiceArg = "--secure-desktop-service";

    /// <inheritdoc cref="ServiceArg"/>
    public const string HelperArg = "--secure-desktop-helper";

    /// <summary>What the service is registered as. Used by <c>sc create</c>/<c>sc delete</c>.</summary>
    public const string ServiceName = "PortalRemoteSecureDesktop";

    /// <summary>
    /// Tray (interactive user) → service: input events up, desktop state down.
    /// Reachable by the console user, so its DACL is the one that has to be right.
    /// </summary>
    public const string ControlPipe = "PortalRemote.SecureDesktop.Control";

    /// <summary>
    /// Service → helper, both LocalSystem: input events down, desktop state up.
    /// A separate pipe rather than a role flag on the first one, so "SYSTEM only" can
    /// be expressed as a DACL instead of as a branch someone can forget to write.
    /// </summary>
    public const string HelperPipe = "PortalRemote.SecureDesktop.Helper";

    /// <summary>
    /// The only message types allowed across to SYSTEM — the injection arms of
    /// <c>InputActions.Dispatch</c> and nothing else. Checked twice on purpose: the
    /// tray filters before forwarding and the helper filters before dispatching, so
    /// neither side is trusting the other to have done it.
    ///
    /// <c>media</c> is deliberately absent even though it is "just" a key tap: its
    /// dispatch arm calls back into <c>SystemVolume</c>, which would bind the SYSTEM
    /// account's audio session rather than the user's.
    /// </summary>
    // ponytail: input verbs only; media/volume need a SYSTEM-safe path before they can cross.
    public static readonly HashSet<string> InputVerbs =
    [
        "mouse_move", "mouse_move_abs", "mouse_click", "scroll",
        "key", "tap", "combo", "text", "touch"
    ];

    /// <summary>
    /// Who may open a pipe the service created. SYSTEM and Administrators get it
    /// outright; the interactive user at the physical console gets read/write so the
    /// tray can forward, and is looked up fresh because fast user switching changes
    /// who that is. Everyone else — including other logged-on accounts, sandboxed
    /// processes and services — gets nothing, which is the entire point: without that
    /// restriction any local account could drive SYSTEM-level input onto a UAC prompt.
    /// </summary>
    public static PipeSecurity Dacl(SecurityIdentifier? consoleUser)
    {
        var system = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
        var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);

        var security = new PipeSecurity();
        security.SetOwner(system);
        security.AddAccessRule(new PipeAccessRule(system, PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(admins, PipeAccessRights.FullControl, AccessControlType.Allow));

        // Synchronize on top of ReadWrite: without it the client's handle is not
        // waitable and the connect fails with access denied rather than anything
        // that explains itself.
        if (consoleUser is not null)
        {
            security.AddAccessRule(new PipeAccessRule(
                consoleUser,
                PipeAccessRights.ReadWrite | PipeAccessRights.Synchronize,
                AccessControlType.Allow));
        }

        return security;
    }

    /// <summary>
    /// Refuse to talk to a pipe LocalSystem does not own.
    ///
    /// A DACL decides who may open the pipe <i>we</i> created; it says nothing about who
    /// created the pipe we just connected to. Between two connections the name is briefly
    /// nobody's, and whoever holds it next gets to be the far end: for the helper that
    /// means being handed a SYSTEM impersonation token and a stream of keystrokes to
    /// inject, and for the tray it means receiving the Windows password the user types
    /// from their phone to unlock the machine.
    ///
    /// Ownership is the check that cannot be faked. Setting an object's owner to
    /// LocalSystem needs <c>SeRestorePrivilege</c>, which the ordinary user account this
    /// is defending against does not have — so a pipe owned by SYSTEM was made by SYSTEM.
    /// Paired with <c>TokenImpersonationLevel.Anonymous</c> on both clients, which stops a
    /// server impersonating us even for the moment before this check runs.
    /// </summary>
    public static bool ServerIsSystem(PipeStream pipe)
    {
        try
        {
            var owner = pipe.GetAccessControl().GetOwner(typeof(SecurityIdentifier)) as SecurityIdentifier;
            return owner is not null && owner.IsWellKnown(WellKnownSidType.LocalSystemSid);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException
                                       or PrivilegeNotHeldException or InvalidOperationException)
        {
            // Cannot read the descriptor, so cannot vouch for it.
            return false;
        }
    }

    /// <summary>
    /// Anything remote-controlled that reaches the log, flattened first. The service logs
    /// the type of a message it refused, and that string came off the phone's socket: a
    /// newline in it would forge log lines, and the log is written by SYSTEM.
    /// </summary>
    public static string Safe(string? value)
    {
        if (string.IsNullOrEmpty(value)) return "<none>";
        var clean = new string(value.Where(c => !char.IsControl(c)).Take(64).ToArray());
        return clean.Length == 0 ? "<unprintable>" : clean;
    }

    /// <summary>
    /// One message, one line. Re-serialised compactly rather than passed through as
    /// <c>GetRawText</c>: a pretty-printed frame from the phone would carry literal
    /// newlines and desynchronise a line-delimited reader. Compact JSON escapes every
    /// newline inside string values, so the separator stays unambiguous.
    /// </summary>
    public static string Line(JsonElement message)
    {
        using var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            foreach (var property in message.EnumerateObject())
            {
                // "win" names a window in the user's session, and the secure desktop has
                // none of them — but the handle is still valid, so WinInput.BoundsFor
                // would resolve it to the rectangle that window occupies and scale a tap
                // meant for the lock screen into some app's old position. Frame requests
                // leave it out for the same reason; this makes input agree with them.
                if (property.NameEquals("win")) continue;
                property.WriteTo(writer);
            }

            writer.WriteEndObject();
        }

        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    /// <summary>The desktop the far side is attached to, as the service reports it upward.</summary>
    public static string DesktopLine(string name) =>
        JsonSerializer.Serialize(new { t = "desktop", name });

    /// <summary>
    /// Reads <c>name</c> off a desktop line, or null if this is not one. Anything that
    /// isn't a desktop notice is ignored rather than treated as an error — it leaves
    /// room to add notices later without a version handshake.
    /// </summary>
    public static string? ReadDesktop(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            if (!root.TryGetProperty("t", out var type) || type.GetString() != "desktop") return null;
            return root.TryGetProperty("name", out var name) ? name.GetString() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>
    /// The desktop a normal, unlocked session sits on. Anything else — <c>Winlogon</c>
    /// for the lock screen and UAC, <c>Screen-saver</c> — means local injection is
    /// being swallowed and the SYSTEM path is the one that works.
    /// </summary>
    public const string DefaultDesktop = "Default";

    /// <summary>The mirror asking the helper for one frame of whatever it is attached to.</summary>
    public const string FrameRequest = "frame_req";

    /// <summary>The helper's answer, carrying a JPEG or the reason there isn't one.</summary>
    public const string Frame = "frame";

    /// <summary>
    /// A frame request names a monitor but never a window: the secure desktop has none
    /// of the user's windows on it, so a window handle from the unlocked session would
    /// resolve to a rectangle with a lock screen behind it rather than to that app.
    /// </summary>
    public static string FrameRequestLine(int? monitor, int width, int quality, long id) =>
        JsonSerializer.Serialize(new { t = FrameRequest, mon = monitor, w = width, q = quality, id });

    /// <summary>
    /// A JPEG as base64 inside the same line protocol as everything else, rather than a
    /// second pipe carrying length-prefixed binary. At the mirror's 8–15fps this costs a
    /// third more bytes over a local pipe that is nowhere near saturated, and saves a
    /// whole transport — its DACL, its accept loop and its framing.
    /// </summary>
    // ponytail: base64 over the control pipe. A shared memory-mapped frame buffer is the
    // upgrade if the mirror ever wants a frame rate this cannot feed.
    public static string FrameLine(byte[] jpeg, long id) =>
        JsonSerializer.Serialize(new { t = Frame, id, b64 = Convert.ToBase64String(jpeg) });

    /// <summary>
    /// A frame answer with no image. It carries no reason: the helper is the side that
    /// can write to the log, and it has already recorded why there. Sending the string as
    /// well would be a payload nothing reads.
    /// </summary>
    public static string FrameEmptyLine(long id) => JsonSerializer.Serialize(new { t = Frame, id });

    /// <summary>The JPEG from a frame line, or null if this is not one or carried no image.</summary>
    public static byte[]? ReadFrame(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            if (!root.TryGetProperty("t", out var type) || type.GetString() != Frame) return null;
            if (!root.TryGetProperty("b64", out var payload) || payload.ValueKind != JsonValueKind.String)
                return null;
            return payload.GetBytesFromBase64();
        }
        catch (Exception ex) when (ex is JsonException or FormatException)
        {
            return null;
        }
    }

    /// <summary>True for any line that is a frame answer, empty or not — see <see cref="ReadFrame"/>.</summary>
    public static bool IsFrame(string line) => TypeOf(line) == Frame;

    /// <summary>
    /// The request a frame line is answering, or null. Without this a capture that
    /// overran the mirror's deadline would be handed to whichever request was waiting when
    /// it finally arrived — every later tick showing the previous tick's picture, and a
    /// request for one monitor answerable with another one's image.
    /// </summary>
    public static long? ReadId(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            return root.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.Number
                ? id.GetInt64() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>The <c>t</c> of a line, or null if it has none. The service relays on this.</summary>
    public static string? TypeOf(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            return root.TryGetProperty("t", out var type) ? type.GetString() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
