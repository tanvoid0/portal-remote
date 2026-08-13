# Generate the release signing keystore and store its passwords locally, without
# either ever appearing in git, this script's own output, or a shell history.
#
# The keystore is the app's permanent identity: every update has to be signed by the
# same one, forever, or existing installs can't upgrade over it. This script is
# deliberately idempotent (refuses to overwrite an existing keystore) for that reason.
#
# Usage:
#   .\sign-setup.ps1                 generate the keystore + secrets, print next steps
#   .\sign-setup.ps1 -Force          regenerate even if one already exists (rotates the
#                                     signing identity -- only do this if you mean to)
#   .\sign-setup.ps1 -BackupPath X   also copy the keystore + secrets to X. X can be
#                                     inside the repo only if `git check-ignore` confirms
#                                     the exact filenames are covered by .gitignore --
#                                     the script verifies this itself rather than assume.
#
# Everything lands under $HOME\.portal-remote-release, outside the repo entirely, so
# there's nothing for .gitignore to catch and nothing for `git add -A` to sweep up.
# The folder's ACL is narrowed to the current Windows account only.

param(
    [switch]$Force,
    [string]$Alias = 'portal',
    [int]$ValidityDays = 10000,
    [string]$SecretsDir = "$HOME\.portal-remote-release",
    [string]$BackupPath
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw "keytool not found on PATH. It ships with the JDK -- same one JAVA_HOME points at for the Android build."
}
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI (gh) not found on PATH. Install it to publish the secrets in the next step."
}

$keystorePath = Join-Path $SecretsDir 'portal-remote-release.jks'
$b64Path = "$keystorePath.b64"
$envPath = Join-Path $SecretsDir 'release.local.env'

function Assert-BackupPathSafe([string]$Path) {
    # $null means -BackupPath wasn't given; empty string would also mean "no", so
    # only validate when the caller actually passed something.
    if (-not $Path) { return $null }
    $repoRoot = & git -C $PSScriptRoot rev-parse --show-toplevel 2>$null
    $resolved = [IO.Path]::GetFullPath($Path)
    if ($repoRoot) {
        $repoRoot = (Resolve-Path $repoRoot).Path
        if ($resolved.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
            # Inside the repo is fine *if* git will actually never track these exact
            # filenames -- check the real target names against .gitignore rather than
            # assume the current patterns cover wherever -BackupPath happens to point.
            $backupKeystore = Join-Path $resolved 'portal-remote-release.jks'
            $backupEnv = Join-Path $resolved 'release.local.env'
            & git -C $repoRoot check-ignore -q $backupKeystore
            $keystoreIgnored = $LASTEXITCODE -eq 0
            & git -C $repoRoot check-ignore -q $backupEnv
            $envIgnored = $LASTEXITCODE -eq 0
            if (-not ($keystoreIgnored -and $envIgnored)) {
                throw "-BackupPath '$resolved' is inside the git repo and NOT fully covered by .gitignore (checked '$backupKeystore' and '$backupEnv' with 'git check-ignore'). Fix the gitignore patterns first, or point this outside the repo."
            }
            Write-Host "$resolved is inside the repo but confirmed .gitignore'd (git check-ignore) -- proceeding." -ForegroundColor DarkGray
        }
    }
    $resolved
}

function Show-NextSteps {
    Write-Host ""
    Write-Host "Keystore:  $keystorePath" -ForegroundColor Green
    Write-Host "Secrets:   $envPath (passwords live here -- nowhere else, back this up)" -ForegroundColor Green
    Write-Host ""
    Write-Host "Next: push the 4 GitHub secrets, reading straight from the files above" -ForegroundColor Cyan
    Write-Host "(nothing below has a password typed into it, gh reads the files itself):"
    Write-Host ""
    Write-Host "  gh secret set ANDROID_KEYSTORE_BASE64 --repo tanvoid0/portal-remote --body-file `"$b64Path`""
    Write-Host "  gh secret set ANDROID_KEYSTORE_PASSWORD --repo tanvoid0/portal-remote --body ((Get-Content `"$envPath`" | Select-String ANDROID_KEYSTORE_PASSWORD).Line.Split('=',2)[1])"
    Write-Host "  gh secret set ANDROID_KEY_ALIAS --repo tanvoid0/portal-remote --body '$Alias'"
    Write-Host "  gh secret set ANDROID_KEY_PASSWORD --repo tanvoid0/portal-remote --body ((Get-Content `"$envPath`" | Select-String ANDROID_KEY_PASSWORD).Line.Split('=',2)[1])"
    Write-Host ""
    Write-Host "Then back up $keystorePath and $envPath somewhere durable (password manager" -ForegroundColor Yellow
    Write-Host "attachment, encrypted archive) before this machine or that folder can be lost." -ForegroundColor Yellow
}

$resolvedBackup = Assert-BackupPathSafe $BackupPath

if ((Test-Path $keystorePath) -and -not $Force) {
    if (-not $resolvedBackup) {
        throw "$keystorePath already exists. Re-running would orphan it as the signing identity for anything already installed -- pass -Force only if you mean to rotate keys, or -BackupPath to just back up what's already there."
    }
    # Already generated -- a keystore is a one-time identity, not something to churn
    # through just to satisfy a backup request. Copy what exists and stop.
    Write-Host "$keystorePath already exists -- backing it up as-is, not regenerating." -ForegroundColor DarkGray
    New-Item -ItemType Directory -Force $resolvedBackup | Out-Null
    Copy-Item $keystorePath, $envPath -Destination $resolvedBackup -Force
    if (Test-Path $b64Path) { Copy-Item $b64Path -Destination $resolvedBackup -Force }
    Show-NextSteps
    return
}

New-Item -ItemType Directory -Force $SecretsDir | Out-Null

# Best-effort local hardening: this account only, no inheritance from the parent folder.
icacls $SecretsDir /inheritance:r | Out-Null
icacls $SecretsDir /grant:r "$($env:USERDOMAIN)\$($env:USERNAME):(OI)(CI)F" | Out-Null

function New-Secret {
    # URL-safe base64 of 32 random bytes -- 256 bits of entropy, no characters that
    # need escaping in an env file, a shell, or a keytool argument.
    # RNGCryptoServiceProvider rather than RandomNumberGenerator::Fill: Windows
    # PowerShell 5.1 runs on .NET Framework, which doesn't have the static Fill
    # method .NET 5+ added -- this instance API has worked since Framework 2.0.
    $bytes = [byte[]]::new(32)
    $rng = [System.Security.Cryptography.RNGCryptoServiceProvider]::new()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# Passed to keytool via env vars it reads itself (-storepass:env / -keypass:env), so
# the values never appear in argv (visible to other processes on the same machine)
# or get typed anywhere -- including here, since this script never Write-Hosts them.
$env:PR_STOREPASS = New-Secret
$env:PR_KEYPASS = New-Secret

try {
    & keytool -genkeypair -v `
        -keystore $keystorePath `
        -alias $Alias `
        -keyalg RSA -keysize 2048 -validity $ValidityDays `
        -storepass:env PR_STOREPASS -keypass:env PR_KEYPASS `
        -dname "CN=Portal Remote, OU=Portal Remote, O=Portal Remote, L=NA, ST=NA, C=US"
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }

    [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath)) |
        Set-Content -NoNewline $b64Path

    # Not the GITHUB_TOKEN kind of secret -- this is your own local record of what you
    # just generated, so you (not me, not git) can still read it back later.
    @(
        "ANDROID_KEYSTORE_PASSWORD=$($env:PR_STOREPASS)"
        "ANDROID_KEY_ALIAS=$Alias"
        "ANDROID_KEY_PASSWORD=$($env:PR_KEYPASS)"
    ) | Set-Content $envPath
}
finally {
    Remove-Item Env:\PR_STOREPASS, Env:\PR_KEYPASS -ErrorAction SilentlyContinue
}

if ($resolvedBackup) {
    New-Item -ItemType Directory -Force $resolvedBackup | Out-Null
    Copy-Item $keystorePath, $envPath, $b64Path -Destination $resolvedBackup -Force
}

Show-NextSteps
