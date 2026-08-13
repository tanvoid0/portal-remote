# How it works — feature by feature

Design notes for what each surface does and why it is shaped that way. For what is
built vs. not, see [status.md](status.md); for the trust model, see
[security.md](security.md); for the UI tokens both halves share, see
[design-system.md](design-system.md).

## Screen mirroring

GDI `BitBlt` capture (with the mouse cursor composited in by hand — Windows doesn't
include it in a screen grab) → JPEG → an MJPEG `multipart/x-mixed-replace` response on
`/screen/mjpeg` → an Android `Image` sized to the frame's exact aspect ratio, so a touch
is just a fraction of the picture. Taps go back over the existing control socket as
`mouse_move_abs {nx, ny, mon}` — normalised 0..1 coordinates, so the phone never needs to
know the desktop's pixel geometry.

Capture defaults to the **primary monitor**, not the whole virtual desktop: on a
three-monitor PC that would be a 5:1 letterbox strip on the phone. `/screen/monitors`
lists the displays and the app shows a chip per display (plus "All") when there's more
than one.

Two quality presets — Smooth (15fps / 960px / q50) and Sharp (8fps / 1600px / q78) —
because no single setting suits both "watch a video" and "read a line of code".
Measured over loopback against a 3440×1440 monitor: Smooth delivers **14.5 of 15fps at
477KB/s**, Sharp **8.1 of 8fps at 1102KB/s**, with **51–57ms average capture+encode**
per frame. That capture cost, not the network, is the ceiling — roughly 18fps for this
monitor, so Smooth is deliberately near the top of what BitBlt can do and will fall
short on a busy machine. The server logs achieved fps and average capture time when a
stream ends, so this is observable rather than assumed.

Pinch zooms up to 4× and two fingers pan once zoomed (at 1× the same two-finger drag
scrolls the remote desktop instead, since there's nothing to pan). A finger is roughly
130 desktop pixels wide on a 3440px display shown at phone width, so zoom is what makes
small targets actually hittable rather than a nicety.

Typing is available on the mirror itself (the "Type" chip) as well as on the Keyboard
mode of the Control tab — same capture field, so text goes to the PC while you watch the
window it lands in.

## Control tab

Everything you drive the PC with by hand — trackpad, keyboard, media, and a TV-style
remote — sits behind one bottom-nav tab and switches with a tab row, because they're one
activity rather than four screens.

The TV remote is the couch mode: a D-pad with OK (arrows auto-repeat on hold, 400ms then
~16/sec like a real keyboard), Back/Start/menu, transport and volume, chip rows for
Esc/Alt+Tab/Task view/Close and F1–F12, and a power button that offers screen off, lock,
sleep, restart and shut down (the last two behind a confirm). Any of the five can be
scheduled instead of fired immediately — quick picks (5/15/30/60 min) or a custom number
of minutes — and restart/shutdown hand the countdown to the OS's own `shutdown /t`, so it
keeps running even if the tray app isn't. The pending timer is the PC's state, not the
phone's: it's visible as a chip next to the power button and synced to every connected
phone, and can be edited or cancelled from the same picker.

## Resource dashboard

The Monitor tab's second half is a live read of the PC itself: CPU as a ring plus a bar
per logical core, memory, the last minute of both on one shared 0–100 chart, network
throughput each way, a meter per fixed drive, and the five processes currently using the
most processor time.

Everything comes from Windows' own primitives rather than a performance-counter
dependency — `NtQuerySystemInformation` for per-core processor time,
`GlobalMemoryStatusEx` for memory, `DriveInfo` and `NetworkInterface` for the rest.
Kernel time *includes* idle time, so a core's load is `(kernel + user − idle) / total`
over the interval, not `kernel + user`; both that and the byte-counter deltas are
covered by [`SystemStatsTests`](../server/PortalRemote.Tests/SystemStatsTests.cs),
because a rate computed against a counter that reset produces a plausible wrong number
rather than an error.

**Nothing is sampled until the screen is open.** The phone sends `{"t":"sys","on":true}`
when the Stats screen composes and `on:false` when it's disposed; the server refcounts
those and stops its timer when the last one leaves, with the subscription owned by the
socket so a phone that walks out of Wi-Fi releases it too. A resource monitor that
permanently costs 1% of the CPU it is reporting on is the one bug this feature must not
ship with.

Samples arrive at 1Hz and the phone keeps the last 60 for the charts. History is kept
across a glance at another tab but thrown away after a gap of more than five seconds —
two ends of a graph drawn as one line would be a claim about a stretch of time nobody
sampled. Gauges and bars tween between samples so a jump from 12% to 60% reads as a
swing rather than a cut, and snap instead under the system "remove animations" setting.

Not sampled: GPU load (the only route is the `GPU Engine` performance-counter category,
which means a new dependency and hundreds of milliseconds to bind) and per-disk I/O.

## How it looks

The phone is drawn as an instrument panel: chamfered panels with a bracket at their
square corners, tick-scale dials, segmented meters, monospace figures on a ruled ground.
That language is one kit ([`ui/theme/`](../android/app/src/main/java/com/portalremote/ui/theme/)),
and the Material3 colour scheme is *derived* from it rather than written alongside it —
so a stock `Chip` or `Card` lands on the same colours and the same 45° cut as a
hand-drawn panel.

**Light and dark are the same instrument under different lighting.** They share every
dimension, shape and type style, and differ only in colour and in how hard the glow
renders — on light it drops to a trace, because a glow is light *added* to a dark ground
and on a white one the same passes read as a printing fault.

**Settings → Colour** picks one of four accent pairs (Ice, Neon, Deep, Steel), applied
live across the whole app and stored in `AppSettings.accent`. Only the accents are the
user's: the neutrals, and what amber and red mean, stay put. `ContrastTest` measures
every accent against both faces at WCAG's thresholds, which is what makes the picker safe
to extend — two of the four shipped pairs were changed *by that test*, because hues that
differ strongly in hue can still sit within a few percent in luminance and become one
shape to anyone who doesn't separate colour.

The reasoning behind all of it is in [design-system.md](design-system.md) §3–§7.

## Quick share

The pairing and the open control socket are already there, so quick share is mostly two
entry points and a notification on each side. From the phone, Portal Remote is a target
in the **system share sheet** of every app — a link or a screenshot is two taps from
wherever you already are, no trip into this app. From the PC, **Ctrl+Alt+V** sends the
clipboard the other way from inside whatever app you just copied from.

Both directions put the payload on the receiving device's clipboard *before* the
notification fires, so it's pasteable by the time you look at it. Files land in
`<share root>/Inbox/` on the PC and in Downloads on the phone.

A share made while the PC is asleep, or cut off by a Wi-Fi drop mid-upload, is **queued
and re-sent on the next reconnect** rather than failed — the phone is the device that
moves between networks, so "try again later" is the app's job. Queued items say so on
Transfer's share half and can be tapped to retry immediately; the queue is in memory, so it does
not survive the app's process being killed. See [phase5-share.md](phase5-share.md),
which also carries the plan for the internet relay that would make this work off the LAN
(§5, deliberately not built yet).

## Now playing

Control's Media mode mirrors what the PC is actually playing — cover art, title,
artist/album, the player it's coming from, and a progress bar that moves. The source is
Windows' own media session (`GlobalSystemMediaTransportControlsSessionManager`, the same
feed behind the Win11 volume flyout), so any player that registers with it — Spotify,
browsers, VLC — appears without integration.

State is pushed down the control socket that's already open; the bar is interpolated on
the phone between pushes, so it looks continuous at ~2 messages a second. Cover art goes
over HTTP (`/media/art`) rather than the socket — a real cover is ~190KB, and the state
messages are ~200 bytes. This also buys the two things a media key can't express: a
**scrubber** (absolute seek, where the player allows it) and a play/pause button that
knows which one it is.

## The phone as the PC's speaker

A switch on the Media tab plays whatever the PC is playing, on the phone. WASAPI
**loopback** capture on the default output device → raw 16-bit PCM on one endless
chunked response (`/audio/stream`) → straight into an Android `AudioTrack`. Measured at
99% of real time off a real endpoint, ~190KB/s at 48kHz stereo.

Raw PCM rather than Opus, for the mirror's reason: on a LAN with a hundred times that
bandwidth, an encoder and a decoder buy nothing and cost both sides a dependency. The
device's own rate and channel count come back in headers, so nothing resamples either.

Two things follow from loopback being the no-driver option, and both are visible to the
user. It **copies** the PC's output rather than replacing it, so the PC's speakers keep
playing unless they're muted there — the card says so. And ~150ms of buffer (the jitter
margin) means it will never lip-sync with video on the PC's own monitor; it is for
music, not for watching a film on one screen and hearing it on another.

Silence is *sent* rather than skipped: WASAPI stops delivering packets when nothing is
playing, and the server pads with zeros at the exact byte rate. That keeps the phone's
buffer primed so the next track starts instantly, stops an idle connection being reaped
in between, and makes a read timeout meaningful on the phone — a constant-rate stream
going quiet is a fault, not idleness, so the phone reconnects instead of hanging.

On the phone it's a foreground service, because a speaker that stops when the screen
locks is not a speaker; that brings the ongoing notification with its Stop action, and a
`WifiLock`, since Wi-Fi power save with the screen off is audible as dropouts.

Making the phone appear as a *selectable output device* in Windows — and the microphone
and webcam versions of this question — needs a signed driver or a native COM camera
module. That analysis is [phase8-audio.md](phase8-audio.md).

## Casting past the PC

The Media tab has a **"Cast to" chip row** — this PC, or a Roku or DLNA renderer found
on the network. One interface sits behind all of them (`IRemotePlayer`: the receiver
page, mpv, `ShellExecute`, Roku over ECP, DLNA over AVTransport), written *before* the
second and third protocols rather than after, which is why adding one touches nothing
outside `server/PortalRemote.Server/Cast/`. Every adapter reports its position in the
receiver page's shape, so a television gets the phone's existing scrub bar and
play/pause toggle **with no phone-side protocol code at all**.

Two consequences worth knowing before you use it. A Roku's entire control protocol is
its physical remote's buttons, so it has **no absolute seek** — the phone says so by
drawing a read-only progress bar for one and a real scrubber for a DLNA renderer, rather
than a slider that ignores the drag. And a Roku or a TV **fetches the URL naked** — no
`Referer`, no `Cookie` — so a link lifted from a site that checks either will 403 there
while playing fine on the PC. Files picked on the phone are unaffected; those are served
without a session in the first place.

Discovery is the PC's job, not the phone's: SSDP is multicast, and the PC is already
awake, already on the wire, and already speaking the renderer half of the same protocol.
It scans when the picker opens, answers from cache instantly and pushes again when the
sweep lands, so the list is never blank while it waits — and it sends the search out
**every** interface rather than the default route, because a VPN or a Hyper-V switch
otherwise sends it somewhere the TV isn't. A LAN device is never selected for you.

## Pairing

Three ways in, in the order the app offers them:

1. **The remembered PC.** The last successful pairing is stored (address, token and the
   PC's own name) and reconnected silently on launch; the pairing screen also offers it
   as a one-tap card if that reconnect hasn't landed yet.
2. **Discovery.** The phone broadcasts `PORTALREMOTE?` on UDP 8765; every server on the
   LAN answers with its name, HTTP port and version — no secrets, so the reply is safe
   to shout. Tapping a discovered PC calls `POST /pair/request`, which puts an "allow
   this phone?" dialog on that PC; Allow hands back the token. Needs the app allowed
   through Windows Firewall on Private networks (the same per-program rule covers the
   UDP probe).
3. **QR code or typed address.** The QR carries the token, so it pairs without anyone
   clicking Allow. Typing the address (digit boxes, no `.` or `:` to hunt for) goes
   through the same approve-on-PC flow as discovery.
