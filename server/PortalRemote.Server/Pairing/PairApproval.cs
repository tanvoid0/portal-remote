using PortalRemote.Config;

namespace PortalRemote.Pairing;

/// <summary>Body of <c>POST /pair/request</c> — the phone's own name, for the prompt.</summary>
public sealed record PairRequestBody(string? Device);

/// <summary>
/// Hands the pairing token to a phone that asks for it, but only after someone
/// clicks Allow on this PC.
///
/// That click is the whole security model, and it's the same bar the QR code
/// already sets: you have to be standing at the machine. It's what lets a phone
/// pair with a discovered PC without anyone typing a 32-character token.
/// </summary>
public sealed class PairApproval : IDisposable
{
    private readonly ServerConfig _config;

    // Same trick as TrayIcon's _sync: an invisible control whose only job is to
    // marshal off Kestrel's worker threads and onto the UI thread, which is the
    // only one allowed to put a dialog on screen.
    // Fully qualified: the PortalRemote.Control namespace shadows
    // System.Windows.Forms.Control by simple name.
    private readonly System.Windows.Forms.Control _sync = new();

    private int _prompting;

    /// <summary>Set by the tray once there's a window to own the dialog. Called on
    /// the UI thread with (device name, remote IP); returns true to allow.</summary>
    public Func<string, string, bool>? Prompt { get; set; }

    public PairApproval(ServerConfig config)
    {
        _config = config;
        _ = _sync.Handle; // force handle creation so BeginInvoke works immediately
    }

    /// <summary>The pairing token if the request was allowed, otherwise null.</summary>
    public async Task<string?> RequestTokenAsync(string? deviceName, string remoteIp)
    {
        var prompt = Prompt;
        if (prompt is null) return null;

        // One dialog at a time: without this, anything on the LAN could bury the
        // screen under pairing prompts.
        if (Interlocked.Exchange(ref _prompting, 1) == 1) return null;

        try
        {
            var device = SafeDeviceName(deviceName);
            var answered = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            try
            {
                _sync.BeginInvoke(new Action(() =>
                {
                    try { answered.SetResult(prompt(device, remoteIp)); }
                    catch (Exception ex) { answered.SetException(ex); }
                }));
            }
            catch (Exception ex) when (ex is ObjectDisposedException or InvalidOperationException)
            {
                // Shutting down — there's no UI left to ask, so nobody said yes.
                return null;
            }

            return await answered.Task ? _config.Token : null;
        }
        finally
        {
            Interlocked.Exchange(ref _prompting, 0);
        }
    }

    /// <summary>The phone chooses its own name, so this is untrusted text heading
    /// straight into a dialog: strip control characters and cap the length, or it
    /// could fake extra lines of prompt copy.</summary>
    private static string SafeDeviceName(string? raw)
    {
        var cleaned = new string((raw ?? string.Empty).Where(c => !char.IsControl(c)).ToArray()).Trim();
        if (cleaned.Length == 0) return "An unnamed phone";
        return cleaned.Length <= 40 ? cleaned : cleaned[..40] + "…";
    }

    public void Dispose() => _sync.Dispose();
}
