# Portal Remote

A phone (Android) that remote-controls a PC (Windows tray app + local server) over the
local network — trackpad, keyboard, media keys, file transfer.

- `server/` — `PortalRemote.Server`, .NET 8 / WinForms, runs as a tray app.
- `android/` — Jetpack Compose client.

## UI / design work

Before making any visual or interaction change on either side (WinForms tray/QrForm or
the Android Compose screens), read [docs/design-system.md](docs/design-system.md) first
and follow it — tokens, motion durations/easing, and per-screen component guidance are
all defined there. It also lists a suggested implementation order (§10) if picking this
up from scratch.

## Screenshots

The README's images are captures of a real build, so they are a claim about what the app
looks like *today*. Two rules keep that true:

- **Any UI change**: note which screens it touched. The captures for those are now stale,
  even if the change was small — a moved tab row is visible in every shot of that tab.
- **Before a major release**: re-shoot the whole set, existing features and new ones
  alike, in one pass on one device. A set shot across two sessions frames inconsistently,
  which looks worse than one that is a version behind.

Run [`android/screenshots.ps1`](android/screenshots.ps1) — it finds adb, pins the device
to the frame the set is shot in (1080×2340 @ 420dpi), crops the status bar and writes each
file under its canonical name. `-List` shows every shot and what has to be staged for it.

It prompts between shots on purpose and must not be made to run unattended. Most of these
screens put something of the user's in frame — the desktop behind the mirror, the share
thread, the shared folder, browsing history, the PC's focused window, LAN addresses, the
machine name — so a person has to decide what is in each one before it is published. The
staging notes, the redaction rules and the pre-commit checklist are in
[docs/development.md](docs/development.md#screenshots); read that section before a re-shoot
rather than working it out from the script.
