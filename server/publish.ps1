# Publish Portal Remote as a single self-contained .exe.
#
# Self-contained because the whole point is running on a PC that has no .NET
# installed — the dev-time run.ps1 path depends on a user-local SDK plus a
# DOTNET_ROOT override, which is not something to ask an end user for.
#
# OutputType is overridden to WinExe here rather than in the csproj: the console
# window is wanted during development (server logs) and not in a shipped tray app.
#
# Usage:
#   .\publish.ps1                 -> server\publish\PortalRemote.exe
#   .\publish.ps1 -Output D:\out

param(
    [string]$Output = (Join-Path $PSScriptRoot 'publish'),
    # Stamped into the assembly and reported by ServerInfo.Version, which is what the
    # tray's update check compares against GitHub. CI passes the tag being built.
    [string]$Version = '0.1.0'
)

$ErrorActionPreference = 'Stop'

$LocalDotnet = Join-Path $env:LOCALAPPDATA 'Microsoft\dotnet'
$DotnetExe = if (Test-Path (Join-Path $LocalDotnet 'dotnet.exe')) {
    Join-Path $LocalDotnet 'dotnet.exe'
} else {
    'dotnet'
}
if (Test-Path (Join-Path $LocalDotnet 'shared\Microsoft.AspNetCore.App')) {
    $env:DOTNET_ROOT = $LocalDotnet
}

$Project = Join-Path $PSScriptRoot 'PortalRemote.Server\PortalRemote.Server.csproj'

# Trimming is unsupported for WinForms, so compression is the only lever on size —
# it roughly halves the .exe in exchange for a slower first start.
Write-Host "Publishing to $Output ..." -ForegroundColor Cyan
& $DotnetExe publish $Project `
    -p:Version=$Version `
    -c Release `
    -r win-x64 `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true `
    -p:OutputType=WinExe `
    -p:DebugType=none `
    -o $Output `
    --nologo
if ($LASTEXITCODE -ne 0) { throw 'Publish failed.' }

$Exe = Join-Path $Output 'PortalRemote.exe'
$SizeMb = [math]::Round((Get-Item $Exe).Length / 1MB, 1)
Write-Host ""
Write-Host "  $Exe  ($SizeMb MB)" -ForegroundColor Green
Write-Host "  Runs on a PC with no .NET installed. Allow it through Windows"
Write-Host "  Firewall on Private networks the first time it starts."
