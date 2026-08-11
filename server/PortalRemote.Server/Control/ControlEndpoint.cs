using System.Buffers;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using PortalRemote.Auth;
using PortalRemote.Config;
using PortalRemote.Input;

namespace PortalRemote.Control;

/// <summary>
/// The <c>/control</c> WebSocket: a stream of JSON messages driving mouse,
/// keyboard and media input.
/// </summary>
public static class ControlEndpoint
{
    /// <summary>Close code sent when the client presents a bad or missing token.</summary>
    private const WebSocketCloseStatus Unauthorized = (WebSocketCloseStatus)4401;

    /// <summary>Cap on a single frame's payload; control messages are tiny.</summary>
    private const int MaxMessageBytes = 64 * 1024;

    private const int ReceiveBufferBytes = 8 * 1024;

    public static void MapControlEndpoint(this WebApplication app, ServerConfig config, ConnectionState connectionState)
    {
        app.Map("/control", async (HttpContext context, ILoggerFactory loggerFactory) =>
        {
            var log = loggerFactory.CreateLogger("PortalRemote.Control");

            if (!context.WebSockets.IsWebSocketRequest)
            {
                context.Response.StatusCode = StatusCodes.Status400BadRequest;
                await context.Response.WriteAsync("/control expects a WebSocket upgrade");
                return;
            }

            var peer = context.Connection.RemoteIpAddress?.ToString() ?? "<unknown>";

            // Reject before the upgrade so an unpaired client cannot hold a socket open.
            if (!TokenAuth.IsAuthorized(context, config))
            {
                log.LogWarning("Rejected /control from {Peer}: bad token", peer);
                connectionState.OnAuthRejected();
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                return;
            }

            using var socket = await context.WebSockets.AcceptWebSocketAsync();
            log.LogInformation("Client connected: {Peer}", peer);
            connectionState.OnConnected();

            try
            {
                await SendHelloAsync(socket, context.RequestAborted);
                await PumpAsync(socket, log, context.RequestAborted);
            }
            catch (OperationCanceledException)
            {
                // Server shutting down or client vanished; nothing to report.
            }
            catch (WebSocketException ex)
            {
                log.LogInformation("Client {Peer} dropped: {Reason}", peer, ex.Message);
            }
            finally
            {
                log.LogInformation("Client disconnected: {Peer}", peer);
                connectionState.OnDisconnected();
            }
        });
    }

    private static async Task SendHelloAsync(WebSocket socket, CancellationToken ct)
    {
        var (width, height) = WinInput.ScreenSize();
        await SendJsonAsync(socket, new
        {
            t = "hello",
            name = Environment.MachineName,
            version = ServerInfo.Version,
            screen = new { width, height }
        }, ct);
    }

    /// <summary>Read messages until the client closes the socket.</summary>
    private static async Task PumpAsync(WebSocket socket, ILogger log, CancellationToken ct)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(ReceiveBufferBytes);
        var pending = new MemoryStream();

        try
        {
            while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                var result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), ct);

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, null, ct);
                    return;
                }

                pending.Write(buffer, 0, result.Count);

                if (pending.Length > MaxMessageBytes)
                {
                    await socket.CloseAsync(
                        WebSocketCloseStatus.MessageTooBig, "message too large", ct);
                    return;
                }

                if (!result.EndOfMessage) continue;

                var text = Encoding.UTF8.GetString(pending.GetBuffer(), 0, (int)pending.Length);
                pending.SetLength(0);
                await HandleAsync(socket, text, log, ct);
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            await pending.DisposeAsync();
        }
    }

    private static async Task HandleAsync(WebSocket socket, string text, ILogger log, CancellationToken ct)
    {
        JsonElement message;
        try
        {
            using var document = JsonDocument.Parse(text);
            message = document.RootElement.Clone();
        }
        catch (JsonException)
        {
            await SendErrorAsync(socket, "malformed json", ct);
            return;
        }

        if (message.ValueKind != JsonValueKind.Object)
        {
            await SendErrorAsync(socket, "expected a json object", ct);
            return;
        }

        try
        {
            var reply = InputActions.Dispatch(message);
            if (reply is not null) await SendJsonAsync(socket, reply, ct);
        }
        catch (UnknownMessageException ex)
        {
            await SendErrorAsync(socket, ex.Message, ct);
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException
                                       or System.ComponentModel.Win32Exception)
        {
            // The input call failed but the session is still usable; report and continue.
            log.LogError(ex, "Input dispatch failed");
            await SendErrorAsync(socket, $"input failed: {ex.Message}", ct);
        }
    }

    private static Task SendErrorAsync(WebSocket socket, string detail, CancellationToken ct) =>
        SendJsonAsync(socket, new { t = "error", detail }, ct);

    private static Task SendJsonAsync(WebSocket socket, object payload, CancellationToken ct)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(payload);
        return socket.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, ct);
    }
}
