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
| 3 | Screen mirroring | ⬜ Not started |

Phases 0–2 have been built **and exercised end-to-end** — real Android build,
real Windows server, actual mouse movement/clicks measured, actual files
uploaded and downloaded and diffed byte-for-byte, actual volume level read
back from the Windows Core Audio API. This isn't just "the code compiles."

### What's next — pick one

- **Phase 3: screen mirroring.** `mss`/BitBlt-style capture → JPEG → MJPEG
  `multipart/x-mixed-replace` stream → Android `Image` view with tap-to-click
  passthrough. The one remaining piece of the original plan. Expect
  noticeably higher latency than RDP/Moonlight; `dxcam`/Desktop Duplication
  API is the upgrade path if MJPEG isn't good enough, H.264/WebRTC beyond
  that.
- **Gamepad emulation.** Explicitly dropped early on because no ViGEmBus
  driver was installed and the server was going to be Python. Neither
  blocker applies anymore — the server is .NET 8, and `Nefarius.ViGEm.Client`
  is a well-maintained NuGet package. Would need the ViGEmBus driver
  installed on the PC (one-time, user-side) and a touch dual-stick + button
  layout on the Android side.
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
