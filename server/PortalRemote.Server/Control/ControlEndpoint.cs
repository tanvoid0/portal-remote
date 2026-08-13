using System.Buffers;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using PortalRemote.Ai;
using PortalRemote.Auth;
using PortalRemote.Config;
using PortalRemote.Devices;
using PortalRemote.Input;
using PortalRemote.Media;
using PortalRemote.Share;

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

    public static void MapControlEndpoint(
        this WebApplication app, ServerConfig config, ConnectionState connectionState, ShareHub shareHub,
        NowPlaying nowPlaying, AiHealth ai, AiAssistant assistant, DeviceRegistry devices)
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
                connectionState.OnAuthRejected(peer);
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                return;
            }

            // The first phone through the door. Recorded here rather than where the
            // token is handed out: a phone that scanned the QR took the token off the
            // screen and never asked this PC for it, so this socket is the earliest
            // point the PC can honestly say it is paired with anything.
            if (!config.Paired)
            {
                config.Paired = true;
                config.Save();
            }

            // Sent by the phone since 0.3.2; anything older stays unnamed and simply
            // isn't listed as a device, which is better than inventing a label for it.
            var deviceName = context.Request.Headers["X-Portal-Device"].ToString();

            using var socket = await context.WebSockets.AcceptWebSocketAsync();
            log.LogInformation("Client connected: {Peer} ({Device})", peer,
                string.IsNullOrWhiteSpace(deviceName) ? "unnamed" : deviceName);
            connectionState.OnConnected(peer);
            devices.Connected(deviceName, peer);

            // Every send on this socket goes through the wrapper — the share hub
            // pushes from an unrelated thread, and two concurrent SendAsync calls
            // on one WebSocket is an error, not a race you get away with.
            using var client = new ClientSocket(socket);
            shareHub.Add(client);

            try
            {
                await SendHelloAsync(client, config, context.RequestAborted);
                // Whatever is playing was playing before this phone connected, so
                // send it now rather than leaving the card blank until the next
                // track change.
                await client.SendJsonAsync(nowPlaying.Snapshot(), context.RequestAborted);
                // Same reasoning for the assistant: its tab is correct the instant it
                // opens, with no request of its own. Whatever we last learned — this
                // does not probe, so a cold connect is never held up by a dead port.
                await client.SendJsonAsync(ai.Snapshot(), context.RequestAborted);
                // The conversation lives on this PC now, so a client that has never seen
                // it — a reinstalled phone, the desktop window on its first open — is
                // handed the whole thing rather than starting an empty second chat.
                await client.SendJsonAsync(assistant.Conversation.Snapshot(), context.RequestAborted);
                await PumpAsync(socket, client, nowPlaying, ai, assistant, log, context.RequestAborted);
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
                shareHub.Remove(client);
                log.LogInformation("Client disconnected: {Peer}", peer);
                connectionState.OnDisconnected();
                devices.Disconnected(deviceName, peer);
            }
        });
    }

    private static async Task SendHelloAsync(ClientSocket client, ServerConfig config, CancellationToken ct)
    {
        var (width, height) = WinInput.ScreenSize();
        await client.SendJsonAsync(new
        {
            t = "hello",
            name = Environment.MachineName,
            // The phone stores this against the pairing so it can recognise this PC
            // again after its address changes.
            id = config.Id,
            version = ServerInfo.Version,
            // So the phone can wake this PC once it has been asleep. Null on a machine
            // with no ordinary LAN adapter, which the phone reads as "no wake button".
            mac = MacAddress.OfLanInterface(),
            screen = new { width, height }
        }, ct);
    }

    /// <summary>Read messages until the client closes the socket.</summary>
    private static async Task PumpAsync(
        WebSocket socket, ClientSocket client, NowPlaying nowPlaying, AiHealth ai, AiAssistant assistant,
        ILogger log, CancellationToken ct)
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
                await HandleAsync(client, nowPlaying, ai, assistant, text, log, ct);
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            await pending.DisposeAsync();
        }
    }

    private static async Task HandleAsync(
        ClientSocket client, NowPlaying nowPlaying, AiHealth ai, AiAssistant assistant, string text, ILogger log,
        CancellationToken ct)
    {
        JsonElement message;
        try
        {
            using var document = JsonDocument.Parse(text);
            message = document.RootElement.Clone();
        }
        catch (JsonException)
        {
            await SendErrorAsync(client, "malformed json", ct);
            return;
        }

        if (message.ValueKind != JsonValueKind.Object)
        {
            await SendErrorAsync(client, "expected a json object", ct);
            return;
        }

        // Handled here rather than in InputActions: seeking is an async call on the
        // media session, not a synchronous key press, and this is the layer that
        // already has both the session and a cancellation token.
        if (message.TryGetProperty("t", out var kind) && kind.ValueKind == JsonValueKind.String &&
            kind.GetString() == "seek")
        {
            if (!message.TryGetProperty("ms", out var ms) || !ms.TryGetInt64(out var positionMs))
            {
                await SendErrorAsync(client, "seek needs a numeric 'ms'", ct);
                return;
            }
            await nowPlaying.SeekAsync(positionMs);
            return;
        }

        // Also handled here rather than in InputActions, and for the same reason as
        // seek: probing is an async call with a cancellation token, and Dispatch is
        // neither. `retry` is a person pressing the button, which skips the backoff.
        if (kind.ValueKind == JsonValueKind.String && kind.GetString() == "ai_state")
        {
            var retry = message.TryGetProperty("retry", out var r) && r.ValueKind == JsonValueKind.True;
            await client.SendJsonAsync(await ai.CheckAsync(retry, ct), ct);
            return;
        }

        // Everything the assistant does answers on the *broadcast*, not to the client that
        // asked: the transcript lives on this PC and the phone and the desktop window are
        // both watching it. A reply typed on one appears on the other as it arrives.
        //
        // None of these are awaited into an answer here. Deciding takes as long as a local
        // model takes to think — tens of seconds is normal — and this is the socket
        // carrying mouse movement.
        if (kind.ValueKind == JsonValueKind.String && kind.GetString() is { } assistantMessage &&
            assistantMessage.StartsWith("ai_", StringComparison.Ordinal))
        {
            switch (assistantMessage)
            {
                case "ai_ask":
                    assistant.Ask(Text(message));
                    return;

                case "ai_regenerate":
                    assistant.Regenerate();
                    return;

                case "ai_stop":
                    assistant.Stop();
                    return;

                case "ai_clear":
                    assistant.Clear();
                    return;

                // A client that lost its place — a reconnect, a process restart — rather
                // than one that just connected, which is handed the transcript unasked.
                case "ai_history":
                    await client.SendJsonAsync(assistant.Conversation.Snapshot(), ct);
                    return;

                case "ai_confirm":
                {
                    var approved = new List<int>();
                    if (message.TryGetProperty("approved", out var list) && list.ValueKind == JsonValueKind.Array)
                        foreach (var index in list.EnumerateArray())
                            if (index.ValueKind == JsonValueKind.Number && index.TryGetInt32(out var value))
                                approved.Add(value);

                    assistant.Confirm(Id(message), approved);
                    return;
                }

                case "ai_cancel":
                    assistant.Cancel(Id(message));
                    return;
            }
        }

        try
        {
            var reply = InputActions.Dispatch(message);
            if (reply is not null) await client.SendJsonAsync(reply, ct);
        }
        catch (UnknownMessageException ex)
        {
            await SendErrorAsync(client, ex.Message, ct);
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException
                                       or System.ComponentModel.Win32Exception)
        {
            // The input call failed but the session is still usable; report and continue.
            log.LogError(ex, "Input dispatch failed");
            await SendErrorAsync(client, $"input failed: {ex.Message}", ct);
        }
    }

    /// <summary>What was typed, on whichever device typed it.</summary>
    private static string Text(JsonElement message) =>
        message.TryGetProperty("text", out var text) && text.ValueKind == JsonValueKind.String
            ? text.GetString() ?? string.Empty
            : string.Empty;

    /// <summary>The turn a message is about. Server-minted now — the transcript is the
    /// PC's, so two clients cannot disagree about what a plan is called.</summary>
    private static string Id(JsonElement message) =>
        message.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String
            ? id.GetString() ?? string.Empty
            : string.Empty;

    private static Task SendErrorAsync(ClientSocket client, string detail, CancellationToken ct) =>
        client.SendJsonAsync(new { t = "error", detail }, ct);
}
