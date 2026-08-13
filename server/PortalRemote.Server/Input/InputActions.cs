using System.Text.Json;

namespace PortalRemote.Input;

/// <summary>Thrown for a message type or payload the server does not understand.</summary>
public sealed class UnknownMessageException(string message) : Exception(message);

/// <summary>Maps protocol messages onto <see cref="WinInput"/> calls.</summary>
public static class InputActions
{
    /// <summary>Virtual-key codes for the keys the phone UI exposes by name.</summary>
    public static readonly IReadOnlyDictionary<string, ushort> Vk = BuildVkTable();

    /// <summary>Actions accepted by the <c>media</c> message.</summary>
    private static readonly HashSet<string> MediaActions =
        ["play_pause", "next", "prev", "stop", "mute", "vol_up", "vol_down"];

    /// <summary>Transport actions the cast receiver page understands.</summary>
    private static readonly HashSet<string> PlayerActions =
        ["play", "pause", "toggle", "stop", "seek", "volume"];

    private static Dictionary<string, ushort> BuildVkTable()
    {
        var table = new Dictionary<string, ushort>(StringComparer.OrdinalIgnoreCase)
        {
            ["backspace"] = 0x08, ["tab"] = 0x09, ["enter"] = 0x0D, ["shift"] = 0x10,
            ["ctrl"] = 0x11, ["alt"] = 0x12, ["pause"] = 0x13, ["capslock"] = 0x14,
            ["esc"] = 0x1B, ["space"] = 0x20,
            ["pgup"] = 0x21, ["pgdn"] = 0x22, ["end"] = 0x23, ["home"] = 0x24,
            ["left"] = 0x25, ["up"] = 0x26, ["right"] = 0x27, ["down"] = 0x28,
            ["printscreen"] = 0x2C, ["insert"] = 0x2D, ["delete"] = 0x2E,
            ["win"] = 0x5B, ["apps"] = 0x5D,
            ["numlock"] = 0x90, ["scrolllock"] = 0x91,
            ["lshift"] = 0xA0, ["rshift"] = 0xA1, ["lctrl"] = 0xA2, ["rctrl"] = 0xA3,
            ["lalt"] = 0xA4, ["ralt"] = 0xA5,
            // Media / volume
            ["mute"] = 0xAD, ["vol_down"] = 0xAE, ["vol_up"] = 0xAF,
            ["next"] = 0xB0, ["prev"] = 0xB1, ["stop"] = 0xB2, ["play_pause"] = 0xB3,
            ["browser_back"] = 0xA6, ["browser_forward"] = 0xA7
        };
        for (ushort i = 1; i <= 24; i++) table[$"f{i}"] = (ushort)(0x6F + i); // F1..F24
        for (var c = 'a'; c <= 'z'; c++) table[c.ToString()] = (ushort)(c - 'a' + 0x41); // VK_A..VK_Z
        for (var c = '0'; c <= '9'; c++) table[c.ToString()] = c; // VK_0..VK_9 == ASCII '0'..'9'
        return table;
    }

    /// <summary>Resolve a key from either a numeric <c>vk</c> or a named <c>key</c>.</summary>
    public static ushort ResolveVk(JsonElement msg)
    {
        if (msg.TryGetProperty("vk", out var raw) && raw.ValueKind == JsonValueKind.Number)
        {
            var value = raw.GetInt32();
            if (value is < 0 or > 0xFF) throw new UnknownMessageException($"vk out of range: {value}");
            return (ushort)value;
        }

        var name = GetString(msg, "key");
        if (name is null || !Vk.TryGetValue(name, out var vk))
            throw new UnknownMessageException($"unknown key name: {name ?? "<missing>"}");
        return vk;
    }

    /// <summary>
    /// Apply one protocol message, returning a reply to send back if the message
    /// warrants one. Throws <see cref="UnknownMessageException"/> for malformed
    /// input so the caller can answer with an error frame instead of dropping the
    /// connection.
    /// </summary>
    public static object? Dispatch(JsonElement msg)
    {
        var type = GetString(msg, "t")
            ?? throw new UnknownMessageException("message is missing its 't' field");

        switch (type)
        {
            case "ping":
                return new { t = "pong", seq = GetInt(msg, "seq") };

            case "mouse_move":
                WinInput.MoveRelative(GetInt(msg, "dx") ?? 0, GetInt(msg, "dy") ?? 0);
                return null;

            case "mouse_move_abs":
            {
                // nx/ny (0..1 fractions of the monitor named by 'mon') take precedence
                // over pixel x/y — the screen mirror has no reason to know desktop pixels.
                var nx = GetDouble(msg, "nx");
                if (nx is not null)
                {
                    var ny = GetDouble(msg, "ny")
                        ?? throw new UnknownMessageException("mouse_move_abs with 'nx' also needs 'ny'");
                    WinInput.MoveAbsoluteNormalized(nx.Value, ny, GetInt(msg, "mon"));
                    return null;
                }

                WinInput.MoveAbsolute(
                    GetInt(msg, "x") ?? throw new UnknownMessageException("mouse_move_abs needs 'x'"),
                    GetInt(msg, "y") ?? throw new UnknownMessageException("mouse_move_abs needs 'y'"));
                return null;
            }

            case "mouse_click":
            {
                var button = GetString(msg, "btn") ?? "left";
                if (msg.TryGetProperty("down", out var down) &&
                    down.ValueKind is JsonValueKind.True or JsonValueKind.False)
                {
                    WinInput.MouseButton(button, down.GetBoolean());
                }
                else
                {
                    // No explicit state means a full press-and-release.
                    WinInput.MouseButton(button, true);
                    WinInput.MouseButton(button, false);
                }
                return null;
            }

            case "scroll":
                // dy/dx are raw wheel deltas; 120 == one notch, positive scrolls up/right.
                WinInput.Scroll(GetInt(msg, "dy") ?? 0, GetInt(msg, "dx") ?? 0);
                return null;

            case "key":
                WinInput.KeyEvent(ResolveVk(msg), GetBool(msg, "down") ?? true);
                return null;

            case "tap":
                WinInput.Tap(ResolveVk(msg));
                return null;

            case "combo":
            {
                var keys = ReadCombo(msg);
                if (keys.Count == 0)
                    throw new UnknownMessageException("combo needs a non-empty 'keys' list");
                // Press in order, release in reverse, so modifiers wrap the final key.
                foreach (var vk in keys) WinInput.KeyEvent(vk, true);
                for (var i = keys.Count - 1; i >= 0; i--) WinInput.KeyEvent(keys[i], false);
                return null;
            }

            case "text":
                WinInput.TypeText(GetString(msg, "s") ?? string.Empty);
                return null;

            case "cast":
            {
                // Not input, but it rides the same socket the phone already holds
                // open rather than earning an endpoint of its own.
                var url = GetString(msg, "url") ?? throw new UnknownMessageException("cast needs 'url'");
                var checkedUrl = Cast.CastLauncher.Validate(url);

                // A receiver page gives real transport control; ShellExecute just
                // throws the link at whatever is registered and forgets it. Prefer
                // the receiver whenever one is attached.
                if (Cast.CastHub.Instance.HasReceivers)
                {
                    Cast.CastHub.Instance.Load(checkedUrl, GetString(msg, "title"));
                    return new { t = "cast_ok", url = checkedUrl, via = "receiver" };
                }

                Cast.CastLauncher.Open(checkedUrl);
                return new { t = "cast_ok", url = checkedUrl, via = "shell" };
            }

            case "cast_status":
                // Same shape the hub pushes unprompted, so a phone that asks and a
                // phone that waits are looking at one message type, not two.
                return Cast.CastHub.Instance.Snapshot();

            case "player":
            {
                // Transport for whatever the receiver page is playing. Deliberately
                // separate from "media", which taps global media keys and therefore
                // lands on whatever window Windows thinks is playing.
                var action = GetString(msg, "action")
                    ?? throw new UnknownMessageException("player needs 'action'");
                if (!PlayerActions.Contains(action))
                    throw new UnknownMessageException($"unknown player action: {action}");
                if (!Cast.CastHub.Instance.HasReceivers)
                    throw new UnknownMessageException("no cast receiver is attached");

                Cast.CastHub.Instance.Command(new
                {
                    t = action,
                    to = GetDouble(msg, "to"),
                    by = GetDouble(msg, "by"),
                    level = GetDouble(msg, "level"),
                    muted = GetBool(msg, "muted")
                });
                return new { t = "player_ok", action };
            }

            case "power":
            {
                // Not input either, but same reasoning as "cast": it rides the socket
                // the phone already holds rather than earning an endpoint of its own.
                var mode = GetString(msg, "mode") ?? throw new UnknownMessageException("power needs 'mode'");
                Power.Apply(mode);
                return new { t = "power_ok", mode };
            }

            case "media":
            {
                var action = GetString(msg, "action");
                if (action is null || !MediaActions.Contains(action))
                    throw new UnknownMessageException($"unknown media action: {action ?? "<missing>"}");
                WinInput.Tap(Vk[action]);
                return null;
            }

            default:
                throw new UnknownMessageException($"unknown message type: {type}");
        }
    }

    private static List<ushort> ReadCombo(JsonElement msg)
    {
        var keys = new List<ushort>();
        if (!msg.TryGetProperty("keys", out var arr) || arr.ValueKind != JsonValueKind.Array)
            return keys;

        foreach (var item in arr.EnumerateArray())
        {
            keys.Add(item.ValueKind switch
            {
                JsonValueKind.Number => (ushort)item.GetInt32(),
                JsonValueKind.String => Vk.TryGetValue(item.GetString()!, out var vk)
                    ? vk
                    : throw new UnknownMessageException($"unknown key name: {item.GetString()}"),
                _ => throw new UnknownMessageException("combo keys must be numbers or names")
            });
        }
        return keys;
    }

    private static string? GetString(JsonElement msg, string name) =>
        msg.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static int? GetInt(JsonElement msg, string name) =>
        msg.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var i)
            ? i : null;

    private static double? GetDouble(JsonElement msg, string name) =>
        msg.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var d)
            ? d : null;

    private static bool? GetBool(JsonElement msg, string name) =>
        msg.TryGetProperty(name, out var v) && v.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? v.GetBoolean() : null;
}
