# A note on process — what live verification found

This was built with heavy live verification rather than "written once and assumed
correct": every phase was tested against a real Android build and a real running server,
not just compiled. That process surfaced real bugs that got root-caused and fixed rather
than worked around:

1. **Windows pointer acceleration** amplifies relative mouse deltas non-linearly
   (measured: a 60px input delta landed anywhere from 150–290px depending on system
   load) — the trackpad's sensitivity scaling accounts for this.
2. **A WebSocket reconnect bug**: opening the Android system file picker backgrounds the
   app long enough to drop the control socket, which used to bounce the user back to the
   pairing screen and silently cancel whatever was in flight (e.g. an upload). Fixed with
   auto-reconnect in `WsClient` plus keeping the remote UI on a single stable Compose call
   site through the blip — the first fix attempt used two call sites for "connected" vs.
   "reconnecting" and still lost state on the transition, which is itself a useful lesson
   about Compose recomposition identity.
3. **Whole-desktop capture is the wrong default.** The mirror originally captured the full
   virtual screen, which on the development PC is 7280×1440 across three monitors — a 5:1
   strip roughly 250px tall on the phone, i.e. useless. This only surfaced because the
   first captured frame was actually looked at rather than assumed correct; the fix
   (per-monitor capture defaulting to primary) reshaped the wire protocol too, since taps
   now carry which monitor they're relative to.
4. **A two-finger scroll ended in a right-click.** Found while building the mirror's
   gesture handling, but the bug was already live in `TrackpadScreen`: `totalMove` only
   accumulated in the one-finger branch, so after any two-finger scroll it was still 0 and
   the release path read that as a two-finger *tap*. Fixed in both handlers at the cause —
   two-finger movement now counts towards `totalMove` like any other movement — rather
   than by special-casing the release.
5. **The keyboard dropped characters and nobody noticed.** The capture field forced its
   value back to a sentinel on every keystroke and diffed the next callback against
   Compose state, which lags when `onValueChange` fires several times before a
   recomposition. Typing by hand is slow enough to hide it; driving it at machine speed is
   not — `phase3-keyboard-works` arrived at the PC as `paasase3kyybarrd-ok`. This had been
   live since Phase 1 and was only found because the mirror's new inline keyboard got
   tested with `adb input text` rather than by thumb. It now diffs on the common prefix
   against a value updated synchronously, and leaves the IME's buffer alone.

Two things about the mirror are **not** verified live: pinch/pan and two-finger scroll
(injecting real multitouch needs `/dev/input` write access, which SELinux denies on a
production emulator image), and lock-screen resume. The pan/zoom coordinate transform is
covered by JVM unit tests instead (`android/app/src/test/.../MirrorTransformTest.kt`,
`gradlew testDebugUnitTest`) — that's the part where a mistake silently misplaces every
click.

The DLNA *sender* was verified by pointing it at this PC's own DLNA *renderer*, which
speaks the protocol a television speaks: real SSDP on the multicast group, a real
`device.xml`, real `SetAVTransportURI`/`Play`/`Pause`/`Seek` over SOAP, with the title
surviving both rounds of XML escaping and a seek to 90s crossing as `0:01:30` and coming
back as exactly `90.0`. That covers our half of the protocol, not any vendor's quirks.
That harness has since been retired by its own findings — the PC no longer lists itself
(casting to yourself recurses into the router and times out), so reproducing it needs a
real renderer.

The cast picker, a cast to a discovered LAN target, the moving scrub bar and the
transport were then driven **on a real handset** over wireless `adb`, and a file cast from
the phone was fetched back from the PC to confirm the URL carries the media server's own
secret (`200`, byte-exact, with `Range` honoured) while the pairing token is rejected
(`401`).

What still has no hardware to be tested against is listed in [status.md](status.md).
