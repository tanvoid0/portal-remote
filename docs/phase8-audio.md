# Phase 8 — The phone as an audio and video device for the PC

Design notes for the three things people mean by "use my phone as a peripheral": a
**speaker**, a **microphone**, and a **webcam** for the PC.

**Status:** the speaker is built and verified against a real sound card (§1). The
microphone (§2) and the webcam (§3) are not built, and the reason is the same in both
cases: they need Windows to believe a *device* exists, which is a different kind of
project from anything else in this repo.

The short version: one of the three needs no driver and two of them do, and knowing
which is which is the whole of this document.

---

## 0. The one question that decides everything

Windows does not let a user-mode program add an entry to its audio or camera device
lists. That list is populated by drivers. So each of the three features splits on:

> Does this need Windows to *think a device is present*, or is it enough to move audio
> or video between the PC and the phone?

| Want | Needs a device? | Verdict |
|---|---|---|
| PC's sound plays on the phone | **No** — WASAPI loopback copies what already went to the speakers | Built, §1 |
| Phone's mic usable in Zoom/Discord | **Yes** — a virtual audio *capture* endpoint | §2, needs a third-party cable or a signed driver |
| Phone's camera usable in Teams/Zoom | **Yes** — a virtual camera | §3, needs a native COM DLL and an installer |

Everything below follows from that table.

---

## 1. Speaker — built

`GET /audio/stream` opens a WASAPI **loopback** capture on the default render endpoint
and streams what comes back as raw 16-bit PCM, forever, on one chunked HTTP response.
The phone plays it into an `AudioTrack`. See `server/PortalRemote.Server/Audio/` and
`android/.../audio/SpeakerService.kt`.

**Loopback is a copy, not a redirect.** This is the one thing about the feature a user
has to be told, and the Media tab's card says it: the PC's own speakers keep playing
unless they are muted there. Making the phone a *selectable output device* in Windows'
sound settings is §2's problem in the other direction, and carries the same driver bill.

Five decisions worth the space:

- **Raw PCM, not Opus.** Same argument as the mirror's MJPEG: ~190KB/s on a LAN with a
  hundred times that, and neither side pays for an encoder, a decoder, a negotiation or
  a dependency. Revisit if this ever has to cross the internet (phase 6's relay), where
  the trade flips completely.
- **No resampling.** The device's mix rate and channel count come back as
  `X-Portal-Sample-Rate` / `X-Portal-Channels` and the phone configures its output to
  match. A whole class of code, and of artefacts, that neither side has to have.
- **Silence is sent, not skipped.** WASAPI stops delivering packets when nothing is
  playing; the endpoint notices and pads with real zeros at exactly the byte rate.
  Three things hang on that: the phone's buffer stays primed so the next track starts
  instantly, an idle TCP connection cannot be reaped by something in between, and a
  constant-rate stream makes a *read timeout* meaningful on the phone — five seconds of
  nothing on the wire now means the link is gone rather than that the PC is quiet.
- **One capture per client, no hub.** WASAPI opens several loopback streams on one
  endpoint happily. A fan-out would be state to own for a case (two phones as one
  stereo pair) that isn't the point.
- **A foreground service on the phone**, because a speaker that stops when the screen
  locks is not a speaker — and with it, the notification the system is owed, the Stop
  button the user is owed, and a `WifiLock`, because Wi-Fi power save with the screen
  off is heard directly as dropouts on a constant-rate stream.

**Known ceilings.** ~150ms of buffer means this will never lip-sync with video on the
PC's own screen — fine for music, wrong for watching a film on the monitor and hearing
it on the phone. A 5.1 desktop is downmixed by taking front L/R, so a centre-channel
voice goes quiet; proper fold-down is a matrix per layout, worth writing when somebody
notices. And the phone's audio clock drifts against the PC's, so over hours the buffer
walks and eventually glitches once; the reconnect covers it, nothing corrects it.

**Verified:** 48kHz stereo off a real endpoint at 99% of real time, a tone captured at
the right amplitude, 401 without a token, and the silence padding holding the byte rate
with nothing playing. Not yet verified on a handset — see status.md.

---

## 2. Microphone — not built

Streaming the phone's mic *to this app* is trivial and pointless: the value is being
able to pick the phone as a microphone in Discord, Zoom, or Windows' own settings, and
that requires a **virtual audio capture endpoint**. There is no user-mode API that
registers one. Two ways in:

**a. Ride an existing virtual cable (the cheap one).** With VB-Cable or VoiceMeeter
installed, the server renders the phone's audio into "CABLE Input" over WASAPI and the
user picks "CABLE Output" as their microphone. No driver authoring at all — the
server-side code is the loopback path in reverse, roughly a day. The cost is an
external dependency the user installs themselves and a feature that has to detect its
absence and hide itself. Redistributing VB-Cable inside our installer is a licensing
question, not a technical one.

**b. Ship a driver.** A WDM/AVStream audio capture driver, which means an EV code
signing certificate, attestation signing through the Partner Center, and a kernel
component in a repo that currently has none. This is what DroidCam and Camo do. It is
the only version that works with nothing else installed.

**Recommendation if this gets picked up:** (a), gated on detecting the cable, with the
card on the Media tab saying plainly what to install and why. (b) is a decision about
what kind of product this is, not a feature.

## 3. Webcam — not built

Same shape, worse. Getting frames from the phone is easy — the phone can serve MJPEG
the same way `/screen/mjpeg` does, and the plumbing for that already exists on both
sides. The device half is the whole job:

- **A DirectShow filter** — a native C++ COM DLL, registered with `regsvr32` by an
  installer running as admin. Works in Chrome, OBS, and most things that predate the
  Media Foundation era. Cannot be written in .NET.
- **`MFCreateVirtualCamera`** — Windows 11 (build 22000) and later only, and still a
  registered native frame-source DLL. This is the future-proof one and it excludes
  every Windows 10 machine.

OBS ships both, for exactly this reason. Either way the work is a native module plus an
installer that has to run elevated — neither of which this repo has today, and both of
which change what shipping a release means.

**Recommendation:** don't, unless the phone-as-webcam case is the reason someone is
using this app. DroidCam and Camo exist, are signed, and are free at the resolution
most people need. The honest version of this feature is a link to them.

---

## 4. Build order, if this continues

1. ~~Speaker~~ (done).
2. Speaker polish, only if living with it says so: a level meter is not needed, a
   latency preset (buffer 80/150/300ms) probably is on a bad Wi-Fi network.
3. Microphone via §2a, gated on cable detection.
4. Nothing else without a decision about drivers and installers.
