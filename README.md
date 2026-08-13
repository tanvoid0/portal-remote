# Portal Remote

Control a Windows desktop from an Android phone over the local network — a
Monect-style remote: touch trackpad, keyboard, media keys, and file
browsing/transfer. No cloud, no relay — the phone talks directly to a small
server running on the PC.

```
android/   Kotlin + Jetpack Compose client
server/    .NET 8 / WinForms tray app + embedded Kestrel web server
docs/      design-system.md — shared design tokens/motion spec for both UIs
```

## Status

| Phase | What | Status |
|---|---|---|
| 0 | Pairing (QR / manual entry), WebSocket hello round-trip | ✅ Done, verified live |
| 1 | Trackpad, keyboard, media keys | ✅ Done, verified live |
| 2 | File browser: list / download / upload | ✅ Done, verified live |
| 3 | Screen mirroring | ✅ Done, verified live |
| 4 | Casting: link handoff, mpv, in-app browser, browser receiver, local files, Roku + DLNA senders | 🟡 4a–4g, 4i, 4j, 4k, 4l built; DLNA verified live, Roku needs a device |
| 5 | Quick share: clipboard, links, images, files | ✅ Built; server verified live, phone half not yet |
| 7 | Assistant: chatbot + actions via agent-platform | 🟡 7a/7b built, not yet driven live; actions (7c) not |

Phases 0–3 have been built **and exercised end-to-end** — real Android build,
real Windows server, actual mouse movement/clicks measured, actual files
uploaded and downloaded and diffed byte-for-byte, actual volume level read
back from the Windows Core Audio API, actual desktop frames streamed to a
phone and tapped on with the resulting cursor position read back. This isn't
just "the code compiles."

**Phase 3 shape**: GDI `BitBlt` capture (with the mouse cursor composited in by
hand — Windows doesn't include it in a screen grab) → JPEG → an MJPEG
`multipart/x-mixed-replace` response on `/screen/mjpeg` → an Android `Image`
sized to the frame's exact aspect ratio, so a touch is just a fraction of the
picture. Taps go back over the existing control socket as
`mouse_move_abs {nx, ny, mon}` — normalised 0..1 coordinates, so the phone
never needs to know the desktop's pixel geometry. Capture defaults to the
**primary monitor**, not the whole virtual desktop: on a three-monitor PC that
would be a 5:1 letterbox strip on the phone. `/screen/monitors` lists the
displays and the app shows a chip per display (plus "All") when there's more
than one. Two quality presets — Smooth (15fps / 960px / q50) and Sharp
(8fps / 1600px / q78) — because no single setting suits both "watch a video"
and "read a line of code". Measured over loopback against a 3440×1440
monitor: Smooth delivers **14.5 of 15fps at 477KB/s**, Sharp **8.1 of 8fps at
1102KB/s**, with **51–57ms average capture+encode** per frame. That capture
cost, not the network, is the ceiling — roughly 18fps for this monitor, so
Smooth is deliberately near the top of what BitBlt can do and will fall short
on a busy machine. The server logs achieved fps and average capture time when
a stream ends, so this is observable rather than assumed.

Typing is available on the mirror itself (the "Type" chip) as well as on the
Keyboard mode of the Control tab — same capture field, so text goes to the PC while you watch the
window it lands in.

**Control tab**: everything you drive the PC with by hand — trackpad, keyboard,
media, and a TV-style remote — sits behind one bottom-nav tab and switches with a
tab row, because they're one activity rather than four screens. The TV remote is
the couch mode: a D-pad with OK (arrows auto-repeat on hold, 400ms then ~16/sec like
a real keyboard), Back/Start/menu, transport and volume, chip rows for
Esc/Alt+Tab/Task view/Close and F1–F12, and a power button that offers screen off,
lock, sleep, restart and shut down (the last two behind a confirm).

Pinch zooms up to 4× and two fingers pan once zoomed (at 1× the same two-finger
drag scrolls the remote desktop instead, since there's nothing to pan). A
finger is roughly 130 desktop pixels wide on a 3440px display shown at phone
width, so zoom is what makes small targets actually hittable rather than a
nicety.

**Phase 5 shape**: the pairing and the open control socket are already there, so
quick share is mostly two entry points and a notification on each side. From the
phone, Portal Remote is a target in the **system share sheet** of every app — a link
or a screenshot is two taps from wherever you already are, no trip into this app.
From the PC, **Ctrl+Alt+V** sends the clipboard the other way from inside whatever
app you just copied from. Both directions put the payload on the receiving device's
clipboard *before* the notification fires, so it's pasteable by the time you look at
it. Files land in `<share root>/Inbox/` on the PC and in Downloads on the phone.
A share made while the PC is asleep, or cut off by a Wi-Fi drop mid-upload, is
**queued and re-sent on the next reconnect** rather than failed — the phone is the
device that moves between networks, so "try again later" is the app's job. Queued
items say so on the Share tab and can be tapped to retry immediately; the queue is
in memory, so it does not survive the app's process being killed. See
[docs/phase5-share.md](docs/phase5-share.md), which also carries the plan for the
internet relay that would make this work off the LAN (§5, deliberately not built
yet).

**Now playing**: Control's Media mode mirrors what the PC is actually playing — cover art,
title, artist/album, the player it's coming from, and a progress bar that moves. The
source is Windows' own media session (`GlobalSystemMediaTransportControlsSessionManager`,
the same feed behind the Win11 volume flyout), so any player that registers with it —
Spotify, browsers, VLC — appears without integration. State is pushed down the control
socket that's already open; the bar is interpolated on the phone between pushes, so it
looks continuous at ~2 messages a second. Cover art goes over HTTP (`/media/art`)
rather than the socket — a real cover is ~190KB, and the state messages are ~200 bytes.
This also buys the two things a media key can't express: a **scrubber** (absolute seek,
where the player allows it) and a play/pause button that knows which one it is.

**Casting past the PC**: the Media tab has a **"Cast to" chip row** — this PC, or a Roku
or DLNA renderer found on the network. One interface sits behind all of them
(`IRemotePlayer`: the receiver page, mpv, `ShellExecute`, Roku over ECP, DLNA over
AVTransport), written *before* the second and third protocols rather than after, which is
why adding one touches nothing outside `server/PortalRemote.Server/Cast/`. Every adapter
reports its position in the receiver page's shape, so a television gets the phone's
existing scrub bar and play/pause toggle **with no phone-side protocol code at all**.

Two consequences worth knowing before you use it. A Roku's entire control protocol is its
physical remote's buttons, so it has **no absolute seek** — the phone says so by drawing a
read-only progress bar for one and a real scrubber for a DLNA renderer, rather than a
slider that ignores the drag. And a Roku or a TV **fetches the URL naked** — no `Referer`,
no `Cookie` — so a link lifted from a site that checks either will 403 there while playing
fine on the PC. Files picked on the phone are unaffected; those are served without a
session in the first place.

Discovery is the PC's job, not the phone's: SSDP is multicast, and the PC is already awake,
already on the wire, and already speaking the renderer half of the same protocol. It scans
when the picker opens, answers from cache instantly and pushes again when the sweep lands,
so the list is never blank while it waits — and it sends the search out **every** interface
rather than the default route, because a VPN or a Hyper-V switch otherwise sends it
somewhere the TV isn't. A LAN device is never selected for you.

### What's next — pick one

- **Phase 4: casting (phone → PC and beyond).** Web-Video-Caster-style: an in-app
  browser with adblock that hands media URLs to a player, plus a TV remote.
  Planned in [docs/phase4-casting.md](docs/phase4-casting.md). The handoff (4a),
  the in-app browser (4e) and the browser receiver (4g) are built — a `cast`
  message opens a link on the PC, and the receiver URL (in the app window, Copy or
  Open) turns *any* screen with a browser into a cast target. mpv (4b) plays what a
  browser can't and reports a real position, files on the phone are served to the PC
  over range requests (4d), and whichever target is playing pushes its position back
  so the phone has a **scrub bar and a play/pause toggle that knows which one it
  is**. Next: a Google Cast sender (4h), which is now genuinely just one more adapter.
- **Phase 7: assistant.** A chatbot and an action-taking assistant in the app,
  backed by the agent-platform API over loopback from the PC — the phone never talks
  to it directly. Planned in [docs/phase7-assistant.md](docs/phase7-assistant.md).
  The availability model (7a) and the chatbot (7b) are built: the PC probes
  agent-platform and pushes whether it is up, and `/ai/chat` streams a reply through
  to the phone, keeping partial text and offering Regenerate when a stream is cut
  off. **Neither has been driven against a live `agent-platformd` yet.** Next: 7c,
  the action loop — register what this PC and phone can do, ask `/decide`, and
  execute only what the user confirms.
- **Phase 6: internet relay for quick share.** Would make phase 5 work when the
  phone is off the Wi-Fi. Needs an always-on relay, e2e encryption, and a key
  agreement folded into pairing — planned in
  [docs/phase5-share.md §5](docs/phase5-share.md), not built. Worth doing only if
  living with the LAN version says it's worth doing.
- **Mirror upgrades.** Desktop Duplication API (`dxcam`-style) instead of
  BitBlt — worth it now that capture is the measured bottleneck (~18fps
  ceiling), not the network. H.264/WebRTC beyond that.
- **Gamepad emulation.** Explicitly dropped early on because no ViGEmBus
  driver was installed and the server was going to be Python. Neither
  blocker applies anymore — the server is .NET 8, and `Nefarius.ViGEm.Client`
  is a well-maintained NuGet package. Still blocked on one thing, though:
  ViGEmBus is a kernel driver and installing it is an admin, user-side
  decision, so the whole feature would have to be written and shipped without
  a single working test — no way to confirm a stick deflection actually
  reaches a game. Install ViGEmBus first, then build it; a dual-stick UI with
  an untested backend is scaffolding, not a feature.
- **Polish pass.** A concurrent design-system rollout (see
  `docs/design-system.md`) landed color/type/motion tokens and press-scale
  feedback across most screens during this session, but it was still
  finishing up when Phase 2 wrapped — worth a pass to confirm every screen
  picked it up consistently.
- **Ship it.** Package the server as a self-contained single-file .exe
  (`dotnet publish --self-contained`) so it runs on a PC without any .NET
  install, and cut a release APK. Neither is done yet — everything so far
  has run from a debug build against the SDK-based dev server.

## Running it

**Server** (Windows):
```powershell
cd server
.\run.ps1
```
First run creates `%APPDATA%\portal-remote\config.json` with a random pairing
token and opens the app window with the QR code; after that the window is behind
the tray icon (double-click, or "Open Portal Remote"). Needs the .NET 8 SDK;
see the comment at the top of `run.ps1` if you hit a "no frameworks found"
error — it's a `DOTNET_ROOT` issue, not a missing install, if you installed
the SDK user-locally rather than machine-wide.

**Android**:
```powershell
cd android
.\run.ps1 -Launch
```

**Tests** — JVM-only on both sides, no device or PC state required:
```powershell
cd server
dotnet test
```
```powershell
cd android
.\gradlew.bat testDebugUnitTest
```
The server suite covers the cast protocol parsers (Roku ECP, UPnP times, device
descriptions, DIDL escaping); the Android one covers URL normalising, the ad-block
rules, range parsing, the mirror's pan/zoom transform and the wire-message shapes.

**Shipping builds**:
```powershell
cd server
.\publish.ps1
```
Produces `server\publish\PortalRemote.exe` — 79MB, self-contained, no .NET
install and no `DOTNET_ROOT` needed, no console window (verified: served
`/health` and captured a frame with `DOTNET_ROOT` unset). Trimming isn't
supported for WinForms, so single-file compression is the only size lever;
without it the same exe is 180MB.

```powershell
cd android
.\gradlew.bat assembleRelease
```
Produces `app-release-unsigned.apk` (35MB). **Unsigned** — signing needs a
keystore and password, which is a decision to make rather than a step to
automate. Create one with `keytool -genkey -v -keystore portal-remote.jks
-keyalg RSA -keysize 2048 -validity 10000 -alias portal`, keep it out of the
repo, and wire a `signingConfigs` block reading from a gitignored
`keystore.properties`.
Needs `JAVA_HOME`/`ANDROID_HOME` pointed at a JDK 17+ and the Android SDK —
`run.ps1` does this for you if you have Android Studio installed (uses its
bundled JBR and SDK Manager location). On an emulator, neither the pairing QR
nor discovery works (the emulator sits behind NAT at `10.0.2.2`, so it never
sees the host's real LAN IP and its broadcasts never reach the LAN) — use
"Type address", enter `10.0.2.2` and the port, and click Allow on the PC.

## Pairing

Three ways in, in the order the app offers them:

1. **The remembered PC.** The last successful pairing is stored (address, token
   and the PC's own name) and reconnected silently on launch; the pairing
   screen also offers it as a one-tap card if that reconnect hasn't landed yet.
2. **Discovery.** The phone broadcasts `PORTALREMOTE?` on UDP 8765; every
   server on the LAN answers with its name, HTTP port and version — no secrets,
   so the reply is safe to shout. Tapping a discovered PC calls
   `POST /pair/request`, which puts an "allow this phone?" dialog on that PC;
   Allow hands back the token. Needs the app allowed through Windows Firewall
   on Private networks (the same per-program rule covers the UDP probe).
3. **QR code or typed address.** The QR carries the token, so it pairs without
   anyone clicking Allow. Typing the address (digit boxes, no `.` or `:` to
   hunt for) goes through the same approve-on-PC flow as discovery.

## Security notes

- **LAN-only by design.** No relay, no cloud. Don't port-forward this to the
  internet — there's no TLS and a single shared bearer token.
- **`POST /pair/request` is unauthenticated on purpose** — it's how a phone
  that has never seen the PC gets a token in the first place. The gate is the
  dialog it puts on the PC's screen, which is the same bar the QR code sets:
  you have to be at the machine. It defaults to No, names the requesting phone
  and its IP, and only one can be open at a time, so nothing on the LAN can
  bury the screen in prompts. The discovery reply carries no token — only a
  machine name, port and version, all of which a port scan would reveal anyway.
- Every `/files/*` and `/control` request requires the pairing token, sent
  as an `Authorization: Bearer` header from the app. A `?token=` query-string
  fallback exists server-side for requests that can't set headers (image
  tags, streaming endpoints) but the app itself never uses it for `/control`
  precisely because query strings land in the server's plaintext request
  logs — this was checked and fixed during development (see git history).
- Path traversal is blocked on every file endpoint (list/download/upload) by
  resolving against the share root and rejecting anything that escapes it —
  covered by 27 automated checks including `../..`, drive-rooted paths, and
  crafted upload filenames.
- File uploads have no size cap (`Kestrel MaxRequestBodySize` is
  unlimited) — intentional, so large files transfer, but it means anyone
  holding the pairing token can fill the disk. Acceptable for a personal
  LAN tool; would want a cap before this became multi-user or
  internet-facing.
- **Quick share writes to the clipboard on arrival** on both devices, and a paired
  phone can drop any file into `<share root>/Inbox/`. So the tray balloon *reveals*
  a shared file in Explorer (`explorer /select,`) rather than opening it, and only
  `http`/`https` links are ever handed to `ShellExecute` — one click of a
  notification must not be able to run something the phone sent. `/share/*` is
  behind the same token as everything else, with the same path-traversal guard on
  the upload filename; shared text is capped at 256KB per message.
- **The mirror streams whatever is on screen** to anyone holding the token,
  including whatever happens to be open — password managers, private chats.
  It's behind the same single token as everything else, so treat the token as
  granting "sees and controls my desktop", not "can move my mouse". GDI
  capture fails outright against the lock screen and the UAC secure desktop;
  the stream handler treats that as a skipped frame and holds the response
  open rather than dropping the client, so the mirror should resume on unlock
  (coded for, not yet exercised live).
- **A file cast from the phone to a TV carries its own secret, not the pairing
  token.** The phone serves picked files at `http://phone:port/f/<id>?token=…`, and
  that URL is handed to whatever plays it — which, since the Roku and DLNA senders
  landed, can be a television that logs it and shows it in its own status. So the
  phone's media server mints its **own** per-process secret rather than reusing the
  pairing token: the URL grants "read the files this phone offered", never "see and
  control the PC's desktop". The id in the path is 96 unguessable bits on top of that,
  and both die when the app's process does.
- **Casting to a third-party device sends it the URL, naked.** A Roku or a DLNA
  renderer fetches what you give it with no `Referer` and no `Cookie` — that is a
  property of their protocols, not a choice here. It means a link behind a login will
  fail there rather than leak the session, but it also means the URL itself (and
  anything in its query string) is visible to that device and to anything on the LAN
  that can read its state. Only the PC's own player is ever handed headers.
- **The DLNA renderer is unauthenticated, and off by default.** Turning on
  `EnableDlnaRenderer` in `config.json` lets Web Video Caster, BubbleUPnP and gallery
  apps cast to this PC — and they cannot present the pairing token, which is the
  whole point of speaking their protocol. So while it is on, **anyone on the same
  network can put a video on this screen.** They cannot do anything else: the URL
  goes through the same validation as the phone's, so only `http`/`https` ever
  reaches a player, and there is no other action on that surface. The startup
  banner says when it is on.
- Anyone holding the pairing token has the practical equivalent of physical
  keyboard/mouse access to the PC — same trust model as RDP or TeamViewer.
  Rotate the token from the app window if it's ever suspected leaked.

## A note on process

This was built with heavy live verification rather than "written once and
assumed correct": every phase was tested against a real Android build and a
real running server, not just compiled. That process surfaced two real bugs
that got root-caused and fixed rather than worked around:

1. **Windows pointer acceleration** amplifies relative mouse deltas
   non-linearly (measured: a 60px input delta landed anywhere from 150–290px
   depending on system load) — the trackpad's sensitivity scaling accounts
   for this.
2. **A WebSocket reconnect bug**: opening the Android system file picker
   backgrounds the app long enough to drop the control socket, which used
   to bounce the user back to the pairing screen and silently cancel
   whatever was in flight (e.g. an upload). Fixed with auto-reconnect in
   `WsClient` plus keeping the remote UI on a single stable Compose call
   site through the blip — the first fix attempt used two call sites for
   "connected" vs. "reconnecting" and still lost state on the transition,
   which is itself a useful lesson about Compose recomposition identity.
3. **Whole-desktop capture is the wrong default.** The mirror originally
   captured the full virtual screen, which on the development PC is
   7280×1440 across three monitors — a 5:1 strip roughly 250px tall on the
   phone, i.e. useless. This only surfaced because the first captured frame
   was actually looked at rather than assumed correct; the fix (per-monitor
   capture defaulting to primary) reshaped the wire protocol too, since taps
   now carry which monitor they're relative to.
4. **A two-finger scroll ended in a right-click.** Found while building the
   mirror's gesture handling, but the bug was already live in
   `TrackpadScreen`: `totalMove` only accumulated in the one-finger branch, so
   after any two-finger scroll it was still 0 and the release path read that
   as a two-finger *tap*. Fixed in both handlers at the cause — two-finger
   movement now counts towards `totalMove` like any other movement — rather
   than by special-casing the release.

5. **The keyboard dropped characters and nobody noticed.** The capture field
   forced its value back to a sentinel on every keystroke and diffed the next
   callback against Compose state, which lags when `onValueChange` fires
   several times before a recomposition. Typing by hand is slow enough to
   hide it; driving it at machine speed is not — `phase3-keyboard-works`
   arrived at the PC as `paasase3kyybarrd-ok`. This had been live since
   Phase 1 and was only found because the mirror's new inline keyboard got
   tested with `adb input text` rather than by thumb. It now diffs on the
   common prefix against a value updated synchronously, and leaves the IME's
   buffer alone.

Two things about the mirror are **not** verified live: pinch/pan and
two-finger scroll (injecting real multitouch needs `/dev/input` write access,
which SELinux denies on a production emulator image), and lock-screen
resume. The pan/zoom coordinate transform is covered by JVM unit tests instead
(`android/app/src/test/.../MirrorTransformTest.kt`, `gradlew testDebugUnitTest`)
— that's the part where a mistake silently misplaces every click.

The DLNA *sender* was verified by pointing it at this PC's own DLNA *renderer*, which
speaks the protocol a television speaks: real SSDP on the multicast group, a real
`device.xml`, real `SetAVTransportURI`/`Play`/`Pause`/`Seek` over SOAP, with the title
surviving both rounds of XML escaping and a seek to 90s crossing as `0:01:30` and coming
back as exactly `90.0`. That covers our half of the protocol, not any vendor's quirks.
That harness has since been retired by its own findings — the PC no longer lists itself
(casting to yourself recurses into the router and times out), so reproducing it needs a
real renderer.

The cast picker, a cast to a discovered LAN target, the moving scrub bar and the
transport were then driven **on a real handset** over wireless `adb`, and a file cast
from the phone was fetched back from the PC to confirm the URL carries the media
server's own secret (`200`, byte-exact, with `Range` honoured) while the pairing token
is rejected (`401`).

### Still needs hardware — the outstanding to-do list

Everything here is written and unit-tested; none of it has met the device it is for.

| Needs | To verify |
|---|---|
| **A Roku** | The whole ECP sender: `/launch/2213`, the `Play` key's toggle semantics, `Fwd`/`Rev` skip sizes, `/query/media-player` against a real firmware |
| **A DLNA TV, Xbox or Kodi** | A third-party renderer's quirks — the protocol is proven, the vendors aren't. Not VLC: it browses DLNA *servers* and casts to Chromecast, it is not a renderer |
| **An Android handset** | The phone half of quick share (phase 5); pinch/pan and two-finger scroll on the mirror (SELinux blocks synthetic multitouch on an emulator). The cast picker and phone-served files are now done |
| **A locked PC** | The mirror resuming after a lock-screen or UAC blackout — coded for, never watched |
| **A machine with WoL in BIOS** | Wake-on-LAN actually waking something |
| **A running `agent-platformd`** | Phases 7a and 7b, which have never spoken to a live backend |
| **ViGEmBus installed** | Gamepad emulation, which isn't written for exactly this reason |
