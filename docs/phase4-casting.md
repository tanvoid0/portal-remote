# Phase 4 — Casting: phone → PC

Research and design notes for turning Portal Remote into a Web-Video-Caster-style
app where the **PC is the screen** and the phone is the browser/remote — while
keeping everything Phases 0–3 already do (PC → phone control) working.

**Status:** 4a, 4b, 4e, 4g, 4l and the file-serving half of 4d are built — see §13.
Everything else in this document is still design.

---

## 1. What Web Video Caster actually does

Worth being precise, because the naïve mental model ("it mirrors the phone")
is wrong and would lead us to build the expensive thing first.

Web Video Cast is a **browser** that watches its own network traffic. When you
play a video on a web page, it sees the request for the media file, grabs that
URL, and hands **the URL** — not pixels — to the TV. The TV then fetches the
media itself and plays it. The app explicitly does no decoding and no
transcoding; the receiving device must handle the format.
([webvideocaster.com/integrations](https://www.webvideocaster.com/integrations),
[App Store listing](https://apps.apple.com/gb/app/web-video-cast-browser-to-tv/id1400866497))

Two consequences that shape everything below:

- **"Full original resolution" is free** in this model. Nothing is re-encoded;
  the receiver pulls the original file. Screen-encoding a phone at 1080p and
  shipping it over Wi-Fi is *worse* quality and ~100× the engineering.
- **The hard part is context, not transport.** A media URL lifted out of a
  page usually 403s if you fetch it naked — the site wants the same `Referer`,
  `Cookie` and `User-Agent` the browser sent. Web Video Caster passes custom
  headers along with the URL for exactly this reason. Any design that forgets
  this works on `sample.mp4` and fails on every real site.

So the primitive is: **URL + headers + position, handed from phone to PC.**
Screen mirroring is the *fallback* for content that has no fetchable URL, not
the main path.

---

## 2. What we already have that this reuses

| Existing piece | Reused as |
|---|---|
| `PairingService` / `PairApproval` / token auth | Same trust boundary — no second pairing flow |
| `DiscoveryResponder` (UDP 8765) | PC discovery for the cast picker |
| `/control` WebSocket + `Protocol.kt` | Cast commands ride the same socket, new `t` values |
| `InputActions` VK table (`play_pause`, `vol_up`, arrows, `enter`, `esc`) | TV-remote buttons, day one, zero server work |
| `ScreenEndpoints` MJPEG mirror | Stays as-is — PC → phone; the new direction is separate |
| `TrayIcon` / `MainForm` | Host for the "now playing" state and the player window |
| `docs/design-system.md` | Governs every new screen; §13 (chrome budget) matters for the browser |

The one thing that genuinely inverts: **the phone has to become an HTTP
server.** Today it is a pure client. Local files, and proxied web media with
cookies attached, both need the PC pulling bytes *from* the phone.

---

## 3. Target architecture

```
┌─────────────────────── PHONE ───────────────────────┐      ┌────────────── PC ──────────────┐
│                                                     │      │                                │
│  In-app browser (WebView)                           │      │  PortalRemote.Server           │
│    ├─ adblock (filter lists, shouldInterceptRequest) │      │    ├─ /control  (existing WS)  │
│    ├─ popup/redirect blocker                        │      │    │    + cast_* messages       │
│    └─ media sniffer ──┐                             │      │    ├─ Player  (mpv, IPC pipe)  │
│                       │ url + headers               │      │    ├─ Now-playing state        │
│  Local media picker ──┤                             │      │    └─ Power / WoL actions      │
│                       ▼                             │      │                                │
│  Cast controller ─────────── /control WS ──────────────────►│                                │
│                                                     │      │         ┌──────────────────┐   │
│  Local HTTP server :8766                            │◄─────┼─────────┤ mpv fetches media │   │
│    ├─ /f/<id>   local file, range requests          │      │         └──────────────────┘   │
│    ├─ /p/<id>   proxy w/ browser cookies+referer    │      │                                │
│    └─ /screen.h264  MediaProjection fallback        │      │  (or fetches origin directly)  │
│                                                     │      │                                │
│  TV remote UI  ────────────► existing key/media msgs├──────►│  WinInput                     │
└─────────────────────────────────────────────────────┘      └────────────────────────────────┘
```

Three media routes, in order of preference:

1. **Direct** — PC fetches the origin URL itself, with headers we forward.
   Best: no phone bandwidth, no battery, survives the phone sleeping.
2. **Proxied** — PC fetches `http://phone:8766/p/<id>`; the phone replays the
   request to the origin with its live cookie jar. For sites where the session
   can't be serialised into headers.
3. **Served** — local files on the phone, `/f/<id>` with `Range` support.

Route 1 is a strict superset in quality and a strict subset in complexity.
Try it first, fall back on 4xx.

---

## 4. Casting to everything — targets, protocols, and one abstraction

The goal isn't "cast to a PC", it's "cast to whatever is in the room". Web
Video Caster reaches Chromecast, Android TV, Fire TV, Roku, Apple TV, Samsung,
LG, Xbox and PS4 — and it does that by **implementing about six unrelated
protocols behind one picker**, not by finding one magic technology.

The cheapest row in that table is the one worth copying first: for anything it
has no protocol for, the user opens **`cast2tv.app`** in that device's browser
and the page becomes the receiver. That single trick covers every PC, smart TV,
console and tablet on the network with no per-vendor work at all.

### The target matrix

| Target | Protocol to speak | Openness | Effort |
|---|---|---|---|
| **Our PC** (this repo) | our `/control` WS + phone HTTP server | ours | §6 |
| **Anything with a browser** — PC, smart TV, console, tablet | **our own receiver web page** (served by the phone or the PC) | universal | **S** |
| **Chromecast / Google TV / Android TV / Nest Hub / Chromecast-built-in TVs** | Google Cast **sender**, Default Media Receiver `CC1AD845` | open — needs **no registration** | M |
| **Roku** (sticks, Roku TVs) | ECP: plain HTTP on :8060, SSDP discovery | fully open + documented | **S** |
| **DLNA renderers** — Xbox, PS4, many Samsung/Sony/Philips TVs, AV receivers, VLC, Kodi | UPnP `AVTransport` | open standard | M (one impl, many devices) |
| **LG webOS** | SSAP over `wss://tv:3001` | community-documented, TLS mandatory since 2023 | M |
| **Samsung Tizen** | DLNA + DIAL (proprietary Smart View for the rest) | partial | M |
| **Kodi** | JSON-RPC `Player.Open` | trivial | S |
| **Fire TV / Android TV** (best experience) | **our own receiver app**, our protocol | ours | M |
| **Apple TV** | AirPlay 2 | pairing crypto (pyatv-class work) | **L** |
| Miracast / Wi-Fi Display | — | **no app-usable API** | dead end |

### Correcting the Chromecast question

Three things get called "Chromecast" and they have different answers. Only one
is actually closed.

**(a) The Cast protocol, PC as *receiver* — not possible.**
CASTV2 does device authentication over `urn:x-cast:com.google.cast.tp.deviceauth`:
the sender challenges, and the receiver must answer with a signature and a
**platform certificate chain signed by a Google CA**. Third parties don't have
those keys. Reverse-engineering write-ups reach the same conclusion — a
server-side implementation is "pretty useless because device authentication
gets in the way", and the emulators that exist replay `AuthResponse` blobs
precomputed on a rooted Chromecast.
([Tristan Penman](https://tristanpenman.com/blog/posts/2025/03/22/chromecast-device-authentication/),
[node-castv2](https://github.com/thibauts/node-castv2),
[yingtongli.me](https://yingtongli.me/blog/2019/12/20/gcast-auth.html))
On top of that, the sender side needs an app ID registered in Google's Cast
Developer Console, tied to a certified receiver. **Close this door and don't
reopen it.**

**(b) Cast as a *sender*, to real Cast devices — fully open, and we should do it.**
The auth in (a) protects senders from fake receivers; it does not gate senders.
And the **Default Media Receiver (`CC1AD845`) requires no registration** — only
the Styled and Custom receivers need a Developer Console app ID
([Cast registration docs](https://developers.google.com/cast/docs/registration)).
So "load this URL on that device" works out of the box against every Chromecast,
Google TV box and Android TV with Chromecast-built-in. Two implementations:

- **Cast SDK** (`play-services-cast-framework`) — small integration, free
  `MediaRouter` wiring, requires Google Play services on the phone.
- **Raw CASTV2** — TLS socket + protobuf, ~600 lines, no Play services. What
  the BubbleUPnP/pychromecast class of software does.

Start with the SDK; keep the player interface (below) clean enough that swapping
in raw CASTV2 later is one adapter, not a rewrite.

**(c) The Cast *user experience* — yes, we can have this for every target.**
The cast button and route picker are `androidx.mediarouter`, separate from the
Cast SDK, and it takes **custom `MediaRouteProvider`s**. Every protocol we
support publishes its discovered devices as routes into the *same* picker.
The user sees one cast button and one list; they never learn that the Roku and
the PC are reached by completely different means.

**(d) The *idea* — URL handoff plus remote playback control — is what we build.**
Our own messages over the existing `/control` socket, for our own targets.

**Interop consolation prize: make the PC a DLNA/UPnP MediaRenderer.**
DLNA is open, and it's the protocol Web Video Caster and friends already use
for non-Cast targets. If the PC announces itself over SSDP with an `AVTransport`
service, then Web Video Caster, VLC, BubbleUPnP and most Android gallery apps
can cast to it **without us writing a single line of client code**. That makes
it both a real feature and a free test harness for the PC-side player — worth
pulling forward if you want to validate playback before the browser exists.
C# options: [genielabs/intel-upnp-dlna](https://github.com/genielabs/intel-upnp-dlna)
or hand-rolled (SSDP is ~150 lines; `AVTransport` is 4 SOAP actions that matter:
`SetAVTransportURI`, `Play`, `Pause`, `Seek`).

**Android TV later:** because routes 1–3 are plain HTTP plus a JSON control
socket, an Android TV receiver is the same protocol with a different UI. Don't
design anything Windows-specific into the wire format.

### The one abstraction that makes N protocols tractable

Everything above collapses into two interfaces. Write them **before** the second
protocol, not after — retrofitting is where this kind of app turns into a
`when (deviceType)` swamp.

```kotlin
data class CastTarget(
    val id: String, val name: String, val kind: Kind,   // PC, CAST, ROKU, DLNA, WEBOS, BROWSER…
    val caps: Caps,
)

data class Caps(                    // what this receiver can actually play
    val hls: Boolean, val dash: Boolean, val mkv: Boolean, val ac3: Boolean,
    val customHeaders: Boolean,     // almost always false — see the proxy note
    val subtitles: SubFormat?,      // Chromecast: WebVTT only
    val seek: Boolean, val volume: Boolean,
)

interface RemotePlayer {            // one adapter per protocol, nothing else branches
    suspend fun load(media: Media, startAt: Duration)
    suspend fun play(); suspend fun pause(); suspend fun stop()
    suspend fun seek(to: Duration); suspend fun setVolume(v: Float)
    val status: Flow<PlaybackStatus>   // position, duration, paused, buffering
}
```

The UI, the browser's cast button, the queue and the remote all talk to
`RemotePlayer`. `caps` is what lets the UI say *"this Chromecast can't play
MKV/AC3 — send it to the PC instead?"* before the user watches it fail.

### The phone proxy is what makes dumb receivers work

Roku, Chromecast and DLNA renderers **cannot send a `Referer` or a `Cookie`**.
They fetch the URL you give them, naked. Since §1 established that naked URLs
403 on most real sites, the proxy from §3 route 2 stops being a fallback and
becomes **the default path for every third-party target**: hand the receiver
`http://phone:8766/p/<id>`, and the phone attaches the live session when it
fetches the origin. Only our own PC player (and our own TV app) can be trusted
with headers directly.

Consequences worth designing for up front:

- **The phone must stay awake and on Wi-Fi for the whole film.** Foreground
  service, wake lock, `WifiLock`, a progress notification with a stop button.
  This is a real UX cost the direct path doesn't have — prefer direct whenever
  the target supports headers.
- **Subtitles get served by the phone too.** Convert SRT → WebVTT and expose it
  as a sibling URL; Chromecast requires WebVTT *and* CORS headers on the
  response, which means our phone server needs to emit `Access-Control-Allow-Origin`.
- **No transcoding**, same as Web Video Caster. If `caps` says the receiver
  can't decode it, say so — don't build an ffmpeg pipeline on a phone battery.

### Discovery — one aggregator, four mechanisms

| Mechanism | Finds |
|---|---|
| mDNS (`NsdManager`) | `_googlecast._tcp` (Cast + Android TV), `_airplay._tcp` (Apple TV), `_xbmc-jsonrpc-h._tcp` (Kodi), our own `_portalremote._tcp` |
| SSDP `M-SEARCH` to 239.255.255.250:1900 | `…MediaRenderer:1` (DLNA), `roku:ecp` (Roku), `…dial-multiscreen-org…` (DIAL / Samsung) |
| Our existing UDP 8765 broadcast | Portal Remote PCs |
| **Manual IP entry** | everything the above misses |

Keep manual entry permanently. Guest networks, VLANs and AP client-isolation
break multicast entirely, and when they do, every automatic mechanism fails at
once with no explanation. Two Android specifics that cause silent failure:
`WifiManager.MulticastLock` must be held or mDNS/SSDP replies never arrive, and
Android 13+ wants `NEARBY_WIFI_DEVICES` for some discovery paths.

### Case study: a Sony Bravia Android TV (the test device we have)

Worth writing out because it shows the pattern — one device is usually
reachable four different ways, and picking the right one per *job* beats
picking one per device.

| Job | Route | Notes |
|---|---|---|
| **Play media on it** | **Google Cast sender (4h)** | Sony Android TVs ship **Chromecast built-in**, so they are ordinary Cast receivers. `CC1AD845` works, no registration, no Sony-specific code. This is the main path. |
| Media, fallback | **DLNA (4j)** | Sony exposes a renderer under *Settings → Network → Home network setup → **Remote device / Renderer***. Useful when the Cast receiver rejects a format |
| **Remote control, power, inputs, volume** | **Bravia REST API + IRCC-IP** | `POST /sony/system`, `/sony/avContent` etc., JSON-RPC-ish, authenticated with an `X-Auth-PSK` header. IRCC-IP is the SOAP endpoint that emulates physical remote keys. ([Sony's own docs](https://pro-bravia.sony.net/develop/integrate/rest-api/spec/getting-started/)) |
| Power **on** | WoL / Sony "Remote start" | Same magic-packet story as the PC (§8) |
| Best-case, later | **Our own Android TV app (4n)** | It's Android — sideload the receiver APK and it speaks our protocol natively |

**Setup the TV needs** (once, by hand — worth putting in the app's help text):
*Settings → Network → Home network setup → IP Control → Authentication →
Normal and Pre-Shared Key*, then set the PSK, then enable *Simple IP Control*
and *Remote device / Renderer*.

The generalisable lesson: **media handoff and remote control are different
protocols on the same device.** Cast has no "change HDMI input" and no power-off;
IRCC has no "play this URL". The `CastTarget` in §4 therefore needs an optional
second capability — a *control* channel distinct from its *player* channel —
and the TV-remote screen (§8) should target that, not the PC's `WinInput`, when
the selected target is a TV.

### Dead ends — don't spend time here

- **Miracast / Wi-Fi Display from an app.** No public API to initiate it, and
  OEMs block what exists; Google's own `CastRemoteDisplay` is deprecated and
  slated for removal. If someone wants whole-screen phone → PC mirroring,
  **Windows already ships a Miracast receiver** (the Wireless Display optional
  feature) — point them at it rather than building it.
- **A fake Cast receiver on the PC** — see (a).
- **Bluetooth as a cast transport.** Nowhere near the bandwidth. No.

---

## 5. The browser

The single biggest chunk of work, and the thing that decides whether the app is
actually pleasant.

### Engine

| | Android `WebView` | GeckoView |
|---|---|---|
| Size | 0 (system) | +50–70 MB AAR |
| Request interception | `shouldInterceptRequest` — enough for URL blocking | Full WebExtension support (real uBlock Origin) |
| Cosmetic filtering | We inject CSS/JS ourselves | Free, correct |
| Video sniffing | Our own interceptor + injected JS | Same work |
| Risk | Behaviour varies with the user's WebView version | Self-contained, predictable |

**Recommendation: `WebView`.** It's already on the device, it's what Web Video
Caster itself uses, and the 35 MB APK doesn't triple. Revisit only if cosmetic
filtering turns out to be the thing that makes pages unusable.

### Adblock

Ready-made: [Edsuns/AdblockAndroid](https://github.com/Edsuns/AdblockAndroid) is
a filter-list engine built for exactly this (EasyList subscriptions, hooks into
`shouldInterceptRequest`). Adblock Plus's own
[libadblockplus-android](https://github.com/adblockplus/libadblockplus-android)
exists but is heavier and effectively unmaintained.

Roll-your-own is also viable and is a few hundred lines: parse EasyList into
(a) a domain-suffix hash set for the ~80 % of rules that are plain hostnames and
(b) a regex/substring list for the rest; check in `shouldInterceptRequest`,
return `WebResourceResponse("text/plain", "utf-8", empty)` on a hit. The reason
to consider it: filter matching runs on **every subresource request on a
background thread**, so it's the one place where a sloppy library will visibly
cost page-load speed.

Cosmetic rules (`##.ad-banner`) need CSS injection at `onPageFinished` plus a
`MutationObserver` for late DOM. Ship element-hiding as a second step; network
blocking alone removes most of the pain.

Lists to ship: EasyList + EasyPrivacy + a mobile annoyances list, cached in
DataStore/files with a weekly refresh, and a per-site "disable on this site"
toggle (sites *will* break; users need the escape hatch without leaving the app).

### Popup and redirect blocking

Native platform features cover more of this than expected:

- `WebSettings.setJavaScriptCanOpenWindowsAutomatically(false)` +
  `setSupportMultipleWindows(true)` and refuse in `onCreateWindow` — kills
  `window.open` popunders while still letting us honour a genuine user tap.
- `shouldOverrideUrlLoading` — block navigations not triggered by a real
  gesture. `WebResourceRequest.hasGesture()` is the signal for auto-redirects;
  it is the whole feature.
- Block non-`http(s)` schemes (`intent://`, `market://`) unless the user taps
  through — that's the "the page hijacked me into the Play Store" bug.

### Video detection

Two layers, both needed:

1. **Network sniffing** in `shouldInterceptRequest`: match on extension and
   `Content-Type` — `.m3u8` / `application/vnd.apple.mpegurl`, `.mpd`, `.mp4`,
   `.webm`, `.mkv`, `video/*`. Capture the request headers, plus
   `CookieManager.getCookie(url)` and the page URL as `Referer`. This finds
   almost everything.
2. **Injected JS**: `MutationObserver` over `<video>`/`<source>`, and a
   monkey-patch of `fetch`/`XMLHttpRequest.open` to catch manifest fetches the
   interceptor sees as opaque. Also reads title, duration and poster for the
   "now playing" card.

**Known limits — say these out loud in the UI, don't let users discover them:**

- **MSE/`blob:` streams** (YouTube, most modern players) hand `<video>` a blob;
  there is no single URL to cast. Options: cast the *manifest* if we caught it,
  or resolve server-side (see §6), or fall back to screen casting.
- **DRM (Widevine)** — Netflix, Disney+, Prime. Impossible by design: playback
  requires a certified device. Not a bug, don't chase it.
- **HLS on dumb receivers** — irrelevant for us, since our receiver is mpv,
  which handles HLS/DASH natively. This is a real advantage over casting to a
  cheap DLNA TV.

---

## 6. The PC-side player

Needs: HLS + DASH + MP4 + MKV, custom request headers, frame-accurate seek,
scriptable from the server process, and a window we can throw fullscreen on a
chosen monitor.

**Recommendation: mpv**, driven over a named pipe.

```
mpv.exe --input-ipc-server=\\.\pipe\portalremote-mpv --idle=yes --fullscreen
        --user-agent="<from phone>" --http-header-fields="Referer: ...,Cookie: ..."
```

Control is one line of JSON per command on the pipe
(`{"command":["set_property","pause",true]}`), and `observe_property` gives us
position/duration/paused to push back to the phone for the scrub bar. That is
the entire integration — maybe 150 lines of C#. Format coverage is ffmpeg's, so
"whatever the site serves" just works.

Alternatives considered:

- **WebView2** — already on every Windows 11 machine (zero install), but codec
  coverage is Edge's (no MKV, no AC3, HLS needs hls.js), and per-request headers
  mean wiring `WebResourceRequested`. Good enough for a proof of concept, wrong
  for the real thing.
- **LibVLCSharp** — comparable capability to mpv, heavier NuGet + native blobs,
  and a worse control surface than a JSON pipe.
- **`Process.Start(url)`** to the default player — genuinely the right first
  commit, to prove the handoff end to end before any player work.

**Distribution:** mpv is ~30 MB of exe + dlls. Don't bundle it into the
already-79 MB self-contained server by default — look for it next to
`PortalRemote.exe`, then on `PATH`, then a configured path, and fall back to the
default player with a "install mpv for full control" note. `publish.ps1` can
fetch it optionally.

**Server-side URL resolution (optional, your call):** `yt-dlp.exe` on the PC
turns a page URL into a direct stream URL, which solves the entire MSE/blob
class in one move — the phone would send the *page* URL and the PC resolves it.
It's the single highest-leverage item on this list, and it's also a
site-terms-of-service decision rather than a technical one, so it's flagged
rather than assumed. Same shape as the mpv integration: detect the binary, use
it if present.

---

## 7. Phone screen casting (the fallback path)

For content with no fetchable URL — a DRM-free app, a game, photos, or a site
whose player defeats the sniffer.

Pipeline: `MediaProjection` → `VirtualDisplay` at the phone's native resolution
→ `MediaCodec` H.264 encoder → **raw Annex-B elementary stream over HTTP** from
the phone's local server → `mpv http://phone:8766/screen.h264
--profile=low-latency --untimed`.

Raw ES rather than a container is the deliberate shortcut: `MediaMuxer` can't
write to a socket, fMP4 fragmenting is fiddly, and ffmpeg probes a bare H.264
stream fine. Cost: **no audio** in v1 (audio needs a container; internal audio
also needs `AudioPlaybackCapture`, API 29+, and apps can opt out of being
captured), and no seek — which is correct for a live mirror anyway.

**Platform constraints that are not negotiable (Android 14/15/16):**

- `mediaProjection` foreground service type is required, and the app must get
  **user consent before every capture session** — one `createVirtualDisplay()`
  call per consent. Tokens can't be cached or reused across restarts.
- Android 15 QPR1+ shows a prominent status-bar chip, and **projection stops
  automatically when the screen locks**. Design for the stream dying at any
  moment; make resuming one tap.
- The consent dialog offers single-app or whole-screen. Single-app capture is
  the better default here — casting one video app rather than your notifications.
([developer.android.com — Media projection](https://developer.android.com/media/grow/media-projection),
[Behavior changes: Android 14+](https://developer.android.com/about/versions/14/behavior-changes-14))

Latency will land somewhere around 0.5–1 s. Fine for watching, not fine for
gaming. WebRTC would fix it and costs an order of magnitude more work — later,
if ever.

---

## 8. TV remote

Mostly free. The server's VK table already has `up/down/left/right`, `enter`,
`esc`, `home`, `browser_back`, and the media/volume keys, and `Protocol.kt`
already has `tap`/`combo`/`media`.

One design decision worth getting right: **a remote button should target the
player, not the focused window.** Global media keys go wherever Windows decides.
When something is casting, route transport buttons down the mpv IPC pipe and
only fall back to global keys when nothing is playing. Otherwise "pause" pauses
Spotify while the movie keeps rolling.

New server messages needed (all small):

```
power     { action: "sleep" | "lock" | "monitor_off" | "shutdown" | "restart" }
launch    { app: "netflix" | ... }        # optional, later
```

`LockWorkStation`, `SetSuspendState`, `WM_SYSCOMMAND`/`SC_MONITORPOWER`, and
`shutdown.exe` respectively. **`shutdown` and `restart` need a confirm step on
the phone** — a stray tap on a remote should not kill an in-progress render.
Power-*on* is Wake-on-LAN: a magic packet to the PC's MAC, which we can capture
at pair time and store with the host. It only works if the user enables WoL in
their BIOS/NIC, so treat it as best-effort with a clear "didn't work? check
these" message.

---

## 9. The other direction — controlling the phone from the PC

You asked for fully bidirectional. There are three tiers, and they are very far
apart in cost.

**Tier 1 — app-level control (recommended).**
The PC sends commands *to the Portal Remote app*, not to Android: next video,
pause, back, open URL, pick from queue, type into the browser's address bar.
The `/control` socket is already bidirectional (the server sends `hello`),
so this is a new set of server→phone messages plus handlers in the app. No
special permissions, no policy risk, works while the phone is in your pocket.
Covers what people actually want from "control the phone from the PC" during
casting: driving the thing that's casting.

**Tier 2 — AccessibilityService.**
Real input injection without root: `dispatchGesture()` for taps/swipes,
`performGlobalAction()` for back/home/recents, plus MediaProjection for the
screen. This gives genuine phone-mirroring-and-control. Two caveats: Google Play
policy is hostile to accessibility APIs used for non-accessibility purposes
(irrelevant if this stays sideloaded, fatal if it doesn't), and the permission
prompt is appropriately scary because the permission genuinely is.

**Tier 3 — ADB / scrcpy.**
No app changes at all: enable wireless debugging, `adb connect`, and the PC
drives the phone with the full scrcpy pipeline. Best quality by a wide margin,
zero maintenance for us. Costs a per-boot pairing dance and leaves an ADB port
open on the LAN.
([scrcpy over TCP/IP](https://scrcpy.net/control-android-from-pc/))

**Recommendation: build Tier 1. Document Tier 3 as "if you want real
mirroring, use scrcpy — here's the command."** Tier 2 only if Tier 1 proves
insufficient in practice, since it's the one that carries lasting cost.

---

## 10. Security — what changes

Phase 4 meaningfully widens the blast radius. The current model is "the token
grants keyboard/mouse/screen on the PC." After this it also grants:

- **An open HTTP server on the phone.** Bind it, token it with the *same*
  pairing token, and bind to the LAN interface only. `/f/<id>` must serve only
  ids explicitly minted by a user action — never a path from the wire. The path
  traversal work in `FilePaths.cs` is the model to copy, and this time the
  attacker is on the PC side of the connection.
- **Cookies leaving the phone.** Route 1 forwards session cookies for a site to
  the PC in a JSON message over plaintext HTTP on the LAN. That is a real
  escalation from "moves my mouse" and belongs in the README's security notes.
  Prefer route 2 (proxy) for anything with an auth cookie: the cookie then never
  leaves the phone.
- **Filter lists are remote code-ish.** They're fetched over the network and
  drive request blocking and CSS injection. Fetch over HTTPS, pin to known list
  URLs, don't accept a list URL from a web page.
- **`shutdown`/`restart` from an unattended phone.** Confirm on the phone; log
  on the PC.
- **The in-app browser is a browser.** No password manager, no autofill, no
  syncing. If people log into sites in it, that's a credential store we now own.
  Consider making the browser's cookie jar wipeable in one tap and saying so.

---

## 11. Suggested build order

Each step is independently useful and independently testable. Sizes are rough
implementation effort, not calendar time.

**Core — the PC path and the product**

| # | Step | Size | Proves |
|---|---|---|---|
| **4a** | `cast_url` message + PC opens it in the default player. Phone side: paste-a-URL box. | S | The whole handoff, end to end, before any browser work |
| **4b** | mpv integration: IPC pipe, play/pause/seek/volume, position pushed back to the phone. "Now playing" screen with a scrub bar. | M | Real playback control; this is the spine everything else plugs into |
| **4c** | Extract `CastTarget` / `RemotePlayer` / `Caps` (§4) with the PC as the only adapter. | S | Nothing yet — but doing it **before** the second backend is what stops the `when (deviceType)` swamp |
| **4d** | Phone HTTP server (`/f/<id>` + `Range`, `/p/<id>` proxy, WebVTT subs, CORS) + local media picker. | M | The phone-as-server inversion — and it's the prerequisite for *every* third-party target |
| **4e** | In-app browser: WebView, adblock, popup blocking, media sniffer → cast button. Header/cookie forwarding. | **L** | The actual product. Biggest single chunk — treat as its own phase |
| **4f** | TV remote screen + power actions + WoL. | S | Cheap, high perceived value, mostly wiring existing messages |

**Targets — each one is an adapter behind 4c, so they're independent and parallelisable**

| # | Step | Size | Reach |
|---|---|---|---|
| **4g** | **Browser receiver page** — a static page + tiny WS, served by the phone or the PC. Open it on the TV/console/laptop; it plays what the phone sends. | **S** | Every device with a browser. Best reach-per-line in the whole document |
| **4h** | **Google Cast sender** (SDK, Default Media Receiver `CC1AD845`). | M | Every Chromecast, Google TV, Android TV, Nest Hub, Chromecast-built-in TV |
| **4i** | **Roku** ECP adapter (HTTP :8060) + SSDP discovery. | **S** | All Roku sticks and Roku TVs. Also gives you SSDP, which 4j reuses |
| **4j** | **DLNA sender** (`AVTransport`). | M | Xbox, PS4, a lot of Samsung/Sony/Philips TVs, AV receivers, VLC, Kodi |
| **4k** | Discovery aggregator + `androidx.mediarouter` providers → one cast button, one list. | M | Ties 4g–4j into a single UX |
| **4l** | **DLNA *renderer* on the PC** (the reverse of 4j). | M | Web Video Caster, VLC and gallery apps can target our PC |
| **4m** | LG webOS (SSAP), Samsung (DIAL), Kodi (JSON-RPC), Apple TV (AirPlay). | M / M / S / **L** | Per-vendor grind. Do them by demand, not by list-completeness |
| **4n** | Our own **Android TV / Fire TV receiver app**. | M | Best-quality target; reuses the PC protocol verbatim |

**Extras**

| # | Step | Size |
|---|---|---|
| **4o** | `MediaProjection` screen cast, phone → PC (§7) | M |
| **4p** | Tier-1 reverse control, PC drives the app (§9) | S |

**Do 4a first and don't skip it.** It's an afternoon, it's throwaway, and it
answers the only question that can invalidate the whole design: does a URL
lifted from a phone actually play on the PC, or does everything 403?

**Then do 4g before any other target.** A web page is one afternoon and it
reaches more devices than 4h–4n combined. It also forces `RemotePlayer` to be
honest, because a browser receiver and mpv have almost nothing in common.

---

## 12. Decisions needed from you

1. **yt-dlp on the PC** — in or out? It's the difference between "casts most
   sites" and "casts nearly everything", and it's a policy call, not a technical
   one (§6).
2. **mpv bundled or detected?** Bundling adds ~30 MB to a 79 MB exe and makes it
   work out of the box; detecting keeps the download honest and adds a setup step.
3. **Play Store, ever?** If yes, Tier 2 reverse control is off the table
   permanently and the adblocker needs care too. If it stays sideloaded, both
   are open.
4. **DLNA now or later?** Pulling 4f forward means you can drive the PC player
   with Web Video Caster itself while our browser is being written — a real test
   harness, at the cost of doing it before the thing it tests.
5. **Browser scope.** Tabs, history, bookmarks, downloads, incognito — a
   credible modern browser is a lot of surface. Suggest v1 = one tab, no
   history, no bookmarks, a home screen of user-added shortcuts, and let usage
   say what's missing.
6. **Cast SDK or raw CASTV2?** The SDK is far less work but pulls in Google Play
   services. Raw CASTV2 is ~600 lines and dependency-free. Only matters if you
   care about running on de-Googled phones (§4b).
7. **Where does the browser receiver page live?** Served by the PC (always on,
   needs the PC) or by the phone (works with no PC at all, but the phone must
   stay awake). The phone-hosted version makes the app useful without this repo's
   server at all — which may or may not be the product you want.

---

## 13. What is actually built (4a + 4g)

Verified against a running server and a real browser receiver, not just compiled.

**Wire protocol**, both on the existing `/control` socket:

```
-> {"t":"cast","url":"https://…/clip.mp4","title":"Big Buck Bunny"}
<- {"t":"cast_ok","url":"https://…/clip.mp4","via":"receiver"}   // or "shell"

-> {"t":"player","action":"seek","to":2.0}     // play|pause|toggle|stop|seek|volume
<- {"t":"player_ok","action":"seek"}

-> {"t":"cast_status"}
<- {"t":"cast_status","receiver":true,"status":{"paused":true,"position":0,
     "duration":10,"waitingForGesture":true,"error":0, …}}
```

The receiver reports its own state back up the same socket (on `playing`, `pause`,
`ended`, `seeked`, `volumechange`, `error`, `loadedmetadata`, plus a 1 Hz tick while
playing). Without it the server can only say "I sent a link somewhere" — it cannot
see inside a page it merely serves, which makes a blocked playback and a broken one
indistinguishable. `waitingForGesture` is what turns "it isn't playing" into "press
the button on that screen".

**Server** — `Cast/CastLauncher.cs` (validate + ShellExecute fallback),
`Cast/CastHub.cs` (attached receivers + last-cast state), `Cast/CastEndpoints.cs`
(`GET /cast/receiver`, `GET /cast/ws`), `Cast/receiver.html` (embedded in the exe
so the single-file publish stays single-file).

**Routing:** a cast goes to an attached receiver page when there is one, and falls
back to `ShellExecute` when there isn't. `player` without a receiver is an error
rather than a silent no-op — there is nothing to control.

**Android** — `CastUrl.normalize` (paste cleanup, https assumed for a bare host),
a "Cast a link" box on the Media tab, and the transport row that appears under it
once the PC acknowledges the cast. 10 JVM tests. Driven on a real device
(SM-S948B): typing a URL enables the cast button, tapping it lands the media on the
receiver page.

**Phone-side transport** — `cast_ok` carries `via`, and that is the only way the
phone can know whether there is anything to drive: the same `cast` message ends up
at a receiver page (controllable) or at `ShellExecute` (not). `CastState.controllable`
is that distinction, and the `shell` case says what to do about it instead of showing
buttons that could only ever come back `"no cast receiver is attached"`.

The controls are deliberately blind. The receiver reports nothing back, so play and
pause are separate buttons rather than one toggle displaying a state we would be
guessing at, and the skips are relative (`by`, not `to`) because there is no position
on this side to seek *to*. A scrub bar wants a status channel from the receiver —
that's what 4b's mpv `observe_property` gives, and inventing a weaker version of it
for the browser receiver first would be work thrown away.

The state clears on a `player_ok` for `stop`, on a `"no cast receiver"` error (the
page was closed at the other end), and on any disconnect, since a server restart takes
every receiver socket with it.

**Verified live:**

| Case | Result |
|---|---|
| Cast to attached receiver | `via:"receiver"`, 640×360 clip played to completion |
| `seek to:2` then `play` | position moved to 2s and advanced |
| `pause`, `volume level:0.25 muted:true` | applied on the element |
| Receiver page reloaded mid-film | resumed the same URL from `NowPlaying` |
| No receiver attached | `via:"shell"` — Edge opened; `player` → `"no cast receiver is attached"` |
| `javascript:alert(1)`, `file:///…/cmd.exe`, `ms-settings:` | rejected; no process launched |
| Unknown player action | `"unknown player action: bogus"` |
| **Phone → receiver**, typed on the device | button enabled as the URL parsed; media loaded on the receiver at 640×360 |
| Receiver page in **Chrome on Android** | attached, played inline, reported `position 4.0/10.0` — a phone or tablet is a cast target with no extra work |

**Mobile receivers have two limits worth designing around**, neither of which
applies to a PC or a TV:

- **Playback suspends when the tab backgrounds or the screen locks.** Fine for a
  tablet propped up on a desk, wrong for anything unattended.
- **iOS Safari makes `video.volume` read-only.** `player volume level:…` will
  silently do nothing there; `muted` still works. This is exactly what the `Caps`
  record in §4 exists to express — the phone should grey out a volume slider it
  knows the receiver will ignore rather than let it look broken.

(`playsinline` is already on the element, which is what stops iOS Safari yanking
playback into its own fullscreen player.)

**Two things this shook out that the design above did not predict:**

1. **A browser will not start playback until the page has been interacted with**, and
   this applies to the phone's `player play` too — a command arriving over a socket
   carries no user activation. So the receiver needs **one press on the receiving
   screen, once per page load**, before the phone can drive it. That is a real
   constraint on every browser-based receiver, not a bug, and the page now surfaces
   it as a full-screen focusable button (a TV remote has no pointer, but OK presses
   whatever has focus) rather than an invisible "tap anywhere" target. The first
   version relied on a document-level click listener and it was not obvious what to
   press once video filled the screen.
2. **Distinguish "blocked" from "broken".** The first version reported every
   `play()` rejection as an autoplay block, which made a 404 test URL look like a
   browser policy for a good while. It now separates the two, and before the page
   is tapped it assumes the policy — browsers report that block as `NotAllowedError`
   or `AbortError` depending on version.

**Finding the receiver** — the URL was only ever printed in the startup banner, which
is on screen only if the server was launched from a console. It's now a row in the
app window (`PairingService.ReceiverUrl`, one definition, used by both), with **Copy**
for typing it into a TV and **Open** for making this PC the target without typing
anything. Not a QR: the screens that most need this — TVs, consoles — have no camera,
and the ones that do have the pairing QR already. It carries the token, so it re-reads
after a rotation exactly like the QR does.

**HLS/DASH** — still not playable in the receiver, but no longer a blank failure: a
`.m3u8` the browser can't decode natively (everything except Safari) or any `.mpd` is
named as an adaptive stream, with the suggestion to cast it with no receiver open so
it lands in the PC's own player. Making it actually play means shipping hls.js/dash.js
inside the exe — ~500KB of library for a case mpv (4b) handles natively, so it waits
for someone to want it.

### The in-app browser (4e)

A full browser, not a viewport: tabs, private tabs, bookmarks, history, downloads,
fullscreen video, search-engine choice, and blocking that can be switched off per
site or globally. `ui/BrowserScreen.kt` + `ui/BrowserSession.kt` for the tab model,
`data/BrowserStore.kt` for persistence, and three pure helpers that carry the tests:
`net/AdBlock.kt`, `net/MediaSniffer.kt`, `net/Omnibox.kt`.

**Private tabs get a real profile.** WebView's cookie jar is process-global, so
"incognito" is a lie unless you use `androidx.webkit`'s `Profile` API — private tabs
run on a `portal-private` profile that is *deleted* when you leave the browser. The
API needs WebView 114+, so it is feature-detected: without it the tab still gets no
history, no persistent storage, no cache and no third-party cookies, and the empty-tab
text **says** that sites you're signed into will still recognise you rather than
implying privacy it can't deliver.

**The omnibox** is the other place a browser is judged constantly, so the
place/question decision is a pure function with its own tests: a space always means
search, an explicit non-`http(s)` scheme is refused rather than searched for, and a
bare word with no dot is a search — `recipes` must never become `https://recipes`.

- **Adblock** — EasyList + EasyPrivacy fetched on first open, parsed to a hostname
  set, cached in `filesDir` and refreshed weekly; a ~30-domain seed list blocks the
  obvious before the first fetch lands. Matching walks the domain's suffixes, so
  `stats.g.doubleclick.net` is caught by a `doubleclick.net` rule.
  **Only `||host^` rules are honoured.** Rules with options are skipped *deliberately*:
  `||example.com^$third-party` blocks that domain only in third-party context, and
  applying it unconditionally breaks the site's own requests. The cost is that
  cosmetic rules aren't applied either — ads don't load, but their empty boxes can
  remain. The upgrade is a real engine (adblock-rust via JNI), not more regex.
- **Popup blocking** — `javaScriptCanOpenWindowsAutomatically = false` plus an
  `onCreateWindow` that refuses anything without `isUserGesture`. A genuine
  "open in new tab" tap is honoured by loading it in the same tab, via the
  throwaway-WebView transport trick (`onCreateWindow` never tells you the target
  URL). Non-`http(s)` schemes are refused outright — `intent://` and `market://`
  are how a page throws you into an app store.
- **Media sniffing** — `shouldInterceptRequest` classifies by extension
  (`.m3u8`/`.mpd`/`.mp4`/`.webm`/`.mkv`/`.mov`), plus a `<video>` scan on
  `onPageFinished` for players that set `src` directly and never generate an
  interceptable request. HLS `.ts` segments are ignored on purpose: casting one gets
  you two seconds of film and *looks* like it worked. `blob:`/`data:` sources are
  detected and reported as "this player builds the video in the page itself" rather
  than offered as a cast that would fail.
- **Cast** — the sheet sends `Protocol.cast(url, title)`, the same message 4a
  built, so it lands on the receiver page with a real title.

**Verified:** compiles clean; 24 tests pass — `AdBlockTest` (7), `MediaSnifferTest`
(6), `OmniboxTest` (6), `BrowserStoreTest` (5) — covering the rule parser, the suffix
walk (including that a malformed `com` rule can't take the whole web down), the
segment and blob exclusions, the address-vs-search decision, and history
capping/dedup. An earlier build with the single-tab version installed and opened on
the device.

**One bug the pixels caught that the tree did not:** the WebView Box used
`fillMaxSize()` inside a `Column`, so it claimed the full height and — being a real
Android View, drawn above Compose content — covered the address bar entirely. The
accessibility dump still listed the bar at its correct bounds, so only the
screenshot showed it. Fixed with `weight(1f)`; **the fix is installed but has not
been looked at on screen yet.**

**Not verified:** browsing a real site end to end — how many ads actually disappear,
whether the sniffer finds the stream on a typical video site, and whether a found
URL plays on the receiver or 403s for want of a `Referer` (§1 says it will, on many
sites; the phone-side proxy in 4d is the fix and is not built).

### The status channel (first half of 4b)

**The ordering above was wrong, and the code says so.** This section used to close by
saying a scrub bar needed mpv, because mpv's `observe_property` reports position and
duration. But the receiver page *already reported all three* — on every transport event
and once a second while playing — and `CastHub` already stored the latest one. The only
missing link was the server→phone direction: the phone could ask for `cast_status` but
was never told. The scrub bar cost a push, not a player.

`CastHub.Changed` now fires whenever `Snapshot()` would answer differently — a status
arrived, something new was cast, or a receiver attached or went away — and `Program.cs`
forwards it to every connected phone through the share hub, exactly the way
`NowPlaying.Changed` already did. `Snapshot()` is the single definition of that payload,
so the `cast_status` *request* and the push are one message type rather than two shapes
free to drift apart.

On the phone, `CastStatus` parses it and carries the playhead forward against the phone's
own monotonic clock between reports — which is what `NowPlaying` was already doing, so
both now implement a shared `Playhead` and the composable that ticks a bar takes either.

What that buys on the receiver path, with no mpv:

- **A real scrub bar** with absolute seek (`player seek to:`), using the same
  drag-outranks-incoming settle timer as the now-playing bar.
- **A play/pause toggle that knows which one it is.** It sends the explicit action rather
  than the receiver's `toggle`: the state shown may be a second old, and a toggle against
  a stale reading flips the wrong way, while sending what is displayed is harmless when
  it's wrong.
- **The controls go away when the receiver page does.** `receiver:false` against a cast
  we believed was controllable now clears it immediately, instead of the phone finding
  out by getting an error on the next button press.
- **`waitingForGesture` said out loud** at the moment it applies, rather than the cast
  merely looking broken.

Still deliberately blind where it should be: with no report yet — or a receiver that
never sends one — it falls back to the two separate buttons and the relative skips, since
a bar pinned at zero claims a position we don't have.

**Verified:** server builds clean; 78 Android JVM tests pass, 7 new over `CastStatus`
(seconds→ms conversion, the untouched-page case, paused vs playing interpolation, the end
clamp, and a live stream having no bar to scrub). **Not yet driven on a device against a
real receiver** — that is the next thing to do with it.

### mpv (the other half of 4b)

`Cast/MpvPlayer.cs`, ~250 lines, and it slotted in behind the status shape above exactly
as that section predicted: **no new wire message and no new parsing on the phone.** mpv
reports through `CastHub.OnStatus` in the receiver page's own shape, `Snapshot()` counts
a running mpv as a receiver, and the scrub bar, the toggle and the
controls-disappear-when-it-does handling all work against it untouched. The phone's one
change is a constant: `via:"mpv"` is controllable too.

**Routing is three-deep now** — receiver page, then mpv, then `ShellExecute`. The page
wins because opening it was a deliberate choice of *screen*, quite possibly not this one;
mpv wins over the shell because it plays HLS and DASH and can be driven.

**Detect, do not bundle** (§6): `MpvPath` in config, then `mpv.exe` next to our own exe,
then `PATH`. Nothing found is not an error — the cast falls through to the default
handler, which is what it did before.

**Adopt, don't duplicate.** `Start()` tries the pipe *before* launching anything, so an
mpv left over from a previous run of this server is reused rather than fought with. Same
rule [phase7-assistant.md §4.3](phase7-assistant.md) sets for `agent-platformd`.

Two things worth keeping:

- **Nothing blocking happens under the state lock.** The first version wrote commands
  through an auto-flushing `StreamWriter`, and `StreamWriter.AutoFlush` on a named pipe
  means `FlushFileBuffers`, which **blocks until the peer reads**. Holding the lock over
  that deadlocked three ways — the write waited for mpv to read, mpv waited for us to
  read its events, and the read loop waited for the lock the write was holding — and it
  took the phone's control socket down with it, since the reply to `cast` never came.
  Commands are now written to the pipe directly, under a lock that guards nothing but
  the writes. Real mpv reads continuously so this would have fired rarely, which is the
  worst frequency for a hang to have.
- **`force-media-title` is set after the load, not as a load option.** `loadfile`'s
  options argument changed shape across mpv versions; the property did not.

**Verified** against a stand-in that serves `\\.\pipe\portalremote-mpv` and answers a
`loadfile` with the property-change events mpv emits — the real socket, the real server,
the real client messages:

| Case | Result |
|---|---|
| `cast` with no receiver page and mpv reachable | `via:"mpv"`; six `observe_property`, then `loadfile`, then `force-media-title` |
| `duration`/`pause`/`time-pos` events | pushed on as `cast_status` — `position 3.5`, `duration 120` |
| `eof-reached` reported as `null` (mpv for "unavailable") | read as false, not as a stale previous value |
| A command reply (`{"error":"success",…}`) on the same pipe | ignored; only `property-change` events are read |
| `player pause` / `seek to:42` / `volume level:0.25 muted:true` | `set_property pause true`, `seek 42 absolute`, `volume 25` + `mute true` — mpv's 0–100 scale, the phone's 0–1 |
| `player stop` | `quit`; the pipe closes and the phone gets `receiver:false, status:null` |
| `player` after that | `"no cast receiver is attached"` |

**Not verified:** mpv actually playing anything — there is no mpv on this machine, so what
is proven is every line of our side of the pipe and none of mpv's. Nothing here forwards
`Referer`/`Cookie` either (`--http-header-fields`), because nothing on the phone sends
them yet; that arrives with 4d, not before.

### The phone as a server (4d, the `/f/<id>` half)

`net/MediaServer.kt` — `GET`/`HEAD`, one route, byte ranges, and nothing else. Pick a
video in the Media tab and the phone mints an id for it and casts
`http://<phone>:<port>/f/<id>?token=…`; mpv or the receiver page pulls the bytes. Nothing
is uploaded first, which is the point — a two-hour film starts playing immediately rather
than after it has crossed the Wi-Fi twice.

- **Only ids minted by `offer` are servable.** No path from the wire is ever resolved
  against anything, which is the whole traversal defence. `FilePaths.cs` has to sanitise
  because it takes a path; this doesn't take one.
- **Same pairing token**, in the query string, compared with `MessageDigest.isEqual`. Ids
  are 12 random bytes on top of that.
- **Bound to one address, not `0.0.0.0`** — the one on the PC's subnet, chosen by
  `pickLocalAddress`. A phone has several (Wi-Fi, a VPN's tun, mobile data); handing the
  PC the wrong one is a cast that fails with no explanation at either end. The server is
  rebuilt if that address or the pairing changes.
- **Ephemeral port.** The URL carries it, so the fixed `:8766` in §3 would only have been
  one more thing that can already be taken.
- **Started on first use, closed with the ViewModel.** No foreground service, so this
  matches the ceiling §4 already describes: the phone must stay awake and on Wi-Fi for
  the length of the film. The read grant on a picked document dies with the process
  anyway, so nothing here is worth outliving it.

**Verified** — 10 JVM tests driving the real server over real loopback sockets: whole
file, `bytes=100-199`, open-ended `bytes=900-`, suffix `bytes=-100` (how ffmpeg reaches a
trailing `moov` atom), `HEAD` with no body, 401 with a wrong or missing token, 404 for an
id nobody offered *and* for `/f/../../etc/passwd`, 416 past the end with
`Content-Range: bytes */1000`, and the subnet match in `pickLocalAddress`.

**Not verified:** the Android half — the picker, the `ContentResolver` size/name lookup
and the `skip()`-based seek — and a real PC pulling a real film. Those need a device.

**Not built:** `/p/<id>`, the cookie-replaying proxy. It is what makes *dumb* receivers
work (§4), and we have none yet — our own PC can be handed headers directly, which is
route 1 and cheaper. Build it with the first third-party target, not before. Subtitles
and the CORS header Chromecast needs come with it.

### Waking the PC (the last of 4f)

The rest of 4f — the remote screen, the power actions — was built with the TV remote.
Power *on* could not be, because nothing on the phone knew this machine's MAC: ARP isn't
readable from an app, and by the time the PC is asleep it can't be asked.

So the PC volunteers it. `mac` is a field in the hello (`Config/MacAddress.cs` — the Up
Ethernet/Wi-Fi adapter that has an IPv4 gateway, which is the one the phone is talking
to), the phone keeps it on the `SavedHost`, and the "Last used" card grows a **Wake**
button. `net/WakeOnLan.kt` broadcasts the magic packet to the subnet's broadcast address
*and* `255.255.255.255`, since a sleeping PC has no address to aim at and some routers
drop the all-ones form.

**Nothing comes back**, so the button can only report that the packet left the phone —
and the line under it says the rest, because a Wake that needs a BIOS setting and gives
no feedback is otherwise a button that "does nothing".

**Verified:** the hello from this machine carries `2c:9c:58:e6:96:9b`, which is its Wi-Fi
adapter; three JVM tests pin the packet (`FF`×6 then the MAC ×16, 102 bytes), that
`1A-2B-…`, `1a:2b:…` and `1a2b3c4d5e6f` all parse the same, and that a malformed MAC is
refused rather than padded into a packet that would wake nothing. **Not verified:** an
actual machine waking — this PC's BIOS setting is the user's call, not a code path.

### The PC as a DLNA renderer (4l)

The reverse of 4j, and the cheapest interop in this document: announce this PC as a UPnP
`MediaRenderer` and **VLC, Web Video Caster, BubbleUPnP and most Android gallery apps can
cast to it with no client code of ours at all.** It is also a test harness for the mpv
player — a real third-party sender, rather than our own phone agreeing with itself.

`Dlna/DlnaRenderer.cs` is SSDP (M-SEARCH replies, `ssdp:alive` on start, `ssdp:byebye` on
exit) and `Dlna/DlnaEndpoints.cs` is the device description plus the AVTransport SOAP
actions. Everything lands in `CastRouter`, extracted from `InputActions` when this became
its second caller — a phone and a copy of VLC pointing at this PC must not drift into two
different routing rules.

**Off by default, and it has to be** (`EnableDlnaRenderer`). A DLNA controller cannot
present our pairing token — speaking someone else's protocol is the whole point — so
these endpoints are open to the LAN, and "anyone on this Wi-Fi can put a video fullscreen
on my PC" is not a default anybody chose. The startup banner says so when it is on.

Three details that decide whether this works at all:

- **`SO_REUSEADDR` before binding UDP 1900.** Windows already runs an SSDP service on
  that port. Without it the bind is refused and DLNA silently never works on exactly the
  machines most likely to have it.
- **The `LOCATION` address is resolved per-request**, by opening a UDP socket toward the
  controller and reading back which local address the routing table chose. A PC with
  several NICs has no single right answer, and the one it dialled is the one it can dial
  again.
- **`force-media-title` comes from DIDL-Lite.** The sender's title arrives XML-escaped
  inside the SOAP body; without unpacking it, mpv shows a CDN filename.

**Verified** by driving it exactly as a controller does — real M-SEARCH on the multicast
group, then SOAP over HTTP:

| Case | Result |
|---|---|
| `M-SEARCH` for `MediaRenderer:1` | `200`, `LOCATION: http://192.168.0.137:8766/dlna/device.xml`, stable `uuid:` derived from the install id |
| `device.xml` | `CRYOSTATION (Portal Remote)`, AVTransport + ConnectionManager |
| `GetProtocolInfo` | the sink list — a `500` here is how a sender decides we can play nothing |
| `SetAVTransportURI` with DIDL-Lite | mpv got `loadfile` **and** `force-media-title "Bunny From VLC"` |
| `Play` / `Pause` / `Seek 0:01:05` | `set_property pause false` / `true` / `seek 65 absolute` |
| `GetTransportInfo` | `PLAYING`, read from the same status the phone's scrub bar uses |
| `GetPositionInfo` | `TrackDuration 0:02:00`, `RelTime 0:00:03` |
| `SetAVTransportURI` with `file:///C:/Windows/System32/cmd.exe` | UPnP error **714** — the same validation the phone's cast goes through, so a controller cannot turn "cast this" into "run this" |
| Unknown action | UPnP error 401 |
| `Seek` to `banana` | UPnP error 711 |

**Not built:** GENA eventing (`eventSubURL` is empty, so controllers poll
`GetPositionInfo`, which we answer) and `RenderingControl`. Without the latter a sender
simply doesn't offer a volume slider, which beats offering one that does nothing.

**Not verified:** a real sender app. What is proven is every byte of our side of SSDP and
SOAP against a hand-written controller; VLC's or Web Video Caster's own quirks are not.

**Not built yet, in order:** everything in §11 from 4c onward.

## Sources

- [Web Video Caster — integrations](https://www.webvideocaster.com/integrations) · [App Store listing](https://apps.apple.com/gb/app/web-video-cast-browser-to-tv/id1400866497)
- [Chromecast Device Authentication — Tristan Penman](https://tristanpenman.com/blog/posts/2025/03/22/chromecast-device-authentication/) · [node-castv2](https://github.com/thibauts/node-castv2) · [Disabling Cast device auth — yingtongli.me](https://yingtongli.me/blog/2019/12/20/gcast-auth.html)
- [AdblockAndroid](https://github.com/Edsuns/AdblockAndroid) · [libadblockplus-android](https://github.com/adblockplus/libadblockplus-android)
- [Android — Media projection](https://developer.android.com/media/grow/media-projection) · [Behavior changes: Android 14+](https://developer.android.com/about/versions/14/behavior-changes-14) · [Foreground service types](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [intel-upnp-dlna (C#)](https://github.com/genielabs/intel-upnp-dlna)
- [scrcpy over TCP/IP](https://scrcpy.net/control-android-from-pc/)
- [Cast registration — Default Media Receiver needs none](https://developers.google.com/cast/docs/registration)
- [Roku External Control Protocol](https://developer.roku.com/dev/docs/external-control-api)
- [Sony BRAVIA REST API — getting started](https://pro-bravia.sony.net/develop/integrate/rest-api/spec/getting-started/) · [IP control interfaces (IRCC-IP)](https://pro-bravia.sony.net/develop/integrate/ip-control/)
- [LG webOS SSAP — lgtv2](https://github.com/hobbyquaker/lgtv2) · [LGWebOSRemote](https://github.com/klattimer/LGWebOSRemote)
- [Web Video Cast — Play Store listing (supported devices)](https://play.google.com/store/apps/details?id=com.instantbits.cast.webvideo&hl=en_US) · [cast2tv.app — their browser receiver](https://cast2tv.app/)
