# Capture the README's phone screenshots from a connected device over adb.
#
# What this does and does not do. It handles the mechanical half — find adb, pin the
# device to the frame the set is shot in, screencap, crop the status bar, name the file,
# put it in docs/assets/screenshots — and it prompts you between shots for the half a
# script cannot do: staging what is on screen. Most of these screens put something of
# the user's in frame (the desktop itself, a share thread, a shared folder, browsing
# history), so an unattended "re-shoot everything" pass would capture whatever happened
# to be there. See docs/development.md#screenshots for what each screen needs, and for
# the checks to run before committing a shot.
#
# It deliberately does not drive the app by tapping. The bottom nav's selected item
# expands into a labelled pill, so every other icon shifts when the tab changes and
# coordinates read off one screenshot are wrong on the next; `uiautomator dump` fails
# with "could not get idle state" on the animating screens (mirror, media, stats); and
# some skins refuse injected input outright. Navigating by hand is more reliable than
# any of that and costs one tap per shot.
#
# The tray window is not here — it is captured by window handle through PrintWindow, on
# the PC side. That one is in docs/development.md too.
#
# Usage:
#   .\screenshots.ps1                       every shot, prompting before each
#   .\screenshots.ps1 -List                 print the shot list and exit
#   .\screenshots.ps1 -Only deck,deck-stats just those two
#   .\screenshots.ps1 -Only deck -NoPrompt  capture immediately, screen already staged
#   .\screenshots.ps1 -Reset                undo a frame override left by a crashed run
#
# -KeepFrame holds the frame override across a pass driven from another script; finish
# that pass with -Reset. Without it every run resizes the display twice, which relayouts
# the app in between navigating to a screen and capturing it.

param(
    # Shot names to capture, in the order given. Everything, in table order, if omitted.
    [string[]]$Only,
    # adb serial, for when more than one device is attached.
    [string]$Device,
    [string]$OutDir = (Join-Path $PSScriptRoot '..\docs\assets\screenshots'),
    # Rows of status bar to cut. Its glyphs end at row 78 and the app's own top padding
    # runs to about row 127, so 88 is a clean cut that leaves 1080x2252.
    [int]$CropTop = 88,
    # The frame the whole set is shot in. Forced on the device for the duration so a
    # different panel (or an emulator) still produces images that frame identically —
    # a set at two sizes is worse than a set that is one release out of date. Always
    # reset on the way out.
    [string]$Size = '1080x2340',
    [int]$Density = 420,
    # Skip the "stage this, then press Enter" prompt. For a screen you have already set
    # up, and for calling this from another script.
    [switch]$NoPrompt,
    [switch]$List,
    # Leave the frame override in place instead of resetting it. For a pass driven from
    # another script one shot at a time, where setting and clearing the frame around
    # every capture relayouts the app between navigating and shooting. Follow the pass
    # with -Reset.
    [switch]$KeepFrame,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'

# name -> what has to be true on screen before it is worth pressing Enter. Order is the
# order the README lays them out in, which is also the shortest walk through the app.
$SHOTS = @(
    @{ name = 'pair';                note = 'Pair screen. Needs "Forget this PC" first — and someone at the PC to allow it back in afterwards.' }
    @{ name = 'settings';            note = 'Settings, scrolled to the top.' }
    @{ name = 'control-trackpad';    note = 'Control > Trackpad. Nothing to stage.' }
    @{ name = 'control-keyboard';    note = 'Control > Keyboard. It focuses its type field on arrival — dismiss the IME first or it covers the nav bar.' }
    @{ name = 'control-media';       note = 'Control > Media. Play something on the PC so the card has a track and a scrubber.' }
    @{ name = 'control-remote';      note = 'Control > Remote. Nothing to stage.' }
    @{ name = 'monitor-screen';      note = 'Monitor (the mirror). Stage the desktop — check every monitor, not just the primary. Tap once somewhere harmless to clear the gesture hint.' }
    @{ name = 'monitor-screen-full'; note = 'The mirror in landscape: turn the phone sideways and it goes full screen on its own. No crop — full screen hides both bars.'; landscape = $true }
    @{ name = 'deck-shortcuts';      note = 'Deck > Shortcuts. The context row follows the PC''s focused window — tap the Deck tile for the app you want in frame and let the launch take focus.' }
    @{ name = 'deck-stats';          note = 'Deck > Stats, dark. Leave it open 60s+ with the PC under real load, or the chart is a flatline.' }
    @{ name = 'deck-stats-light';    note = 'Same, light: adb shell cmd uimode night no. The flip recreates the activity and clears the history, so the 60s starts again after it.' }
    @{ name = 'deck-stats-detail';   note = 'Same again, scrolled down to network, drives and processes.' }
    @{ name = 'transfer-share';      note = 'Transfer > Share, with a staged thread sent from both ends.' }
    @{ name = 'transfer-files';      note = 'Transfer > Files. Point ShareRoot at placeholder files, not the real ones.' }
    @{ name = 'browser';             note = 'Browser on a page you are happy to publish — history is visible in the omnibox.' }
    @{ name = 'browser-adblock';     note = 'Browser on canyoublockit.com, with every ad slot empty.' }
    @{ name = 'assistant';           note = 'Assistant, showing a fresh exchange and a proposed pair of actions awaiting approval.' }
)

if ($List) {
    $SHOTS | ForEach-Object { '{0,-20} {1}' -f $_.name, $_.note }
    return
}

# --- adb -----------------------------------------------------------------------------
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $onPath) { throw "adb not found. Set ANDROID_HOME, or put platform-tools on PATH." }
    $adb = $onPath.Source
}

$target = @()
if ($Device) { $target = @('-s', $Device) }

function Adb { & $adb @target @args }

$attached = (Adb devices) | Select-String -Pattern '\tdevice$'
if ($attached.Count -eq 0) { throw "No device. Plug a phone in with USB debugging on, adb connect it, or start an emulator." }
if ($attached.Count -gt 1 -and -not $Device) {
    throw "More than one device attached. Pass -Device <serial>:`n$($attached -join "`n")"
}

if ($Reset) {
    Adb shell wm size reset | Out-Null
    Adb shell wm density reset | Out-Null
    Write-Host "Frame override cleared." -ForegroundColor Green
    return
}

# --- the shots -----------------------------------------------------------------------
# @() around it because a single hashtable is not a one-element array — .Count on a bare
# hashtable is its key count, which reads as a plausible-looking wrong progress number.
$queue = @(
    if ($Only) {
        $Only | ForEach-Object {
            $n = $_
            $hit = $SHOTS | Where-Object { $_.name -eq $n }
            if (-not $hit) { throw "No shot named '$n'. -List shows them all." }
            $hit
        }
    } else { $SHOTS }
)

$OutDir = (New-Item -ItemType Directory -Force -Path $OutDir).FullName
Add-Type -AssemblyName System.Drawing
$remote = '/sdcard/portal-shot.png'
$taken = 0

try {
    # Pin the frame. A no-op on a device that is already this size; on anything else it
    # is what keeps the set from arriving at two resolutions.
    Adb shell wm size $Size | Out-Null
    Adb shell wm density $Density | Out-Null
    # Reconfiguring the display is not instant, and screencap during the gap returns the
    # panel's real size — which looks like the override silently not working. Wait for
    # the device to report it back before trusting a capture.
    $settled = $false
    foreach ($try in 1..20) {
        $reported = (Adb shell wm size) -join "`n"
        if ($reported -match "Override size: $([regex]::Escape($Size))") { $settled = $true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $settled) { throw "Device did not take the $Size frame override." }
    # And the app has to relayout into it.
    Start-Sleep -Milliseconds 500

    $i = 0
    foreach ($shot in $queue) {
        $i++
        $dest = Join-Path $OutDir "$($shot.name).png"
        Write-Host ""
        Write-Host "[$i/$($queue.Count)] $($shot.name)" -ForegroundColor Cyan
        Write-Host "  $($shot.note)"
        if ($shot.landscape) { Write-Host "  Landscape — turn the device before capturing." -ForegroundColor Yellow }

        if (-not $NoPrompt) {
            $answer = Read-Host "  Enter to capture, s to skip, q to stop"
            if ($answer -eq 'q') { break }
            if ($answer -eq 's') { continue }
        }

        # Cheap guard against the most embarrassing failure this script has: a resize,
        # a stray tap or a crash puts the launcher in front, screencap dutifully
        # captures the wallpaper, and it lands in the README looking like a shot that
        # was simply taken badly.
        # mFocusedApp, not mCurrentFocus: the focused *window* is often a system overlay
        # sitting over a perfectly correct app — the IME, a toast, or the "swipe down to
        # exit full screen" panel this app triggers by hiding the nav bar. Those are not
        # the wrong app, they are just in front of it.
        # -join first: adb returns lines as an array, and `-notmatch` over an array
        # *filters* it rather than testing it, so the `if` is true whenever any line
        # fails to match — i.e. always.
        $focus = (Adb shell dumpsys window) -join "`n"
        if ($focus -notmatch 'mFocusedApp.*com\.portalremote') {
            $msg = "Portal Remote is not the focused window — bring it to the front first."
            if ($NoPrompt) { throw $msg }
            Write-Warning $msg
            continue
        }

        # Capture, then check the image is actually the frame we asked for. `wm size`
        # reports the override the moment it is *recorded*, which is before the display
        # has finished reconfiguring — so the settle check above can pass while screencap
        # is still returning the panel's real size. Checking the pixels is the only
        # honest test, and getting it wrong means one off-size frame in an otherwise
        # consistent set, which is exactly the thing this script exists to prevent.
        $tmp = Join-Path ([System.IO.Path]::GetTempPath()) "portal-shot-$($shot.name).png"
        # Built in two steps, and not as @($Size, $a + 'x' + $b): inside @(...) the
        # comma binds tighter than +, so that spelling yields four elements
        # (1080x2340, 2340, x, 1080) and never contains the landscape form — which
        # passes silently for portrait and fails every landscape shot.
        $wh = $Size -split 'x'
        $want = @($Size, "$($wh[1])x$($wh[0])")
        $img = $null
        # 12 tries, not a handful: a plain resize settles in well under a second, but
        # reconfiguring after a *rotation* has taken several on this emulator, and the
        # failure mode is the run aborting on a frame that was about to be right.
        foreach ($attempt in 1..12) {
            Adb shell screencap -p $remote | Out-Null
            Adb pull $remote $tmp | Out-Null
            Adb shell rm $remote | Out-Null
            $img = [System.Drawing.Image]::FromFile($tmp)
            if ($want -contains "$($img.Width)x$($img.Height)") { break }
            $img.Dispose(); $img = $null
            Start-Sleep -Milliseconds 500
        }
        if (-not $img) { throw "Device never captured at $Size — the frame override did not take." }

        # Crop the status bar off. Its clock drifts across a shoot and its notification
        # icons say what else is installed, and One UI ignores SystemUI demo mode, so
        # the bar comes off afterwards rather than being frozen first. Full screen has
        # already dropped both bars, so the landscape shot is kept whole.
        try {
            $top = if ($shot.landscape) { 0 } else { $CropTop }
            $rect = [System.Drawing.Rectangle]::new(0, $top, $img.Width, $img.Height - $top)
            $cropped = ([System.Drawing.Bitmap]$img).Clone($rect, $img.PixelFormat)
            try { $cropped.Save($dest, [System.Drawing.Imaging.ImageFormat]::Png) }
            finally { $cropped.Dispose() }
            Write-Host "  -> $dest  ($($rect.Width)x$($rect.Height))" -ForegroundColor Green
        } finally {
            $img.Dispose()
            Remove-Item $tmp -ErrorAction SilentlyContinue
        }
        $taken++
    }
} finally {
    if (-not $KeepFrame) {
        Adb shell wm size reset | Out-Null
        Adb shell wm density reset | Out-Null
    }
}

Write-Host ""
Write-Host "$taken shot(s) written to $OutDir." -ForegroundColor Green
Write-Host "Check each one for anything that shouldn't be public before committing —" -ForegroundColor Yellow
Write-Host "pairing token, the desktop behind the mirror, share thread, shared folder," -ForegroundColor Yellow
Write-Host "LAN addresses, the Windows username, the phone's own display name." -ForegroundColor Yellow
