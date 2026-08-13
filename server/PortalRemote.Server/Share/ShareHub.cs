using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text.Json;
using PortalRemote.Auth;
using PortalRemote.Config;
using PortalRemote.Files;

namespace PortalRemote.Share;

/// <summary>What a share is, which decides what the receiving side offers to do
/// with it — paste, open, or save.</summary>
public static class ShareKind
{
    public const string Text = "text";
    public const string Link = "link";
    public const string Image = "image";
    public const string File = "file";

    private static readonly string[] ImageExtensions =
        [".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"];

    /// <summary>A link is worth distinguishing from plain text: it's the one kind
    /// where the useful action is "open" rather than "paste".</summary>
    public static string ForText(string text)
    {
        var trimmed = text.Trim();
        return (trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) &&
               !trimmed.Any(char.IsWhiteSpace)
            ? Link
            : Text;
    }

    public static string ForFile(string fileName) =>
        ImageExtensions.Any(x => fileName.EndsWith(x, StringComparison.OrdinalIgnoreCase))
            ? Image
            : File;
}

/// <summary>One thing sent between the phone and this PC — a bit of text, a link,
/// an image or a file. Files live in the Inbox folder under the share root; the
/// item only carries the name.</summary>
public sealed record ShareItem(
    string Kind,
    string? Text,
    string? FileName,
    long Size,
    string From,
    DateTimeOffset At)
{
    /// <summary>Short one-line form for a notification balloon.</summary>
    public string Preview(int max = 120)
    {
        var body = (Text ?? FileName ?? Kind).ReplaceLineEndings(" ").Trim();
        return body.Length <= max ? body : body[..max] + "…";
    }
}

/// <summary>
/// A `/control` socket with its sends serialized. WebSockets allow exactly one
/// send at a time, and this one has two writers — the request pump answering a
/// ping, and <see cref="ShareHub"/> pushing a share from an unrelated thread.
/// </summary>
public sealed class ClientSocket(WebSocket socket) : IDisposable
{
    private readonly SemaphoreSlim _sendLock = new(1, 1);

    public async Task SendJsonAsync(object payload, CancellationToken ct)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(payload);
        await _sendLock.WaitAsync(ct);
        try
        {
            if (socket.State != WebSocketState.Open) return;
            await socket.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, ct);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public void Dispose() => _sendLock.Dispose();
}

/// <summary>
/// The quick-share exchange: phones POST to <c>/share/*</c> and this raises
/// <see cref="Received"/> for the tray to act on; the tray calls
/// <see cref="SendToPhonesAsync"/> to push the other way over the control sockets
/// already open.
///
/// Deliberately not durable — a share is a hand-off between two devices in front
/// of you, not a mailbox. Anything the phone missed while disconnected is still
/// on the PC (clipboard, Inbox folder).
/// </summary>
public sealed class ShareHub(ServerConfig config)
{
    /// <summary>Sub-folder of the share root that incoming shares land in — kept
    /// out of the user's own files so "what did my phone just send me" is one
    /// folder, not a scavenger hunt.</summary>
    public const string InboxFolder = "Inbox";

    private readonly ConcurrentDictionary<ClientSocket, byte> _clients = new();

    /// <summary>Raised on a Kestrel thread when a phone shares something to this
    /// PC. Subscribers must marshal to the UI thread themselves.</summary>
    public event Action<ShareItem>? Received;

    public bool HasClients => !_clients.IsEmpty;

    public void Add(ClientSocket client) => _clients[client] = 0;

    public void Remove(ClientSocket client) => _clients.TryRemove(client, out _);

    /// <summary>Absolute path of the Inbox, created on first use.</summary>
    public string InboxPath()
    {
        var path = Path.Combine(config.ResolvedShareRoot(), InboxFolder);
        Directory.CreateDirectory(path);
        return path;
    }

    public void Publish(ShareItem item) => Received?.Invoke(item);

    /// <summary>Push a share to every connected phone.</summary>
    public async Task SendToPhonesAsync(ShareItem item)
    {
        await BroadcastAsync(new
        {
            t = "share",
            kind = item.Kind,
            text = item.Text,
            file = item.FileName,
            size = item.Size,
            from = item.From,
            // A relative path, not a full URL: the phone already knows the address
            // it dialled, and the one the PC would compose can be the wrong one on
            // a machine with several interfaces.
            path = item.FileName is null ? null : $"{InboxFolder}/{item.FileName}"
        });
    }

    /// <summary>
    /// Send one message to every connected phone. Best-effort per socket: one phone
    /// dropping mid-send must not stop the others from getting it.
    /// </summary>
    public async Task BroadcastAsync(object payload)
    {
        foreach (var client in _clients.Keys)
        {
            try
            {
                await client.SendJsonAsync(payload, CancellationToken.None);
            }
            catch (Exception ex) when (ex is WebSocketException or ObjectDisposedException
                                           or OperationCanceledException or InvalidOperationException)
            {
                // That phone is gone; its own disconnect path will remove it.
            }
        }
    }

    /// <summary>Write bytes into the Inbox under a non-colliding name and return it.</summary>
    public async Task<string> SaveToInboxAsync(string requestedName, Stream content, CancellationToken ct = default)
    {
        var name = UniqueName(FilePaths.SafeFileName(requestedName) is { Length: > 0 } safe ? safe : "share.bin");
        var destination = Path.Combine(InboxPath(), name);
        await using var file = System.IO.File.Create(destination);
        await content.CopyToAsync(file, ct);
        return name;
    }

    /// <summary>`shot.png` -> `shot (2).png` rather than overwriting — two screenshots
    /// in a row are the normal case for this feature, not an edge case.</summary>
    private string UniqueName(string name)
    {
        var inbox = InboxPath();
        if (!System.IO.File.Exists(Path.Combine(inbox, name))) return name;

        var stem = Path.GetFileNameWithoutExtension(name);
        var extension = Path.GetExtension(name);
        for (var n = 2; n < 1000; n++)
        {
            var candidate = $"{stem} ({n}){extension}";
            if (!System.IO.File.Exists(Path.Combine(inbox, candidate))) return candidate;
        }
        return $"{stem} ({Guid.NewGuid():N}){extension}";
    }
}

public sealed record ShareTextBody(string? Text, string? From);

/// <summary>
/// `/share/*` — the phone's end of quick share. Text and links arrive as JSON,
/// images and files as multipart, both token-authed like every other endpoint.
/// </summary>
public static class ShareEndpoints
{
    /// <summary>Clipboard-sized, not document-sized. A phone that wants to send a
    /// megabyte of text is sending a file.</summary>
    private const int MaxTextChars = 256 * 1024;

    public static void MapShareEndpoints(this WebApplication app, ServerConfig config, ShareHub hub)
    {
        var group = app.MapGroup("/share").AddEndpointFilter(new TokenAuth.RequireTokenFilter(config));

        group.MapPost("/text", (ShareTextBody? body) =>
        {
            var text = body?.Text;
            if (string.IsNullOrEmpty(text))
                return Results.BadRequest(new { error = "share needs a non-empty 'text'" });
            if (text.Length > MaxTextChars)
                return Results.BadRequest(new { error = $"text over {MaxTextChars} characters — send it as a file" });

            var item = new ShareItem(
                ShareKind.ForText(text), text, null, text.Length,
                From(body?.From), DateTimeOffset.Now);
            hub.Publish(item);
            return Results.Ok(new { ok = true, kind = item.Kind });
        });

        group.MapPost("/upload", async (HttpRequest request, CancellationToken ct) =>
        {
            if (!request.HasFormContentType)
                return Results.BadRequest(new { error = "expected multipart/form-data" });

            var form = await request.ReadFormAsync(ct);
            var file = form.Files.FirstOrDefault();
            if (file is null) return Results.BadRequest(new { error = "no file in upload" });

            await using var stream = file.OpenReadStream();
            var saved = await hub.SaveToInboxAsync(file.FileName, stream, ct);

            var item = new ShareItem(
                ShareKind.ForFile(saved), null, saved, file.Length,
                From(form["from"].ToString()), DateTimeOffset.Now);
            hub.Publish(item);
            return Results.Ok(new { ok = true, file = saved, kind = item.Kind });
        });
    }

    private static string From(string? name) =>
        string.IsNullOrWhiteSpace(name) ? "your phone" : name.Trim();
}
