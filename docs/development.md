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

The README's mockups are generated, not drawn by hand — [`docs/assets/generate.py`](assets/generate.py)
is their source of truth. Stdlib Python only, no dependencies:

```bash
python docs/assets/generate.py                          # rewrite every screen
python docs/assets/generate.py --only android-media     # just one
python docs/assets/generate.py --list                   # names it knows
python docs/assets/generate.py --check                  # exit 1 if any SVG is stale
python docs/assets/generate.py --png                    # also rasterise to docs/assets/png/
```

Unchanged files aren't rewritten, so `git status` only shows what actually moved.
`--check` writes nothing and exits 1 on drift — it runs as the `docs` job in
[CI](../.github/workflows/ci.yml), so a hand-edited SVG or a generator change that
wasn't re-run fails the build.

`--png` rasterises each SVG through headless Chrome/Edge/Chromium (whichever it finds;
`BROWSER=/path/to/chrome` overrides) at its natural size, with transparent corners. The
output is gitignored — the README uses the SVGs, and PNGs are for pasting somewhere that
can't render SVG. It's also the quickest way to eyeball a change: run `--png` and open
the folder.

**Adding a screen**: write a function returning `(body_markup, width, height)`, add it to
the `SCREENS` dict at the bottom, run the script — it prints `added` — and reference
`docs/assets/<name>.svg` from the README. The shared chrome (phone frame, title row, the
nav capsule, chip rows, the brand mark, the QR block) is already there as helpers, so a
new screen is mostly its own content.

**When the UI changes**, edit the matching function and re-run. These are mockups of the
real screens; the labels come from the actual strings in the Compose screens and
`Tray/MainForm.cs`, and they should keep doing so — a screenshot that flatters a UI that
no longer exists is worse than none. CI only checks that the SVGs match the script; that
the script matches the app is on whoever changed the app.

## Updates

Both halves update themselves from GitHub releases: the PC's tray menu has **Check for
updates…** (it downloads the new exe, swaps it in and restarts), and the phone's
**Settings → Updates** downloads the new APK and hands it to the system installer.
Nothing is downloaded until you say yes, and neither half phones home otherwise — the
only request is to GitHub's public release API, when you ask.
