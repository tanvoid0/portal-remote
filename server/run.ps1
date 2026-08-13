# Build and launch the Portal Remote server.
#
# The ASP.NET Core 8 runtime lives in the user-local .NET install rather than
# C:\Program Files\dotnet, which only carries the base and desktop runtimes. The
# generated apphost searches Program Files by default, so DOTNET_ROOT has to point
# at the user-local root or startup fails with "No frameworks were found".
#
# Installing the ASP.NET Core 8 runtime machine-wide (admin required), or
# publishing self-contained, both remove the need for this.

$ErrorActionPreference = 'Stop'

$LocalDotnet = Join-Path $env:LOCALAPPDATA 'Microsoft\dotnet'
$DotnetExe = Join-Path $LocalDotnet 'dotnet.exe'

if (-not (Test-Path $DotnetExe)) {
    throw "No .NET SDK at $LocalDotnet. Install with: https://dot.net/v1/dotnet-install.ps1 -Channel 8.0"
}

if (Test-Path (Join-Path $LocalDotnet 'shared\Microsoft.AspNetCore.App')) {
    $env:DOTNET_ROOT = $LocalDotnet
}

$Solution = Join-Path $PSScriptRoot 'PortalRemote.sln'

Write-Host 'Building...' -ForegroundColor Cyan
& $DotnetExe build $Solution -v q --nologo
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }

# Found rather than hardcoded: the output folder is named after the target
# framework, and that moved once already (to the Windows SDK flavour, for the media
# session API). Newest wins, so a stale folder from a previous TFM is ignored.
$Exe = Get-ChildItem (Join-Path $PSScriptRoot 'PortalRemote.Server\bin\Debug') `
    -Filter PortalRemote.exe -Recurse |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $Exe) { throw 'Build produced no PortalRemote.exe.' }

Write-Host 'Starting Portal Remote...' -ForegroundColor Cyan
& $Exe @args
