using System.Runtime.InteropServices;
using PortalRemote.Auth;
using PortalRemote.Config;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace PortalRemote.Media;

/// <summary>
/// What the PC is playing right now — title/artist/album, cover art, and a position
/// that moves.
///
/// The source is the same one Windows' own volume flyout reads: the system media
/// transport controls session (SMTC). Any player that registers with it — Spotify,
/// browsers, VLC, Groove, most things — shows up here without knowing this app
/// exists. A player that doesn't register is invisible, and there is nothing to be
/// done about that from outside it.
///
/// Transport buttons are deliberately left on the media virtual-key path
/// (<see cref="Input.InputActions"/>): those already work. This adds the one thing
/// a key press cannot express — an absolute seek — plus the state to draw.
/// </summary>
public sealed class NowPlaying : IDisposable
{
    /// <summary>
    /// Floor between pushes. Some players report a new position several times a
    /// second; the phone interpolates between updates anyway, so anything faster
    /// than this is bandwidth spent on a number that already looks continuous.
    /// </summary>
    private static readonly TimeSpan MinPushInterval = TimeSpan.FromMilliseconds(500);

    /// <summary>Cover art is a thumbnail, not a wallpaper. Anything past this is a
    /// player doing something odd, and not worth holding in memory.</summary>
    private const int MaxArtBytes = 4 * 1024 * 1024;

    private readonly object _gate = new();

    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private GlobalSystemMediaTransportControlsSession? _session;

    private Track? _track;
    private byte[]? _art;
    private string _artType = "image/jpeg";

    /// <summary>Bumped whenever the art bytes change. The phone re-fetches
    /// <c>/media/art</c> when it sees a number it hasn't drawn yet, which is all the
    /// cache invalidation this needs — art is only ever "the current track's".</summary>
    private int _artRevision;

    private DateTimeOffset _lastPush = DateTimeOffset.MinValue;

    /// <summary>
    /// When play/pause last changed. A player reports its position only now and then,
    /// so on resume the newest timeline reading can be seconds old and *predate* the
    /// resume — extrapolating across that gap invents movement that never happened,
    /// and the bar jumps forward and then snaps back on the next push.
    /// </summary>
    private DateTimeOffset _playbackChangedAt = DateTimeOffset.MinValue;

    private bool _disposed;

    /// <summary>Raised with a ready-to-serialize payload when the state changes.
    /// Fires on a thread-pool thread.</summary>
    public event Action<object>? Changed;

    /// <summary>Everything from the async half of the API, cached so that
    /// <see cref="Snapshot"/> can stay synchronous.</summary>
    private sealed record Track(string? Title, string? Artist, string? Album, string? App);

    /// <summary>
    /// Attach to the session manager. Failures are swallowed: a PC with no media
    /// session — or a Windows build that won't hand one over — should lose the now
    /// playing card, not the server.
    /// </summary>
    public async Task StartAsync()
    {
        try
        {
            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            lock (_gate)
            {
                if (_disposed) return;
                _manager = manager;
            }
            manager.CurrentSessionChanged += OnCurrentSessionChanged;
            BindSession(manager.GetCurrentSession());
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException or TypeLoadException
                                       or NotSupportedException or UnauthorizedAccessException)
        {
            // No media session support on this machine; Snapshot() stays inactive.
        }
    }

    private void OnCurrentSessionChanged(
        GlobalSystemMediaTransportControlsSessionManager sender, CurrentSessionChangedEventArgs args) =>
        BindSession(sender.GetCurrentSession());

    /// <summary>Point at a different player (or at nothing) and start listening to it.</summary>
    private void BindSession(GlobalSystemMediaTransportControlsSession? session)
    {
        GlobalSystemMediaTransportControlsSession? previous;
        lock (_gate)
        {
            if (_disposed || ReferenceEquals(_session, session)) return;
            previous = _session;
            _session = session;
            _track = null;
            _art = null;
            _artRevision++;
        }

        if (previous is not null)
        {
            previous.MediaPropertiesChanged -= OnMediaPropertiesChanged;
            previous.PlaybackInfoChanged -= OnPlaybackInfoChanged;
            previous.TimelinePropertiesChanged -= OnTimelinePropertiesChanged;
        }

        if (session is null)
        {
            Push(force: true);
            return;
        }

        session.MediaPropertiesChanged += OnMediaPropertiesChanged;
        session.PlaybackInfoChanged += OnPlaybackInfoChanged;
        session.TimelinePropertiesChanged += OnTimelinePropertiesChanged;
        _ = RefreshTrackAsync(session);
    }

    private void OnMediaPropertiesChanged(
        GlobalSystemMediaTransportControlsSession sender, MediaPropertiesChangedEventArgs args) =>
        _ = RefreshTrackAsync(sender);

    private void OnPlaybackInfoChanged(
        GlobalSystemMediaTransportControlsSession sender, PlaybackInfoChangedEventArgs args)
    {
        lock (_gate) _playbackChangedAt = DateTimeOffset.UtcNow;
        // Play/pause is the one state change the user is watching for after pressing
        // a button, so it jumps the throttle queue.
        Push(force: true);
    }

    private void OnTimelinePropertiesChanged(
        GlobalSystemMediaTransportControlsSession sender, TimelinePropertiesChangedEventArgs args) =>
        Push(force: false);

    /// <summary>Re-read the title/artist/art, which is the async half of the API.</summary>
    private async Task RefreshTrackAsync(GlobalSystemMediaTransportControlsSession session)
    {
        Track? track = null;
        byte[]? art = null;
        var artType = "image/jpeg";

        try
        {
            var properties = await session.TryGetMediaPropertiesAsync();
            track = new Track(
                Blank(properties.Title),
                Blank(properties.Artist) ?? Blank(properties.AlbumArtist),
                Blank(properties.AlbumTitle),
                AppName(session.SourceAppUserModelId));
            (art, artType) = await ReadArtAsync(properties.Thumbnail);
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException or TimeoutException)
        {
            // The player went away between the event and the read. Whatever we have
            // is stale, so publish nothing rather than a half-track.
        }

        lock (_gate)
        {
            // A session change raced us; that bind will refresh on its own.
            if (!ReferenceEquals(_session, session)) return;
            _track = track;
            _art = art;
            _artType = artType;
            _artRevision++;
        }

        Push(force: true);
    }

    /// <summary>Pull the thumbnail out of its WinRT stream as plain bytes.</summary>
    private static async Task<(byte[]? Bytes, string ContentType)> ReadArtAsync(
        IRandomAccessStreamReference? reference)
    {
        if (reference is null) return (null, "image/jpeg");
        try
        {
            using var stream = await reference.OpenReadAsync();
            if (stream.Size == 0 || stream.Size > MaxArtBytes) return (null, "image/jpeg");

            var reader = new DataReader(stream.GetInputStreamAt(0));
            await reader.LoadAsync((uint)stream.Size);
            var bytes = new byte[stream.Size];
            reader.ReadBytes(bytes);
            var type = string.IsNullOrWhiteSpace(stream.ContentType) ? "image/jpeg" : stream.ContentType;
            return (bytes, type);
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException
                                       or ObjectDisposedException or OutOfMemoryException)
        {
            return (null, "image/jpeg");
        }
    }

    /// <summary>The current cover art, for the <c>/media/art</c> endpoint.</summary>
    public (byte[]? Bytes, string ContentType) CurrentArt()
    {
        lock (_gate) return (_art, _artType);
    }

    /// <summary>
    /// Jump to <paramref name="positionMs"/> from the start of the track. No-op if
    /// nothing is playing or the player doesn't allow seeking — the phone disables
    /// the control in that case, but the message can still arrive after the fact.
    /// </summary>
    public async Task SeekAsync(long positionMs)
    {
        GlobalSystemMediaTransportControlsSession? session;
        lock (_gate) session = _session;
        if (session is null) return;

        try
        {
            var timeline = session.GetTimelineProperties();
            var target = timeline.StartTime + TimeSpan.FromMilliseconds(Math.Max(0, positionMs));
            await session.TryChangePlaybackPositionAsync(target.Ticks);
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            // Player vanished mid-seek; the next push will show where it actually is.
        }
    }

    /// <summary>
    /// The state as a message, taken now. The position is advanced to this instant
    /// before sending rather than shipped with the PC's own timestamp: the phone's
    /// clock is not this clock, and a few seconds of skew between them would be a few
    /// seconds of wrong progress bar.
    /// </summary>
    public object Snapshot()
    {
        GlobalSystemMediaTransportControlsSession? session;
        Track? track;
        int artRevision;
        bool hasArt;
        DateTimeOffset playbackChangedAt;
        lock (_gate)
        {
            session = _session;
            track = _track;
            artRevision = _artRevision;
            hasArt = _art is not null;
            playbackChangedAt = _playbackChangedAt;
        }

        if (session is null || track is null) return Inactive;

        GlobalSystemMediaTransportControlsSessionPlaybackInfo info;
        GlobalSystemMediaTransportControlsSessionTimelineProperties timeline;
        try
        {
            info = session.GetPlaybackInfo();
            timeline = session.GetTimelineProperties();
        }
        catch (Exception ex) when (ex is COMException or InvalidOperationException)
        {
            return Inactive;
        }

        var playing = info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        var rate = info.PlaybackRate ?? 1.0;
        var duration = (timeline.EndTime - timeline.StartTime).TotalMilliseconds;
        var position = (timeline.Position - timeline.StartTime).TotalMilliseconds
                     + ElapsedSinceUpdate(timeline.LastUpdatedTime, playing ? rate : 0, playbackChangedAt);
        if (duration > 0) position = Math.Clamp(position, 0, duration);

        return new
        {
            t = "now_playing",
            active = true,
            playing,
            title = track.Title,
            artist = track.Artist,
            album = track.Album,
            app = track.App,
            positionMs = (long)position,
            durationMs = (long)Math.Max(0, duration),
            rate,
            canSeek = info.Controls.IsPlaybackPositionEnabled,
            canNext = info.Controls.IsNextEnabled,
            canPrev = info.Controls.IsPreviousEnabled,
            // Null rather than 0 when there is no art, so the phone can tell "this
            // track has no cover" from "cover number zero".
            art = hasArt ? artRevision : (int?)null
        };
    }

    private static object Inactive => new { t = "now_playing", active = false };

    /// <summary>
    /// How far the position has moved on its own since the player last reported it.
    /// Guarded against a player that never sets the timestamp, against a stale session
    /// left paused for hours, and against a reading taken before playback resumed —
    /// see <see cref="_playbackChangedAt"/>. All three would otherwise invent movement
    /// that never happened.
    /// </summary>
    private static double ElapsedSinceUpdate(
        DateTimeOffset lastUpdated, double rate, DateTimeOffset playbackChangedAt)
    {
        if (rate <= 0 || lastUpdated <= DateTimeOffset.UnixEpoch) return 0;
        if (lastUpdated < playbackChangedAt) return 0;
        var elapsed = (DateTimeOffset.UtcNow - lastUpdated).TotalMilliseconds * rate;
        return elapsed is > 0 and < 6 * 60 * 60 * 1000 ? elapsed : 0;
    }

    /// <summary>Publish the current state, unless an identical-looking push just went
    /// out — see <see cref="MinPushInterval"/>.</summary>
    private void Push(bool force)
    {
        var handler = Changed;
        if (handler is null) return;

        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            if (!force && now - _lastPush < MinPushInterval) return;
            _lastPush = now;
        }

        handler(Snapshot());
    }

    /// <summary>
    /// Windows identifies the player three different ways and none of them is a name:
    /// `vlc.exe`, or a store app's AUMID (`SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify`),
    /// or a bare hex id (Firefox's `308046B0AF4A39CB`). Take the readable part where
    /// there is one, and show nothing rather than the hex where there isn't.
    /// </summary>
    private static string? AppName(string? appUserModelId)
    {
        var id = Blank(appUserModelId);
        if (id is null) return null;

        // PackageFamilyName!AppId — the half after the bang is the one a human named.
        var bang = id.LastIndexOf('!');
        if (bang >= 0 && bang < id.Length - 1) id = id[(bang + 1)..];

        if (id.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) id = id[..^4];
        return id.All(Uri.IsHexDigit) ? null : id;
    }

    private static string? Blank(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();

    public void Dispose()
    {
        GlobalSystemMediaTransportControlsSession? session;
        GlobalSystemMediaTransportControlsSessionManager? manager;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            session = _session;
            manager = _manager;
            _session = null;
            _manager = null;
            _art = null;
        }

        if (manager is not null) manager.CurrentSessionChanged -= OnCurrentSessionChanged;
        if (session is null) return;
        session.MediaPropertiesChanged -= OnMediaPropertiesChanged;
        session.PlaybackInfoChanged -= OnPlaybackInfoChanged;
        session.TimelinePropertiesChanged -= OnTimelinePropertiesChanged;
    }
}

/// <summary>
/// `/media/art` — the current track's cover. Its own endpoint rather than base64 in
/// the WebSocket push, so the frequent little state messages stay small and the
/// occasional big image goes over HTTP where the phone can stream and cache it.
/// </summary>
public static class MediaEndpoints
{
    public static void MapMediaEndpoints(this WebApplication app, ServerConfig config, NowPlaying nowPlaying)
    {
        // The `rev` query parameter is the phone's cache key, not ours: there is only
        // ever one current cover, and asking for it is asking for that one.
        app.MapGet("/media/art", () =>
        {
            var (bytes, contentType) = nowPlaying.CurrentArt();
            return bytes is null ? Results.NotFound() : Results.Bytes(bytes, contentType);
        }).AddEndpointFilter(new TokenAuth.RequireTokenFilter(config));
    }
}
