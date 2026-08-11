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
