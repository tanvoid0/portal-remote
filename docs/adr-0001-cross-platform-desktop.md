# ADR 0001 — Should the desktop app move off WinForms to go cross-platform?

- **Status:** Accepted — stay Windows-only for now
- **Date:** 2026-08-31
- **Deciders:** Portal Remote maintainers
- **Supersedes / superseded by:** —

## Context

The desktop half of Portal Remote is `server/PortalRemote.Server`: .NET 8, ASP.NET Core
minimal API for everything the phone talks to, WinForms for the tray app and its windows.
It is Windows-only. The question raised: should it migrate to another framework so it can
run on macOS and Linux — and if that is too much work, is staying Windows-only defensible?

The framing matters, so it is worth stating plainly before the options: **the UI framework
is not what makes this app Windows-only.** It is one of nine things that do, and it is the
easiest of them.

### What the code actually is

~15,700 lines of C# on the server side. Split by how portable it is today:

| Bucket | LOC | What is in it |
|---|---:|---|
| **Already portable** | ~7,500 | `Cast/`, `Dlna/`, `Share/`, `Files/`, `Pairing/`, `Devices/`, `Auth/`, `Ai/`, `Update/`, `Control/`, `Program.cs`'s HTTP wiring. Pure .NET + ASP.NET Core. Compiles on `net8.0` unchanged. |
| **UI framework** | ~4,100 | `Tray/` (2,707) + `Theme/` (1,430). WinForms plus 17 files of hand-drawn `System.Drawing` — the instrument kit from [design-system.md](design-system.md). |
| **OS integration** | ~4,100 | Eight subsystems that reach into Windows directly. Table below. |

The eight OS-integration subsystems, and what each would cost on another platform:

| Subsystem | LOC | Windows today | Linux | macOS |
|---|---:|---|---|---|
| `Input/WinInput` | 986 | `SendInput` P/Invoke | `/dev/uinput` — works on **both** X11 and Wayland. Needs a udev rule or the `input` group. | `CGEventPost` + Accessibility grant |
| `Metrics/SystemStats` | 672 | PDH perf counters | `/proc` — easier than Windows | `sysctl` / `host_statistics` |
| `Audio/` (loopback + volume) | 628 | WASAPI loopback COM | PipeWire/PulseAudio **monitor source** — easier than Windows | ⛔ **No native loopback.** Requires the user to install BlackHole or similar. |
| `Media/NowPlaying` | 398 | WinRT `GlobalSystemMediaTransportControls` | MPRIS over D-Bus — easier and more capable | ⛔ `MediaRemote` is private and locked down since Sonoma. Effectively unavailable. |
| `Mirror/ScreenCapture` | 299 | GDI `BitBlt` | X11 `XShmGetImage`. **Wayland needs the PipeWire screencast portal** — a per-session user consent dialog, not a silent capture. | ScreenCaptureKit + Screen Recording grant |
| `Config/StartupRegistration` | ~120 | Registry `Run` key | `.desktop` in `~/.config/autostart` — trivial | `SMAppService` / LaunchAgent |
| `Metrics/ActiveWindow` | ~100 | `GetForegroundWindow` | X11 `_NET_ACTIVE_WINDOW`. ⛔ **Wayland has no equivalent** — by design. | Accessibility grant |
| `Input/Power`, `Theme/SystemTheme` | ~90 | Win32 + registry | `systemctl` / `gsettings` — trivial | `osascript` / `NSApp` |

Two things fall out of that table:

1. **On Linux the platform layer is mostly a lateral move or an improvement** — `/proc`,
   MPRIS and PipeWire monitor sources are all *nicer* than their Windows counterparts.
   The exceptions are Wayland-shaped: screen capture becomes consent-gated, and
   active-window reporting is simply not available. Input survives via `uinput`.
2. **On macOS two features are blocked, not merely expensive.** Loopback audio (Phase 8)
   needs a third-party virtual device the user installs themselves; now-playing/media
   metadata has no supported API at all. A macOS build ships with the Control tab's media
   card and the phone-as-speaker feature missing or degraded.

The Android client is unaffected either way — it speaks HTTP + WebSocket to a paired
address and knows nothing about the OS behind it. Zero Android work in every option below.

## Decision

**Stay Windows-only. Do not migrate the UI framework.**

Cross-platform support is viable — nothing here is architecturally blocked on Linux — but
a framework migration is the wrong first move, because the framework is not the blocker.
An Avalonia rewrite delivers a Linux app that cannot move the cursor, mirror the screen,
read the volume or report what is playing, because none of those live in the UI layer.

When Linux is genuinely wanted, the order is: **platform layer first, headless; UI
framework last, and only if a tray is actually required.** That sequencing is recorded
here so the work is not started from the wrong end.

## Options considered

### 1. Stay Windows-only *(chosen)*

- **Cost:** zero.
- **Gets:** the current feature set, fully verified, on the one platform where every
  subsystem has a supported API.
- **Loses:** macOS and Linux users.

### 2. Headless Linux server, no desktop UI *(the recommended path if Linux is wanted)*

Ship a second target that keeps the ASP.NET Core server and drops `Tray/` and `Theme/`
entirely. Runs as a systemd user unit. Configuration and pairing move to the existing
HTTP surface — the app already embeds and serves `Cast/receiver.html`, so a small
localhost settings page is the same trick again, not a new capability.

- **Touches:** the ~4,100-line OS-integration layer, behind whatever seam each subsystem
  already has. `Tray/` and `Theme/` are excluded from the Linux build, not ported.
- **Estimate:** ~3–4 weeks for X11 parity. Wayland: input works via `uinput`, screen
  capture becomes a consented PipeWire portal session, active-window is dropped.
- **Why it is the cheap one:** it skips the entire framework question. 4,100 lines of UI
  that do not get rewritten is the largest single saving available in any option here.

### 3. Migrate WinForms → Avalonia

Keeps C#, .NET 8 and every line of the portable 7,500. Avalonia has a real cross-platform
tray icon and a Skia canvas, so the hand-drawn instrument kit ports rather than being
redesigned — `System.Drawing` calls become Skia calls, largely mechanically.

- **Cost:** ~2–3 weeks for the UI port, **on top of** option 2's platform work, which is
  still required in full. Not instead of it.
- **Verdict:** the right framework *when* a native desktop UI on Linux is wanted. It is
  step two, not step one.

### 4. .NET MAUI

Rejected outright: no Linux target. Fails the stated requirement.

### 5. Rewrite in Tauri / Electron / Go / Rust

Discards ~15,700 lines of working, live-verified code — including every cast adapter, the
DLNA stack, the pairing protocol and the AI plumbing — to solve a problem those lines do
not have. The platform layer would still have to be written from scratch, in a language
with worse Windows interop than C#. Rejected.

### 6. macOS support

Not recommended in any near-term form, independent of framework. Beyond the Apple
Developer Program ($99/yr) and notarization, three separate user-granted permissions
(Accessibility, Screen Recording, Input Monitoring) gate the core features, and two
features have no supported API at all. Estimated ~4–5 weeks for a build that is
permanently missing pieces the Windows one has. If macOS is ever done, it should be after
Linux, and its feature gaps should be documented up front rather than discovered.

## Consequences

**Accepted:**

- Portal Remote stays a Windows product. The README and [status.md](status.md) already say
  so; no promise is being broken.
- Linux and macOS users are unserved. If that changes, the entry point is option 2.

**Not accepted, and worth guarding against:**

- No speculative abstraction work is authorised by this ADR. Do **not** introduce
  `IInputBackend`-style interfaces with one implementation "for later" — that is cost paid
  now for a decision deliberately deferred. The existing seams (each subsystem is already
  its own file behind a small internal surface) are sufficient for option 2 to start from.
- Do keep the discipline that made those seams: new OS-specific code goes in its own file
  under the subsystem it belongs to, not inline in an endpoint handler.

**Revisit when:** someone commits to the ~3–4 weeks of Linux platform work, or a Wayland
change makes synthetic input or screen capture materially easier. Not before.
