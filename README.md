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
Keyboard tab — same capture field, so text goes to the PC while you watch the
window it lands in.

Pinch zooms up to 4× and two fingers pan once zoomed (at 1× the same two-finger
drag scrolls the remote desktop instead, since there's nothing to pan). A
finger is roughly 130 desktop pixels wide on a 3440px display shown at phone
width, so zoom is what makes small targets actually hittable rather than a
nicety.

### What's next — pick one

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
First run creates `%APPDATA%\portal-remote\config.json` with a random
pairing token and shows a QR code from the tray icon. Needs the .NET 8 SDK;
see the comment at the top of `run.ps1` if you hit a "no frameworks found"
error — it's a `DOTNET_ROOT` issue, not a missing install, if you installed
the SDK user-locally rather than machine-wide.

**Android**:
```powershell
cd android
.\run.ps1 -Launch
```

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
bundled JBR and SDK Manager location). On an emulator, the pairing QR won't
work (the emulator sits behind NAT at `10.0.2.2`, not the host's real LAN
IP) — use the "Enter address manually" fallback with `10.0.2.2:<port>` and
the token from the server's `config.json`.

## Security notes

- **LAN-only by design.** No relay, no cloud. Don't port-forward this to the
  internet — there's no TLS and a single shared bearer token.
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
- **The mirror streams whatever is on screen** to anyone holding the token,
  including whatever happens to be open — password managers, private chats.
  It's behind the same single token as everything else, so treat the token as
  granting "sees and controls my desktop", not "can move my mouse". GDI
  capture fails outright against the lock screen and the UAC secure desktop;
  the stream handler treats that as a skipped frame and holds the response
  open rather than dropping the client, so the mirror should resume on unlock
  (coded for, not yet exercised live).
- Anyone holding the pairing token has the practical equivalent of physical
  keyboard/mouse access to the PC — same trust model as RDP or TeamViewer.
  Rotate the token from the tray menu if it's ever suspected leaked.

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
