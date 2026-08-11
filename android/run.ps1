# Build (and optionally install) the Portal Remote Android app.
#
# Android Studio's SDK and bundled JDK are installed but not on PATH, so this
# script points JAVA_HOME/ANDROID_HOME at them for the duration of the build.
#
# Usage:
#   .\run.ps1              build the debug APK
#   .\run.ps1 -Install      also install onto a connected device/emulator
#   .\run.ps1 -Launch       install and launch the app

param(
    [switch]$Install,
    [switch]$Launch
)

$ErrorActionPreference = 'Stop'

# Only fill these in when the environment hasn't already: the SDK doesn't always
# live under LOCALAPPDATA (a second drive is a common choice), and overwriting a
# working ANDROID_HOME with a guess just breaks the build.
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }

if (-not (Test-Path $env:JAVA_HOME)) {
    throw "Android Studio JBR not found at $env:JAVA_HOME. Install Android Studio, or set JAVA_HOME to any JDK 17+."
}
if (-not (Test-Path $env:ANDROID_HOME)) {
    throw "Android SDK not found at $env:ANDROID_HOME. Install it via Android Studio's SDK Manager."
}

Push-Location $PSScriptRoot
try {
    if ($Launch) {
        & .\gradlew.bat installDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Build/install failed.' }
        & "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n com.portalremote/.MainActivity
    }
    elseif ($Install) {
        & .\gradlew.bat installDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Build/install failed.' }
    }
    else {
        & .\gradlew.bat assembleDebug --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
        Write-Host "APK: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
    }
}
finally {
    Pop-Location
}
