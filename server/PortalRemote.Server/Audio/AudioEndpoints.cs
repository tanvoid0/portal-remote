using System.Diagnostics;
using System.Runtime.InteropServices;
using PortalRemote.Auth;
using PortalRemote.Config;

namespace PortalRemote.Audio;

/// <summary>
/// `/audio/stream` — what the PC is playing, as an endless chunked response of raw
/// 16-bit PCM. The phone plays it straight into an `AudioTrack`, which makes it a
/// wireless speaker for the PC.
///
/// Raw PCM, not Opus or AAC: same argument as the mirror's MJPEG. It is ~190KB/s on a
/// LAN that has a hundred times that, and it costs neither side an encoder, a decoder,
/// a negotiation or a dependency. The rate and channel count come back in headers so
/// the phone configures itself from the device rather than from an assumption.
///
/// One capture per client rather than a shared hub — WASAPI is happy to open several
/// loopback streams on one endpoint, and a fan-out would be state to own for a case
/// (two phones as one stereo pair) that isn't the point of this.
/// </summary>
public static class AudioEndpoints
{
    /// <summary>How often the capture buffer is drained. The device period is ~10ms, so
    /// this is one poll per packet — anything shorter is a busier loop for the same audio.</summary>
    private const int PollMs = 10;

    /// <summary>
    /// How long the device must produce nothing before silence is synthesised for it.
    /// Long enough that it cannot fire between two real packets, short enough that the
    /// phone's buffer never runs dry waiting for the next track to start.
    /// </summary>
    private const int SilenceHoldMs = 40;

    /// <summary>Ceiling on one padding write, so a stall cannot become a burst.</summary>
    private const int MaxPadMs = 200;

    public static void MapAudioEndpoints(this WebApplication app, ServerConfig config)
    {
        var group = app.MapGroup("/audio").AddEndpointFilter(new TokenAuth.RequireTokenFilter(config));

        group.MapGet("/stream", async (HttpContext context, ILoggerFactory loggerFactory) =>
        {
            var log = loggerFactory.CreateLogger("PortalRemote.Audio");
            var peer = context.Connection.RemoteIpAddress?.ToString() ?? "<unknown>";

            LoopbackCapture capture;
            try
            {
                capture = new LoopbackCapture();
            }
            catch (Exception ex) when (ex is InvalidOperationException or NotSupportedException or COMException)
            {
                // 503 with the reason in the body: the phone shows this verbatim, and
                // "no audio output device" is something the user can actually act on.
                log.LogWarning("Audio capture unavailable for {Peer}: {Reason}", peer, ex.Message);
                context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
                await context.Response.WriteAsync(ex.Message, context.RequestAborted);
                return;
            }

            using (capture)
            {
                context.Response.ContentType = "application/octet-stream";
                context.Response.Headers["X-Portal-Sample-Rate"] = capture.SampleRate.ToString();
                context.Response.Headers["X-Portal-Channels"] = capture.Channels.ToString();
                context.Response.Headers.CacheControl = "no-store";

                log.LogInformation("Speaker started for {Peer} ({Rate}Hz, {Channels}ch)",
                    peer, capture.SampleRate, capture.Channels);

                var ct = context.RequestAborted;
                var blockAlign = capture.Channels * 2;
                var silence = new byte[capture.ByteRate * MaxPadMs / 1000];
                // Restarted on every real packet, so it measures how long the device has
                // been quiet — never how long this loop has been running.
                var idle = Stopwatch.StartNew();
                var padded = 0L;
                var sent = 0L;
                var streamTime = Stopwatch.StartNew();

                try
                {
                    while (!ct.IsCancellationRequested)
                    {
                        var chunk = capture.Read();
                        if (chunk.Count > 0)
                        {
                            await context.Response.Body.WriteAsync(chunk.AsMemory(), ct);
                            await context.Response.Body.FlushAsync(ct);
                            sent += chunk.Count;
                            idle.Restart();
                            padded = 0;
                            // Drain whatever else is queued before sleeping: a poll that
                            // returns one packet when three are waiting falls behind and
                            // never catches up.
                            continue;
                        }

                        if (idle.ElapsedMilliseconds >= SilenceHoldMs)
                        {
                            // Silence is sent rather than simply not sent. Three things
                            // hang on it: the phone's buffer stays primed, so the next
                            // track starts instantly instead of after a refill; an idle
                            // TCP connection cannot be reaped by something in between;
                            // and the phone can tell "nothing is playing" from "this
                            // stopped working", which from a stalled socket it cannot.
                            var owed = (long)(idle.Elapsed.TotalSeconds * capture.ByteRate) - padded;
                            var pad = (int)Math.Min(owed, silence.Length);
                            pad -= pad % blockAlign;
                            if (pad > 0)
                            {
                                await context.Response.Body.WriteAsync(silence.AsMemory(0, pad), ct);
                                await context.Response.Body.FlushAsync(ct);
                                padded += pad;
                                sent += pad;
                            }
                        }

                        await Task.Delay(PollMs, ct);
                    }
                }
                catch (OperationCanceledException)
                {
                    // The phone stopped listening or the socket dropped — normal end.
                }
                catch (Exception ex) when (ex is InvalidOperationException or COMException)
                {
                    // Switching the PC's output device invalidates this capture client
                    // (AUDCLNT_E_DEVICE_INVALIDATED). Ending the response is the whole
                    // recovery: the phone reconnects on its own and the new stream opens
                    // against whatever the default device now is — which is also exactly
                    // what the user asked for by switching it.
                    log.LogInformation("Speaker stream for {Peer} ended: {Reason}", peer, ex.Message);
                }
                finally
                {
                    var seconds = streamTime.Elapsed.TotalSeconds;
                    log.LogInformation("Speaker ended for {Peer}: {Seconds:F1}s, {Sent:F1}MB",
                        peer, seconds, sent / 1024d / 1024d);
                }
            }
        });
    }
}
