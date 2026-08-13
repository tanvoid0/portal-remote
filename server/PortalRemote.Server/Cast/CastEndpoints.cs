using System.Net.WebSockets;
using System.Reflection;
using System.Text;
using PortalRemote.Auth;
using PortalRemote.Config;

namespace PortalRemote.Cast;

/// <summary>
/// `/cast/*` — the receiver page and the socket it holds open. Serving the page from
/// here rather than shipping a file means the URL is all a TV needs: no sideloading,
/// no store, no app.
/// </summary>
public static class CastEndpoints
{
    private static readonly string ReceiverHtml = LoadReceiverHtml();

    public static void MapCastEndpoints(this WebApplication app, ServerConfig config)
    {
        var group = app.MapGroup("/cast").AddEndpointFilter(new TokenAuth.RequireTokenFilter(config));

        // Token rides in the query string here, not a header: this URL gets typed into
        // a TV remote, and a browser tab cannot set an Authorization header on its own
        // navigation. Same trade-off the mirror's <img> endpoints already make.
        group.MapGet("/receiver", () => Results.Content(ReceiverHtml, "text/html; charset=utf-8"));

        group.Map("/ws", async (HttpContext context, ILoggerFactory loggerFactory) =>
        {
            var log = loggerFactory.CreateLogger("PortalRemote.Cast");

            if (!context.WebSockets.IsWebSocketRequest)
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                await context.Response.WriteAsync("/cast/ws expects a WebSocket upgrade");
                return;
            }

            var peer = context.Connection.RemoteIpAddress?.ToString() ?? "<unknown>";
            using var socket = await context.WebSockets.AcceptWebSocketAsync();
            CastHub.Instance.Add(socket);
            log.LogInformation("Cast receiver attached: {Peer}", peer);

            try
            {
                // A receiver that attaches mid-film should show the film, not a
                // "ready to cast" screen — e.g. after the page reloads.
                if (CastHub.Instance.NowPlaying is { } resume) CastHub.Instance.Command(resume);

                // The receiver reports what it is actually doing (playing, position,
                // duration) — status frames are small and self-contained, so a single
                // buffer with no reassembly is enough. Oversized or partial frames are
                // dropped rather than accumulated.
                var buffer = new byte[4096];
                while (socket.State == WebSocketState.Open)
                {
                    var result = await socket.ReceiveAsync(buffer, context.RequestAborted);
                    if (result.MessageType == WebSocketMessageType.Close) break;
                    if (result is { EndOfMessage: true, MessageType: WebSocketMessageType.Text, Count: > 0 })
                        CastHub.Instance.OnStatus(Encoding.UTF8.GetString(buffer, 0, result.Count));
                }
            }
            catch (OperationCanceledException) { }
            catch (WebSocketException ex)
            {
                log.LogInformation("Cast receiver {Peer} dropped: {Reason}", peer, ex.Message);
            }
            finally
            {
                CastHub.Instance.Remove(socket);
                log.LogInformation("Cast receiver detached: {Peer}", peer);
            }
        });
    }

    /// <summary>The receiver page ships inside the exe so the single-file publish stays single-file.</summary>
    private static string LoadReceiverHtml()
    {
        var assembly = Assembly.GetExecutingAssembly();
        using var stream = assembly.GetManifestResourceStream("PortalRemote.Cast.receiver.html")
            ?? throw new InvalidOperationException("receiver.html was not embedded in the build");
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }
}
