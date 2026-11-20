using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using PortalRemote.Secure;
using Xunit;

namespace PortalRemote.Tests;

/// <summary>
/// The two things on the secure-desktop path that can be checked without a lock screen
/// — and the two worth checking, because both are what stops a LAN feature from being a
/// local escalation to SYSTEM. Everything else about that path needs a real desktop
/// switch and is verified by hand.
/// </summary>
public class SecureChannelTests
{
    /// <summary>
    /// A literal account SID: the shape of a real logged-on user, and identical on every
    /// machine the suite runs on.
    /// </summary>
    private static readonly SecurityIdentifier ConsoleUser =
        new("S-1-5-21-1111111111-2222222222-3333333333-1001");

    [Fact]
    public void Dacl_grants_system_admins_and_the_console_user()
    {
        var granted = Granted(SecureChannel.Dacl(ConsoleUser));

        Assert.Contains(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), granted);
        Assert.Contains(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), granted);
        Assert.Contains(ConsoleUser, granted);
    }

    /// <summary>
    /// The one that matters. Either of these on the pipe would let any local account —
    /// a second logged-on user, a sandboxed process, a service — drive SYSTEM-level
    /// input onto a UAC prompt.
    /// </summary>
    [Fact]
    public void Dacl_grants_no_one_else()
    {
        foreach (var dacl in new[] { SecureChannel.Dacl(ConsoleUser), SecureChannel.Dacl(null) })
        {
            var granted = Granted(dacl);
            Assert.DoesNotContain(new SecurityIdentifier(WellKnownSidType.WorldSid, null), granted);
            Assert.DoesNotContain(new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null), granted);
            Assert.DoesNotContain(new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null), granted);
            Assert.DoesNotContain(new SecurityIdentifier(WellKnownSidType.AnonymousSid, null), granted);
            Assert.DoesNotContain(new SecurityIdentifier(WellKnownSidType.InteractiveSid, null), granted);
        }
    }

    /// <summary>With nobody signed in there is no user to name, and the pipe stays SYSTEM-only.</summary>
    [Fact]
    public void Dacl_without_a_console_user_is_system_and_admins_only()
    {
        Assert.Equal(2, Granted(SecureChannel.Dacl(null)).Count);
    }

    /// <summary>
    /// The allowlist is the other half of the boundary: the control socket carries far
    /// more than input, and none of the rest has any business running as SYSTEM.
    /// </summary>
    [Fact]
    public void Only_input_verbs_may_cross_to_system()
    {
        foreach (var verb in new[]
                 {
                     "cast", "cast_status", "cast_targets", "player", "open_url",
                     "power", "power_timer_set", "power_timer_cancel",
                     "volume_set", "media", "ai_state", "ai_send", "seek", "sys", "win_watch"
                 })
        {
            Assert.DoesNotContain(verb, SecureChannel.InputVerbs);
        }

        foreach (var verb in new[] { "mouse_move", "mouse_click", "key", "tap", "combo", "text", "touch" })
        {
            Assert.Contains(verb, SecureChannel.InputVerbs);
        }
    }

    /// <summary>
    /// A frame has to survive the trip as one line. A pretty-printed message from the
    /// phone carries real newlines, and passing its raw text through would desynchronise
    /// the reader on the far side for every message after it.
    /// </summary>
    [Fact]
    public void Lines_never_contain_a_newline()
    {
        using var document = System.Text.Json.JsonDocument.Parse("{\n  \"t\": \"text\",\n  \"s\": \"a\\nb\"\n}");
        var line = SecureChannel.Line(document.RootElement);

        Assert.DoesNotContain('\n', line);
        Assert.Contains("\\n", line); // escaped inside the string value, not a separator
        Assert.Null(SecureChannel.ReadDesktop(line));
    }

    /// <summary>
    /// A JPEG has to survive base64 and the line protocol byte for byte — a mirror that
    /// shows a corrupted lock screen is worse than one that shows nothing.
    /// </summary>
    [Fact]
    public void Frames_round_trip_intact()
    {
        // Bytes chosen to include the ones that break naive framing: newline, carriage
        // return, quote, backslash and a high byte.
        var jpeg = new byte[] { 0xFF, 0xD8, 0x0A, 0x0D, 0x22, 0x5C, 0x00, 0x80, 0xFF, 0xD9 };
        var line = SecureChannel.FrameLine(jpeg, 7);

        Assert.DoesNotContain('\n', line);
        Assert.True(SecureChannel.IsFrame(line));
        Assert.Equal(jpeg, SecureChannel.ReadFrame(line));
    }

    /// <summary>A refusal is still a frame answer, so the mirror stops waiting on it.</summary>
    [Fact]
    public void A_failed_frame_is_a_frame_with_no_image()
    {
        var line = SecureChannel.FrameEmptyLine(7);

        Assert.True(SecureChannel.IsFrame(line));
        Assert.Null(SecureChannel.ReadFrame(line));
        Assert.Equal(7, SecureChannel.ReadId(line));
    }

    [Fact]
    public void Frame_requests_carry_their_monitor_and_settings()
    {
        Assert.Equal(SecureChannel.FrameRequest,
            SecureChannel.TypeOf(SecureChannel.FrameRequestLine(1, 1280, 60, 3)));
        Assert.Contains("\"w\":1280", SecureChannel.FrameRequestLine(null, 1280, 60, 3));
        Assert.Contains("\"mon\":1", SecureChannel.FrameRequestLine(1, 1280, 60, 3));
        Assert.Equal(3, SecureChannel.ReadId(SecureChannel.FrameRequestLine(1, 1280, 60, 3)));
    }

    /// <summary>
    /// A frame request is the one non-input verb allowed across, and it must stay out of
    /// the injection allowlist: nothing that reaches <c>Dispatch</c> should answer to it.
    /// </summary>
    [Fact]
    public void A_frame_request_is_not_an_input_verb()
    {
        Assert.DoesNotContain(SecureChannel.FrameRequest, SecureChannel.InputVerbs);
    }

    [Fact]
    public void Desktop_lines_round_trip()
    {
        Assert.Equal("Winlogon", SecureChannel.ReadDesktop(SecureChannel.DesktopLine("Winlogon")));
        Assert.Null(SecureChannel.ReadDesktop("{\"t\":\"pong\"}"));
        Assert.Null(SecureChannel.ReadDesktop("not json"));
    }

    /// <summary>
    /// A frame answer only counts for the request that asked for it — otherwise a capture
    /// that overran its deadline is handed to the next tick, and every frame after it is
    /// one behind.
    /// </summary>
    [Fact]
    public void Frame_answers_carry_the_id_they_answer()
    {
        Assert.Equal(11, SecureChannel.ReadId(SecureChannel.FrameLine([0xFF, 0xD8], 11)));
        Assert.NotEqual(SecureChannel.ReadId(SecureChannel.FrameLine([0xFF, 0xD8], 11)),
            SecureChannel.ReadId(SecureChannel.FrameLine([0xFF, 0xD8], 12)));
        Assert.Null(SecureChannel.ReadId("{\"t\":\"frame\"}"));
    }

    /// <summary>
    /// A window handle must not cross. The secure desktop has none of the user's windows,
    /// but the handle still resolves to the rectangle that window occupies — so a tap meant
    /// for the lock screen would be scaled into some app's old position.
    /// </summary>
    [Fact]
    public void Forwarded_input_never_carries_a_window_handle()
    {
        using var document = System.Text.Json.JsonDocument.Parse(
            "{\"t\":\"mouse_move_abs\",\"nx\":0.5,\"ny\":0.5,\"win\":123456,\"mon\":1}");
        var line = SecureChannel.Line(document.RootElement);

        Assert.DoesNotContain("win", line);
        Assert.Contains("\"mon\":1", line);
        Assert.Contains("\"nx\":0.5", line);
    }

    /// <summary>
    /// The service logs the type of a message it refused, and that string came off the
    /// phone's socket into a file written by SYSTEM. A newline in it would forge log lines.
    /// </summary>
    [Fact]
    public void Logged_remote_text_cannot_forge_a_log_line()
    {
        var forged = SecureChannel.Safe("x\n12:00:00 [0] service: desktop is Default");

        Assert.DoesNotContain('\n', forged);
        Assert.DoesNotContain('\r', forged);
        Assert.Equal("<none>", SecureChannel.Safe(null));
        Assert.True(SecureChannel.Safe(new string('a', 500)).Length <= 64);
    }

    private static List<SecurityIdentifier> Granted(PipeSecurity security) =>
        security.GetAccessRules(true, false, typeof(SecurityIdentifier))
            .Cast<PipeAccessRule>()
            .Where(rule => rule.AccessControlType == AccessControlType.Allow)
            .Select(rule => (SecurityIdentifier)rule.IdentityReference)
            .Distinct()
            .ToList();
}
