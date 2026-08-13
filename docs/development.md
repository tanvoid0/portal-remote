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

Phone screens, with the device unlocked and the app on the screen you want:

```bash
adb devices                                    # or: adb connect <phone-ip>:<port>
adb exec-out screencap -p > docs/assets/screenshots/android-media.png
```

`adb shell input tap <x> <y>` drives the app between shots, so a re-shoot is scriptable —
but note that MIUI and some other skins refuse injected input unless *USB debugging
(Security settings)* is on, and deny raw `sendevent` under SELinux regardless. On those
devices the screen has to be driven by hand; `screencap` still works.

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

**Before committing a shot**, check it for anything that shouldn't be public: the pairing
token in the *Cast to a screen* field, whatever the desktop happens to be showing behind
the mirror, the contents of the share thread, the phone's lock screen and notification
shade (a stray `screencap` while the phone locked mid-shoot will capture both), and
whatever is actually in the shared folder for Transfer's files half. Stage first — minimise
everything, open something harmless, drop a placeholder file into `ShareRoot` and move
the real ones out for the duration — rather than editing it out afterwards.

**Which screens need staging, and which don't.** Trackpad, Keyboard, TV remote and Stats
show no user content at all and can be re-shot unattended in one pass. Pair, Browser,
Screen, Share, Files and Assistant all put something of the user's on screen — a
discovered PC list, browsing history, the desktop itself, the share thread, the shared
folder, a conversation — so they are the ones that need the staging above, and the reason
a "re-shoot everything" pass is not a thing that can be run without a person deciding what
is in frame.

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
