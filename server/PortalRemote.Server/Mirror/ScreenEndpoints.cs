using System.ComponentModel;
using System.Runtime.InteropServices;
using PortalRemote.Auth;
using PortalRemote.Config;
using PortalRemote.Input;

namespace PortalRemote.Mirror;

/// <summary>
/// `/screen/*` — a JPEG of the desktop, either one frame or an MJPEG stream.
/// MJPEG (<c>multipart/x-mixed-replace</c>) rather than H.264/WebRTC: one long-lived
/// HTTP response with no negotiation, no codec dependency on either side, and it
/// degrades gracefully — a slow client just gets fewer frames.
/// </summary>
public static class ScreenEndpoints
{
    private const string Boundary = "portalframe";

    /// <summary>Defaults tuned for a phone over Wi-Fi: readable, not sharp.</summary>
    private const int DefaultFps = 12;
    private const int DefaultWidth = 1280;
    private const int DefaultQuality = 60;

    public static void MapScreenEndpoints(this WebApplication app, ServerConfig config)
    {
        var group = app.MapGroup("/screen").AddEndpointFilter(new TokenAuth.RequireTokenFilter(config));

        // Which displays exist, so the phone can offer a picker instead of guessing.
        group.MapGet("/monitors", () => Results.Ok(new
        {
            monitors = WinInput.Displays().Select(d => new
            {
                index = d.Index,
                name = d.Name,
                primary = d.Primary,
                width = d.Bounds.Width,
                height = d.Bounds.Height
            })
        }));

        // Single frame — handy for `curl`-checking the capture path without a client.
        group.MapGet("/frame", (int? monitor, int? width, int? quality) =>
            Results.File(
                ScreenCapture.Jpeg(monitor, ClampWidth(width), ClampQuality(quality)),
                "image/jpeg"));

        group.MapGet("/mjpeg", async (HttpContext context, ILoggerFactory loggerFactory,
                                      int? monitor, int? fps, int? width, int? quality) =>
        {
            var log = loggerFactory.CreateLogger("PortalRemote.Screen");
            var peer = context.Connection.RemoteIpAddress?.ToString() ?? "<unknown>";

            var frameWidth = ClampWidth(width);
            var frameQuality = ClampQuality(quality);
            var rate = Math.Clamp(fps ?? DefaultFps, 1, 30);

            context.Response.ContentType = $"multipart/x-mixed-replace; boundary={Boundary}";
            context.Response.Headers.CacheControl = "no-store";

            log.LogInformation("Mirror started for {Peer} (monitor {Monitor}, {Fps}fps, {Width}px, q{Quality})",
                peer, monitor?.ToString() ?? "primary", rate, frameWidth, frameQuality);

            var ct = context.RequestAborted;
            // PeriodicTimer paces off a fixed period rather than sleeping after each
            // frame, so capture/encode time is absorbed instead of added to the interval.
            using var ticker = new PeriodicTimer(TimeSpan.FromSeconds(1.0 / rate));
            var frames = 0;
            var reportedCaptureFailure = false;

            try
            {
                do
                {
                    byte[] jpeg;
                    try
                    {
                        jpeg = ScreenCapture.Jpeg(monitor, frameWidth, frameQuality);
                        reportedCaptureFailure = false;
                    }
                    catch (Exception ex) when (ex is Win32Exception or ExternalException or InvalidOperationException)
                    {
                        // GDI capture fails outright while the workstation is locked or a
                        // UAC prompt owns the secure desktop. Skip the frame and keep the
                        // response open so the mirror simply resumes when the desktop
                        // comes back, instead of dropping the client into a retry loop.
                        if (!reportedCaptureFailure)
                        {
                            log.LogWarning("Screen capture unavailable ({Reason}); holding the stream open", ex.Message);
                            reportedCaptureFailure = true;
                        }
                        continue;
                    }

                    await context.Response.WriteAsync(
                        $"--{Boundary}\r\nContent-Type: image/jpeg\r\nContent-Length: {jpeg.Length}\r\n\r\n", ct);
                    await context.Response.Body.WriteAsync(jpeg, ct);
                    await context.Response.WriteAsync("\r\n", ct);
                    await context.Response.Body.FlushAsync(ct);
                    frames++;
                } while (await ticker.WaitForNextTickAsync(ct));
            }
            catch (OperationCanceledException)
            {
                // The phone navigated away or the socket dropped — normal end of stream.
            }
            finally
            {
                log.LogInformation("Mirror ended for {Peer} after {Frames} frames", peer, frames);
            }
        });
    }

    private static int ClampWidth(int? width) => Math.Clamp(width ?? DefaultWidth, 320, 3840);

    private static int ClampQuality(int? quality) => Math.Clamp(quality ?? DefaultQuality, 20, 95);
}
