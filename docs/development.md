# Building and running from source

Prebuilt binaries are attached to every [GitHub
release](https://github.com/tanvoid0/portal-remote/releases) — this page is for running
the two halves from the repo.

## Server (Windows)

```powershell
cd server
.\run.ps1
```

First run creates `%APPDATA%\portal-remote\config.json` with a random pairing token and
opens the app window with the QR code; after that the window is behind the tray icon
(double-click, or "Open Portal Remote"). Needs the .NET 8 SDK; see the comment at the top
of `run.ps1` if you hit a "no frameworks found" error — it's a `DOTNET_ROOT` issue, not a
missing install, if you installed the SDK user-locally rather than machine-wide.

## Android

```powershell
cd android
.\run.ps1 -Launch
```

Needs `JAVA_HOME`/`ANDROID_HOME` pointed at a JDK 17+ and the Android SDK — `run.ps1`
does this for you if you have Android Studio installed (uses its bundled JBR and SDK
Manager location).

On an emulator, neither the pairing QR nor discovery works (the emulator sits behind NAT
at `10.0.2.2`, so it never sees the host's real LAN IP and its broadcasts never reach the
LAN) — use "Type address", enter `10.0.2.2` and the port, and click Allow on the PC.

## Tests

JVM-only on both sides, no device or PC state required:

```powershell
cd server
dotnet test
```

```powershell
cd android
.\gradlew.bat testDebugUnitTest
```

The server suite covers the cast protocol parsers (Roku ECP, UPnP times, device
descriptions, DIDL escaping); the Android one covers URL normalising, the ad-block rules,
range parsing, the mirror's pan/zoom transform and the wire-message shapes.

## Shipping builds

```powershell
cd server
.\publish.ps1
```

Produces `server\publish\PortalRemote.exe` — 79MB, self-contained, no .NET install and no
`DOTNET_ROOT` needed, no console window (verified: served `/health` and captured a frame
with `DOTNET_ROOT` unset). Trimming isn't supported for WinForms, so single-file
compression is the only size lever; without it the same exe is 180MB.

```powershell
cd android
.\gradlew.bat assembleRelease
```

Produces `app-release-unsigned.apk` (35MB). **Unsigned** — signing needs a keystore and
password, which is a decision to make rather than a step to automate. Create one with
`keytool -genkey -v -keystore portal-remote.jks -keyalg RSA -keysize 2048 -validity 10000
-alias portal`, keep it out of the repo, and wire a `signingConfigs` block reading from a
gitignored `keystore.properties`.

## Screenshots

The README's screenshots in [`docs/assets/screenshots/`](assets/screenshots) are real
captures of a real build — a phone over `adb`, and the tray window through `PrintWindow`.
They replaced a set of generated SVG mockups, which drifted from the app the moment a
screen changed and flattered a UI that no longer existed.

The set is named by the tab it belongs to — `control-*`, `monitor-*`, `transfer-*`, then
`pair`, `deck`, `browser*`, `assistant`, `settings` and `desktop-tray` — so the directory
sorts into the same groups the README lays them out in.

**Every phone shot is 1080×2252 — a `screencap` with the status bar cropped, not staged.**
SystemUI demo mode, the usual way to freeze the clock and hide notification icons, is
ignored by One UI: `sysui_demo_allowed 1` plus the `com.android.systemui.demo` broadcasts
change nothing on a Samsung device. Since the clock otherwise drifts across a shoot and
the notification icons say what else is installed, the bar comes off afterwards. Its
glyphs end at row 78 and the app's own top padding runs to about row 127, so 88 is a
clean cut:

```python
Image.open(src).crop((0, 88, 1080, 2340)).save(dst)   # -> 1080x2252
```

Phone screens, with the device unlocked and the app on the screen you want:

```bash
adb devices                                    # or: adb connect <phone-ip>:<port>
adb exec-out screencap -p > docs/assets/screenshots/control-media.png
```

`adb shell input tap <x> <y>` drives the app between shots, so a re-shoot is scriptable —
but note that MIUI and some other skins refuse injected input unless *USB debugging
(Security settings)* is on, and deny raw `sendevent` under SELinux regardless. On those
devices the screen has to be driven by hand; `screencap` still works.

Two things about tapping this app in particular. The bottom nav's selected item expands
into a labelled pill, so **every other icon shifts** when the tab changes — coordinates
read off one screenshot are wrong on the next, and a stale tap lands on a neighbour.
Re-read them after each move. And on the mirror, a swipe is not a scroll: the surface
forwards drags to the PC as pointer input, so an attempt to scroll the quality chips
drags something on the desktop instead.

`KEYCODE_BACK` closes the IME when it is open and **leaves the app** when it is not, which
puts the phone's home screen in the frame. Check the keyboard is actually up before
sending it. The Keyboard screen focuses its *Tap to type* field on arrival, so it opens
the IME by itself — dismiss it before capturing, or the phone's own keyboard covers the
bottom nav and that shot alone is framed differently from the rest.

`uiautomator dump` gives exact node bounds to tap, but it fails with `could not get idle
state` on any screen that animates — which here means the mirror, the media card whenever
its scrubber is moving, and the stats dashboard, which is animating something every
second it is open. Read the coordinates off a screenshot on those.

The stats screen needs staging of its own: its charts are a rolling minute, so a shot
taken straight after opening the tab is a nearly empty box. Leave it open **at least 60
seconds** before capturing, and put the PC under some real load first or the result is a
flatline that shows nothing the feature does — a few busy-loop background jobs plus a
large download is enough:

```powershell
1..8 | ForEach-Object { Start-Job { $end=(Get-Date).AddSeconds(110); while((Get-Date) -lt $end){ [math]::Sqrt([math]::PI) | Out-Null } } }
```

The tray window is captured by handle rather than by screen region, so it doesn't matter
what is stacked on top of it:

```powershell
# PrintWindow with PW_RENDERFULLCONTENT (2); DwmGetWindowAttribute(9) for the true bounds
$h = (Get-Process PortalRemote).MainWindowHandle
```

The light-mode dashboard needs the system theme flipped, because the app follows
`isSystemInDarkTheme()` and has no toggle of its own. Flipping it **recreates the
activity and clears the stats history**, so the 60-second wait starts again after the
switch, not before:

```bash
adb shell cmd uimode night no    # ... wait 60s, capture ... then: night yes
```

The mirror prints a gesture hint over the frame that only clears "on the first touch" —
waiting does not dismiss it. Tap once somewhere harmless (empty wallpaper, well away from
the menu bar) before capturing, remembering the tap is a real click on the PC.

**Before committing a shot**, check it for anything that shouldn't be public: the pairing
token in the *Cast to a screen* field, whatever the desktop happens to be showing behind
the mirror, the contents of the share thread, the phone's lock screen and notification
shade (a stray `screencap` while the phone locked mid-shoot will capture both), and
whatever is actually in the shared folder for Transfer's files half. Stage first — minimise
everything, open something harmless, drop a placeholder file into `ShareRoot` and move
the real ones out for the duration — rather than editing it out afterwards.

Two leaks are worth calling out by name, because neither is where you would look. The
phone's display name is `Settings.Global.DEVICE_NAME`, which on a personal handset is
usually the owner's actual name, and it is printed against every device row in the tray
window. And the mirror will happily show a second monitor you have forgotten about —
including whatever editor has `config.json`, and its token, open on it.

**What is staged, and what is redrawn.** Everything that could be staged, was: a
placeholder `ShareRoot`, a share thread sent from both ends, a fresh assistant exchange,
a maximised window on the mirrored display. What cannot be staged — LAN addresses, the
Windows username in two paths, the phone's display name — is painted over afterwards and
**redrawn in the same typeface** (Roboto for the phone, Segoe UI for the tray) at the size
fitted to the original string's measured ink width, so the result reads as the app rather
than as a black bar. Fit by width, not by height: an ink-height fit locks onto the text
field's border or a lowercase x-height instead of the type size.

**Which screens need staging, and which don't.** Trackpad, Keyboard, TV remote and Stats
show no user content at all and can be re-shot unattended in one pass. Pair, Browser,
Screen, Share, Files, Deck and Assistant all put something of the user's on screen — a
discovered PC list, browsing history, the desktop itself, the share thread, the shared
folder, the PC's focused window, a conversation — so they are the ones that need the
staging above, and the reason a "re-shoot everything" pass is not a thing that can be run
without a person deciding what is in frame.

Two of them cost more than a tap. **Pair** needs *Forget this PC*, which means re-pairing
afterwards — `POST /pair/request` is unauthenticated by design and gated on the *Let this
phone control this PC?* dialog, so someone has to be at the PC to allow it back in.
**Deck**'s context row follows whatever has focus on the PC, and `SetForegroundWindow`
from a background script loses to a fullscreen game or an overlay; the reliable way to
point it at something is to tap the Deck's own tile for that app and let the launch take
focus properly.

**When the UI changes**, re-shoot the screens it touched. A screenshot in the README is a
claim about what the app looks like today.

## Updates

Both halves update themselves from GitHub releases: the PC's tray menu has **Check for
updates…** (it downloads the new exe, swaps it in and restarts), and the phone's
**Settings → Updates** downloads the new APK and hands it to the system installer.
Nothing is downloaded until you say yes, and neither half phones home otherwise — the
only request is to GitHub's public release API, when you ask.

Both halves report the check three ways, not two — `Updates.standing` on the phone,
`UpdateCheck.StandingOf` on the PC:

| Installed vs newest release | What it says |
|---|---|
| same | Already up to date |
| behind | *0.2.0 is behind 0.3.1* — offers the install |
| unreleased | *a development build* — offers nothing |

The third row is the one that matters while developing. A build run from source carries
the checked-in default — `0.1.0-dev` in `build.gradle.kts` and the csproj — and the
`-dev` suffix is the whole point: those digits are whatever was last committed, not a
position on the release line, so nobody has to bump them at each tag. Without it a build
of today's source reads as *behind*, and "updating" it installs an older build over a
newer one. A build made after a release but before the next tag counts as unreleased
too, on its version alone.
