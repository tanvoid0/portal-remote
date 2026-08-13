# Phase 5 — Quick share: clipboard, links, images

The gap this closes: you have a link, a screenshot, or a paragraph on one device and
you want it on the other one, three feet away. Today that means emailing yourself,
or opening a chat app on both devices to talk to yourself in it — a round trip
through someone else's servers for a hand-off between two machines on the same desk.

Portal Remote is already paired, already authenticated, and already holds an open
socket between the two. Quick share is that socket used for the other obvious thing.

Built and shipped: LAN only. §5 is the plan for making it work away from the LAN,
which is deliberately *not* built — see §6 for why that order.

---

## 1. What it does

| From | To | How you trigger it | What happens on arrival |
|---|---|---|---|
| Phone | PC | System share sheet, from any app | Lands on the PC's clipboard; tray balloon says what arrived |
| Phone | PC | Share tab → "Send clipboard" | Same |
| PC | Phone | `Ctrl+Alt+V` anywhere | Lands on the phone's clipboard; heads-up notification |
| PC | Phone | Tray → "Send clipboard to phone" | Same |

Text, links, images and files all work in both directions. Links are told apart from
plain text so the useful action is *open* rather than *paste* — the one place a
notification tap does something different per kind.

Files land in `<share root>/Inbox/` on the PC and go to the system Downloads folder
on the phone (via `DownloadManager`, which gives a real progress notification for
free — the same route `FilesScreen` already uses).

## 2. Where it lives

**Server** (`server/PortalRemote.Server/`)

- `Share/ShareHub.cs` — the whole server side in one file: `ShareKind` (what a share
  is), `ShareItem` (one of them), `ClientSocket` (a `/control` socket with its sends
  serialized), `ShareHub` (the live socket list + the Inbox), and `ShareEndpoints`
  (`POST /share/text`, `POST /share/upload`, both token-authed).
- `Tray/Hotkey.cs` — a system-wide `Ctrl+Alt+V` on an invisible window of its own,
  since the tray app usually has no window open and the whole point is to work from
  inside whatever app you just copied from.
- `Tray/TrayIcon.cs` — clipboard write + balloon on arrival, clipboard read + push on
  the hotkey.

**Phone** (`android/`)

- `net/ShareApi.kt`, `net/ShareEntry.kt` — transport and the history model.
- `ui/ShareScreen.kt` — the list, the "Send clipboard" button, and the clipboard and
  notification helpers.
- `AndroidManifest.xml` — the `ACTION_SEND` intent filter. **This is the feature.**
  Everything else is plumbing behind it; the share sheet is how anyone actually
  reaches this without opening the app first.

## 3. Decisions worth keeping

- **The share sheet is the primary surface, the Share tab is the secondary one.**
  Having to open Portal Remote to share something into Portal Remote would defeat the
  point. The tab exists to show history and to catch the clipboard case, not as the
  way in.
- **Arrive first, announce second.** Both sides put the payload on the clipboard
  *before* raising the notification. The notification is a receipt, not a step — if
  you have to tap something to get the thing, this is no faster than email.
- **`ClientSocket` wraps every `/control` send.** A WebSocket permits exactly one
  send at a time and this socket now has two writers: the request pump answering a
  ping, and the hub pushing from an unrelated thread. That's an exception, not a race
  you get away with, so the wrapper is not optional. `NowPlaying` pushes through the
  same `BroadcastAsync` for the same reason.
- **Nothing is durable, but nothing in flight is dropped either.** No history on
  disk, no mailbox for a phone that isn't connected — a share is a hand-off between
  two devices in front of you. The phone keeps the last 50 in memory and forgets them
  on process death.

  What it *does* keep is the outgoing ones that haven't landed. `AppViewModel.pending`
  holds the send closure per entry id; `retryPending()` runs on every
  `ConnectionState.Connected`, so a share made with the PC asleep goes out by itself
  the moment it wakes. Three things this hinges on:
  - **Keyed off the control socket, not Android's network callbacks.** Reaching *this
    PC* is the condition that matters, and having Wi-Fi is not it. The socket already
    auto-reconnects and already follows the PC to a new DHCP address, so the retry
    trigger comes free with machinery that had to exist anyway.
  - **`inFlight` guards against a flapping link.** A reconnect can fire while an
    attempt is still running, and sending the same file twice is worse than sending it
    late.
  - **The payload lives in the closure, not on `ShareEntry`.** A file share's content
    uri stays captured, which is also the ceiling: the read permission an `ACTION_SEND`
    grant carries dies with the process, so retries work for the life of the app and no
    longer. Surviving a kill would mean copying the bytes into app storage at share
    time — real work, for the case where you kill the app between sharing and
    reconnecting.

  Queued items read "Waiting for your PC", deliberately not "Failed", and are coloured
  `text-secondary` rather than `danger`: nothing was lost, and the app will send it
  without being asked. Tapping one retries immediately, for when you can see the PC is
  back and don't want to wait for the socket to notice.
- **Balloon clicks reveal files, never launch them.** A paired phone can put any file
  in the Inbox; one click of a notification should not be able to run it. Explorer
  opens with the file *selected* (`explorer /select,`), and only `http`/`https` links
  are ever handed to `ShellExecute`.
- **`Ctrl+Alt+V`, not `Ctrl+Shift+V`.** The latter is paste-as-plain-text in most
  apps. If another program already owns the combination, `RegisterHotKey` fails, the
  tray menu quietly drops the shortcut hint, and the menu item still works — a
  shortcut that silently moves to a different key is worse than one that doesn't
  work.
- **Receiving with the app closed is not built.** It needs a foreground service, and
  a foreground service means a permanent "Portal Remote is running" notification and
  a wake-locked socket eating battery all day, to catch the case where you push
  something from the PC to a phone you're not currently holding. Not obviously worth
  it. If it ever is, that's the change: an FGS holding `WsClient`, nothing else moves.

## 4. Protocol

Phone → PC is plain HTTP, since the phone initiates and may be cold-starting:

```
POST /share/text     {"text": "...", "from": "Pixel 8"}
POST /share/upload   multipart: from=<device>, file=<the file>
```

PC → phone rides the control socket that is already open:

```json
{"t":"share","kind":"link","text":"https://…","from":"DESKTOP-1"}
{"t":"share","kind":"image","file":"clip.png","path":"Inbox/clip.png","size":81920,"from":"DESKTOP-1"}
```

`path` is relative, not a full URL: the phone already knows the address it dialled,
and the URL the PC would compose can be the wrong one on a machine with several
interfaces. The phone fetches it through the existing `/files/download`.

`ShareKind.forText` is duplicated on both sides on purpose (`ShareKind.ForText` in
C#, `ShareKind.forText` in Kotlin) so the sender can label an item without a round
trip. `ShareKindTest.kt` pins the Kotlin half; keep the two in step if either
changes, or an item can arrive classified differently from how it was sent.

---

## 5. Internet relay — the plan, not yet built

Everything above stops working the moment the phone is on mobile data. Making it
work anywhere is a different product with a different threat model, so it is written
down here rather than half-built into the LAN path.

### What has to exist

1. **A relay.** A small always-on service both devices dial *out* to — neither can
   accept an inbound connection through carrier NAT. WebSocket over TLS, one room per
   pairing, messages forwarded between the two members and never stored. This is a
   ~200-line service; the cost is that it has to *stay up*, which is the actual
   commitment being made.
2. **An account, or something that stands in for one.** Today the pairing token *is*
   the identity, and it's handed over on a LAN you already control. Over the internet
   the relay needs to know which two devices belong together without becoming a
   directory of everyone's devices. The cheapest honest answer: the QR pairing already
   exchanges a shared secret — derive a room id from it (`HKDF(secret, "room")`) and
   let the relay match on that. No accounts, no email, no password reset, and the
   relay learns a random-looking id rather than a person.
3. **End-to-end encryption, non-negotiable.** On the LAN, plaintext HTTP is defensible
   because the wire is your own house. Through a third-party relay it is not, and
   "the relay is ours so it's fine" is exactly the reasoning that makes a clipboard
   sync a data breach. Same pairing secret → symmetric key; encrypt the payload, let
   the relay forward ciphertext it cannot read. The relay must be useless to whoever
   runs it, including us.
4. **Fall back, don't switch.** Try the LAN address first, use the relay only when
   that fails. A phone on the same Wi-Fi should never round-trip through a data centre
   to reach a PC in the next room — that's slower, and it leaks the existence and
   timing of every share to a server that had no reason to know.

### What changes on each side

- **Server:** an outbound relay client alongside the Kestrel listener, sharing
  `ShareHub` — the hub already abstracts "every connected phone", so a relay-backed
  peer registers as one more `ClientSocket`-shaped thing. That interface is the reason
  this is tractable; keep it.
- **Phone:** `WsClient` grows a second transport and the follow-to-new-address logic
  in `AppViewModel` grows a third branch (LAN address → discovery → relay).
- **Both:** a key-agreement step folded into pairing, and a settings toggle. Relay
  access must be opt-in and revocable per device, and revoking it must actually close
  the room.

### Cost, honestly

Bandwidth is negligible (clipboard text and the occasional screenshot). The real
costs are an always-on service with uptime expectations, a TLS certificate and a
domain, and the fact that a relay outage now looks like *the product* being broken
even though the LAN path still works. That is why this is phase 6 and not phase 5.

## 6. What has actually been exercised

Server side, against a running tray app on the real port — not a mock:

| Check | Result |
|---|---|
| `POST /share/text` and `/share/upload` with no token | `401` both |
| `{"text":"https://example.com/x"}` | `kind: link` |
| `{"text":"look at https://example.com then paste this"}` | `kind: text` — a sentence containing a URL is not a link |
| Empty text / 300KB text | `400` both |
| PC clipboard after a text share | held the shared text |
| PC clipboard after an image share | held the image |
| Same filename uploaded 3× | `live-test.png`, `live-test (2).png`, `live-test (3).png` |
| Filenames `../../evil.exe`, `..\..\..\Windows\System32\evil.dll`, `C:\Windows\rooted.txt` | all flattened into `Inbox/`; nothing written outside it, verified by checking the three target paths afterwards |
| `notes.pdf` | `kind: file`, not `image` |

Not yet exercised: anything requiring a phone — the `ACTION_SEND` filter, the
notification, the PC→phone push, and the retry-on-reconnect queue. Those need a
device, and the retry path specifically wants testing by pulling Wi-Fi mid-upload
rather than by unit test (see §3 on why it isn't one).

## 7. Build order if picking this up

1. ✅ LAN share, both directions — done, this document's §1–4.
2. Live on it for a fortnight. The relay is only worth building if the answer to
   "how often was the phone off the Wi-Fi when I wanted this" is *often*.
3. Relay service + e2e crypto + pairing key agreement, in that order — the crypto
   before the relay is load-bearing, because retrofitting encryption onto a shipped
   plaintext relay means a flag day for every paired device.
4. Transport fallback in `WsClient`, then the settings toggle.
