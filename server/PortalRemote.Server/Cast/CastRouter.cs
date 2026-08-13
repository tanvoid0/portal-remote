using PortalRemote.Input;

namespace PortalRemote.Cast;

/// <summary>
/// Every cast target this PC knows about, and where a cast goes — steps 4c and 4k of
/// <c>docs/phase4-casting.md</c>.
///
/// Nothing outside this file branches on protocol. A Roku, a DLNA renderer, a receiver
/// page, mpv and <c>ShellExecute</c> are all <see cref="IRemotePlayer"/>s; adding the
/// next one is a class and a line in <see cref="Discover"/>, not an edit to the message
/// dispatcher, the DLNA renderer or the phone.
///
/// <b>Discovery is on demand, not a background loop.</b> SSDP is multicast chatter and
/// a TV that is off is not going to appear on its own; the phone asks for a scan when
/// the user opens the picker, and the answer is cached until it does again.
/// </summary>
public static class CastRouter
{
    /// <summary>How long a scan's results stay good enough to answer with.</summary>
    private static readonly TimeSpan ScanCacheFor = TimeSpan.FromSeconds(60);

    /// <summary>Always present, in the order an unaddressed cast tries them.</summary>
    private static readonly IRemotePlayer[] Local = [new ReceiverPlayer(), new MpvRemotePlayer(), new ShellPlayer()];

    private static readonly object gate = new();
    private static IRemotePlayer[] discovered = [];
    private static DateTime lastScan = DateTime.MinValue;
    private static bool scanning;

    /// <summary>Where the last cast went, and therefore what the transport drives.</summary>
    private static IRemotePlayer? active;

    /// <summary>
    /// This PC's own DLNA renderer id, when that is switched on — set from
    /// <c>Program.cs</c>, the same way mpv's path is.
    ///
    /// It exists so discovery can throw our own renderer away. We answer our own
    /// <c>M-SEARCH</c>, so with the renderer enabled the PC finds *itself* and offers
    /// "cast to this PC" a second time, by the long way round. Selecting it is not
    /// merely redundant: <see cref="Dlna.DlnaEndpoints"/> hands what it receives straight
    /// back to this router, so a transport command recurses into the loop it just came
    /// from and dies on the HTTP timeout — measured at exactly 5s, surfacing on the phone
    /// as "no cast receiver is attached".
    /// </summary>
    public static string? OwnRendererUuid { get; set; }

    private static CancellationTokenSource? polling;

    /// <summary>
    /// Raised when the target list or the active target changes, so the phone's picker
    /// updates without asking. Wired to the share hub's broadcast in <c>Program.cs</c>,
    /// exactly like <see cref="CastHub.Changed"/>.
    /// </summary>
    public static event Action<object>? TargetsChanged;

    /// <summary>
    /// Something other than a receiver page is holding a cast. <see cref="CastHub"/>
    /// asks this to decide whether the phone should be drawing transport at all — it can
    /// see its own sockets, but not an mpv window or a TV across the room.
    /// </summary>
    public static bool LiveElsewhere
    {
        get
        {
            var current = active;
            return current is not null && current.Kind != "receiver" && current.Live;
        }
    }

    /// <summary>
    /// Play <paramref name="url"/>. With no <paramref name="targetId"/> this keeps the
    /// behaviour that predates the target list: best control first — an attached receiver
    /// page, then mpv, then the shell. A LAN device is never picked automatically;
    /// putting a video on a television nobody asked for is not a fallback.
    /// </summary>
    public static (string Url, string Via, string Target, string Name) Cast(string url, string? title, string? targetId)
    {
        var checkedUrl = CastLauncher.Validate(url);
        var player = Resolve(targetId);

        player.Load(checkedUrl, title);
        SetActive(player);
        return (checkedUrl, player.Kind, player.Id, player.Name);
    }

    private static IRemotePlayer Resolve(string? targetId)
    {
        if (!string.IsNullOrWhiteSpace(targetId))
        {
            return All().FirstOrDefault(p => p.Id == targetId)
                ?? throw new UnknownMessageException($"no cast target called '{targetId}' — scan again?");
        }

        return Local.FirstOrDefault(p => p.Available)
            // ShellPlayer is always available, so this is unreachable — but a router with
            // no route is worth a real error rather than a null reference.
            ?? throw new UnknownMessageException("no cast target is available");
    }

    /// <summary>
    /// Drive whatever the cast actually landed on. False when nothing is attached —
    /// which is an error worth reporting rather than a silent no-op, since there is
    /// nothing on the other end to have obeyed.
    /// </summary>
    public static bool Transport(string action, double? to, double? by, double? level, bool? muted)
    {
        // A live target owns the transport outright. Falling through when it refuses
        // would be worse than the refusal: a Roku has no absolute seek, and "seek" then
        // landing on a receiver page that happens to be open would scrub a completely
        // different screen. False here means "this target cannot do that", which is
        // exactly what the phone should be told.
        if (active is { Live: true } current)
        {
            if (!current.Command(action, to, by, level, muted)) return false;
            if (action == "stop") SetActive(null);
            return true;
        }

        // Nothing addressed, or what was addressed has gone. Fall back to the local
        // routes in preference order — that is what keeps a receiver page opened *after*
        // the cast, or an mpv adopted from a previous run, drivable, which is how this
        // behaved before targets existed.
        foreach (var player in Local)
        {
            if (!player.Live) continue;
            if (!player.Command(action, to, by, level, muted)) continue;

            SetActive(action == "stop" ? null : player);
            return true;
        }

        return false;
    }

    /// <summary>
    /// The <c>cast_targets</c> message: everything castable right now, and which one is
    /// holding the current cast. Same shape whether the phone asked for it or it was
    /// pushed, so there is one message type rather than two.
    /// </summary>
    public static object Snapshot()
    {
        var current = active;
        bool sweeping;
        lock (gate) sweeping = scanning;

        return new
        {
            t = "cast_targets",
            active = current?.Id,
            // The phone shows this rather than guessing from a timer: a short list and a
            // list that isn't finished look identical, and only this end knows which.
            scanning = sweeping,
            targets = All().Where(p => p.Available).Select(p => new
            {
                id = p.Id,
                name = p.Name,
                kind = p.Kind,
                seek = p.Caps.Seek,
                volume = p.Caps.Volume,
                status = p.Caps.Status,
            }).ToArray(),
        };
    }

    /// <summary>
    /// Look for Rokus and DLNA renderers on the LAN. Cached: reopening the picker
    /// shouldn't cost two seconds and a burst of multicast, but a <paramref name="force"/>
    /// from a pull-to-refresh should.
    /// </summary>
    public static async Task ScanAsync(bool force = false)
    {
        lock (gate)
        {
            if (scanning) return;
            if (!force && DateTime.UtcNow - lastScan < ScanCacheFor) return;
            // Claimed before the scan rather than after, so two phones opening the picker
            // at once don't both flood the network.
            lastScan = DateTime.UtcNow;
            scanning = true;
        }

        try
        {
            var players = await Discover();

            lock (gate)
            {
                // Keep the instance that is actually playing: replacing it with an
                // equal-but-new one would lose the state its poll loop and its play/pause
                // toggle need.
                var live = active;
                discovered = live is not null && live.Kind is "roku" or "dlna"
                    ? [live, .. players.Where(p => p.Id != live.Id)]
                    : players;
            }
        }
        finally
        {
            // In a finally because the phone's spinner is driven by this: a sweep that
            // threw would otherwise leave the picker saying "Scanning…" forever.
            lock (gate) scanning = false;
        }

        Publish();
    }

    /// <summary>
    /// The two SSDP searches, run together. Each hit is then asked what it is over its
    /// own protocol, because neither search reply carries a usable name.
    /// </summary>
    private static async Task<IRemotePlayer[]> Discover()
    {
        var patience = TimeSpan.FromSeconds(3);
        var rokuHits = Ssdp.SearchAsync(Ssdp.RokuTarget, patience);
        var dlnaHits = Ssdp.SearchAsync(Ssdp.MediaRendererTarget, patience);
        await Task.WhenAll(rokuHits, dlnaHits);

        var rokus = await Task.WhenAll(rokuHits.Result.Select(RokuPlayer.ProbeAsync));
        var renderers = await Task.WhenAll(
            dlnaHits.Result.Where(IsSomeoneElse).Select(DlnaPlayer.ProbeAsync));

        return [.. rokus.OfType<IRemotePlayer>(), .. renderers.OfType<IRemotePlayer>()];
    }

    /// <summary>Not this PC answering its own search — see <see cref="OwnRendererUuid"/>.</summary>
    private static bool IsSomeoneElse(SsdpHit hit) =>
        OwnRendererUuid is not { Length: > 0 } mine ||
        !hit.Usn.Contains(mine, StringComparison.OrdinalIgnoreCase);

    private static IEnumerable<IRemotePlayer> All()
    {
        IRemotePlayer[] lan;
        lock (gate) lan = discovered;
        return [.. Local, .. lan];
    }

    /// <summary>
    /// Point the transport at <paramref name="player"/> and start or stop the poll loop
    /// to match. Only the LAN protocols are polled; the receiver page and mpv report on
    /// their own.
    /// </summary>
    private static void SetActive(IRemotePlayer? player)
    {
        CancellationTokenSource? stopped;
        lock (gate)
        {
            if (ReferenceEquals(active, player)) return;
            active = player;
            stopped = polling;
            polling = null;
        }

        stopped?.Cancel();
        stopped?.Dispose();

        if (player is not null && player.Kind is "roku" or "dlna")
        {
            var cancel = new CancellationTokenSource();
            lock (gate) polling = cancel;
            _ = Task.Run(() => PollLoop(player, cancel.Token));
        }

        Publish();
    }

    /// <summary>
    /// A second between polls, matching the 1 Hz the receiver page ticks at — the phone
    /// interpolates between them, so this is about staying honest, not about being smooth.
    /// </summary>
    private static async Task PollLoop(IRemotePlayer player, CancellationToken cancel)
    {
        try
        {
            while (!cancel.IsCancellationRequested)
            {
                await player.PollAsync();
                await Task.Delay(TimeSpan.FromSeconds(1), cancel);
            }
        }
        catch (OperationCanceledException)
        {
            // Something else was cast, or the cast was stopped.
        }
    }

    private static void Publish() => TargetsChanged?.Invoke(Snapshot());
}
