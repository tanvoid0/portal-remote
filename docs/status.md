# Status — what's built, what's next

| Phase | What | Status |
|---|---|---|
| 0 | Pairing (QR / manual entry), WebSocket hello round-trip | ✅ Done, verified live |
| 1 | Trackpad, keyboard, media keys | ✅ Done, verified live |
| 2 | File browser: list / download / upload | ✅ Done, verified live |
| 3 | Screen mirroring | ✅ Done, verified live |
| 4 | Casting: link handoff, mpv, in-app browser, browser receiver, local files, Roku + DLNA senders | 🟡 4a–4g, 4i, 4j, 4k, 4l built; DLNA verified live, Roku needs a device |
| 5 | Quick share: clipboard, links, images, files | ✅ Built; server verified live, phone half not yet |
| 7 | Assistant: chatbot + actions via agent-platform | 🟡 7a/7b built, not yet driven live; actions (7c) not |
| 8 | Phone as the PC's speaker (WASAPI loopback → `AudioTrack`) | ✅ Built; server verified live, phone half not yet. Mic and webcam deliberately not — [phase8-audio.md](phase8-audio.md) |
| 9 | Resource dashboard: CPU per core, memory, network, disks, top processes | ✅ Done, verified live against a 32-thread machine under real load |
| 10 | Instrument UI kit: one palette in light + dark, cut-corner shapes, swappable accent | ✅ Done, verified live in both themes on a real handset |

Phases 0–3 have been built **and exercised end-to-end** — real Android build, real
Windows server, actual mouse movement/clicks measured, actual files uploaded and
downloaded and diffed byte-for-byte, actual volume level read back from the Windows Core
Audio API, actual desktop frames streamed to a phone and tapped on with the resulting
cursor position read back. This isn't just "the code compiles." How each piece works is
in [features.md](features.md); how it was verified is in [process.md](process.md).

## What's next — pick one

- **Phase 4: casting (phone → PC and beyond).** Web-Video-Caster-style: an in-app
  browser with adblock that hands media URLs to a player, plus a TV remote. Planned in
  [phase4-casting.md](phase4-casting.md). The handoff (4a), the in-app browser (4e) and
  the browser receiver (4g) are built — a `cast` message opens a link on the PC, and the
  receiver URL (in the app window, Copy or Open) turns *any* screen with a browser into a
  cast target. mpv (4b) plays what a browser can't and reports a real position, files on
  the phone are served to the PC over range requests (4d), and whichever target is
  playing pushes its position back so the phone has a **scrub bar and a play/pause toggle
  that knows which one it is**. Next: a Google Cast sender (4h), which is now genuinely
  just one more adapter.
- **Phase 7: assistant.** A chatbot and an action-taking assistant in the app, backed by
  the agent-platform API over loopback from the PC — the phone never talks to it
  directly. Planned in [phase7-assistant.md](phase7-assistant.md). The availability model
  (7a) and the chatbot (7b) are built: the PC probes agent-platform and pushes whether it
  is up, and `/ai/chat` streams a reply through to the phone, keeping partial text and
  offering Regenerate when a stream is cut off. **Neither has been driven against a live
  `agent-platformd` yet.** Next: 7c, the action loop — register what this PC and phone
  can do, ask `/decide`, and execute only what the user confirms.
- **Phase 8: the phone as a microphone.** The speaker half is built; the mic is the same
  code backwards plus one problem — Windows will not let a user-mode program add a
  microphone to its device list. Riding an installed VB-Cable is about a day and needs
  no driver; a driver of our own needs an EV certificate and attestation signing.
  Webcam is a third case again and the recommendation there is "use DroidCam". All three
  worked through in [phase8-audio.md](phase8-audio.md).
- **Phase 6: internet relay for quick share.** Would make phase 5 work when the phone is
  off the Wi-Fi. Needs an always-on relay, e2e encryption, and a key agreement folded
  into pairing — planned in [phase5-share.md §5](phase5-share.md), not built. Worth doing
  only if living with the LAN version says it's worth doing.
- **Mirror upgrades.** Desktop Duplication API (`dxcam`-style) instead of BitBlt — worth
  it now that capture is the measured bottleneck (~18fps ceiling), not the network.
  H.264/WebRTC beyond that.
- **Gamepad emulation.** Explicitly dropped early on because no ViGEmBus driver was
  installed and the server was going to be Python. Neither blocker applies anymore — the
  server is .NET 8, and `Nefarius.ViGEm.Client` is a well-maintained NuGet package. Still
  blocked on one thing, though: ViGEmBus is a kernel driver and installing it is an
  admin, user-side decision, so the whole feature would have to be written and shipped
  without a single working test — no way to confirm a stick deflection actually reaches a
  game. Install ViGEmBus first, then build it; a dual-stick UI with an untested backend
  is scaffolding, not a feature.
- **Polish pass — done, and then done again.** A concurrent design-system rollout landed
  color/type/motion tokens and press-scale feedback during the Phase 2 session. Phase 10
  went further and replaced the tokens themselves: one instrument palette (§3) in both
  themes, with the Material scheme *derived* from it rather than written beside it, cut
  corners on the shape scale, and monospace instrument type. The rollout is complete —
  every screen draws from the kit, and `ContrastTest` covers every accent on both faces.
  The screenshot set has now caught up with it: the whole set was re-shot in one pass
  against `v0.4.1`, at one resolution, so no frame shows the pre-kit build any more. It
  also gained the three screens that had never been captured — Deck, Settings and the
  mirror full-screen in landscape — and is named by tab so the directory sorts into the
  groups the README uses (see [development.md](development.md#screenshots)).

## Still needs hardware — the outstanding to-do list

Everything here is written and unit-tested; none of it has met the device it is for.

| Needs | To verify |
|---|---|
| **A Roku** | The whole ECP sender: `/launch/2213`, the `Play` key's toggle semantics, `Fwd`/`Rev` skip sizes, `/query/media-player` against a real firmware |
| **A DLNA TV, Xbox or Kodi** | A third-party renderer's quirks — the protocol is proven, the vendors aren't. Not VLC: it browses DLNA *servers* and casts to Chromecast, it is not a renderer |
| **An Android handset** | The phone half of quick share (phase 5); pinch/pan and two-finger scroll on the mirror (SELinux blocks synthetic multitouch on an emulator). The cast picker and phone-served files are now done. Also the speaker's phone half (phase 8): the stream and its silence padding are verified server-side, but nothing has yet confirmed that 150ms of buffer survives a real Wi-Fi network with the screen off |
| **A locked PC** | The mirror resuming after a lock-screen or UAC blackout — coded for, never watched |
| **A machine with WoL in BIOS** | Wake-on-LAN actually waking something |
| **A running `agent-platformd`** | Phases 7a and 7b, which have never spoken to a live backend |
| **ViGEmBus installed** | Gamepad emulation, which isn't written for exactly this reason |
