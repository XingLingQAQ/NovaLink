# ============================================================================
# fetch-server.ps1 -- download + SHA-256 verify a pinned server jar.
#
# Usage:
#   powershell -File fetch-server.ps1 -Name purpur -DistDir <path-to-dist>
#
# Flow (per docs/REAL-SERVER-E2E.md §4.2):
#   1. If dist/<File> exists AND dist/<File>.sha256 matches -> reuse (cache hit).
#   2. Otherwise download to a .tmp, Get-FileHash -Algorithm SHA256, compare to
#      the lock. Mismatch -> delete tmp, error out (never run an unverified jar).
#   3. Atomically rename to the final name, write the .sha256 sidecar.
#   4. Re-verify the hash on every run (defends against cache corruption).
# ============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Name,
    [Parameter(Mandatory = $true)]
    [string]$DistDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
. (Join-Path $repoRoot 'e2e\versions.lock.ps1')

$entry = Get-LockedServer -Name $Name
if (-not $entry) {
    Write-Error "No version lock entry for server '$Name'. Add it to e2e/versions.lock.ps1."
    exit 1
}

if (-not (Test-Path $DistDir)) { New-Item -ItemType Directory -Path $DistDir -Force | Out-Null }
$dest = Join-Path $DistDir $entry.File
$sidecar = "$dest.sha256"
$expected = $entry.Sha256.ToUpper()

function Test-Sha256([string]$file, [string]$expectedHash) {
    if (-not (Test-Path $file)) { return $false }
    $actual = (Get-FileHash -Path $file -Algorithm SHA256).Hash.ToUpper()
    return $actual -eq $expectedHash
}

# 1. Reuse if the cached jar still verifies.
if (Test-Sha256 -file $dest -expectedHash $expected) {
    Write-Host "[fetch] cache hit: $dest (SHA-256 verified)"
    if (-not (Test-Path $sidecar)) { $expected | Set-Content -Path $sidecar -NoNewline }
    return
}

Write-Host "[fetch] downloading $($entry.Url) -> $dest"
$tmp = "$dest.download.tmp"
try {
    # -UseBasicParsing avoids the IE engine dependency on Server Core / CI runners.
    Invoke-WebRequest -Uri $entry.Url -OutFile $tmp -UseBasicParsing -TimeoutSec 300
} catch {
    Write-Error "Download failed: $($_.Exception.Message)"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    exit 1
}

$actual = (Get-FileHash -Path $tmp -Algorithm SHA256).Hash.ToUpper()
if ($actual -ne $expected) {
    Remove-Item $tmp -Force
    Write-Error "SHA-256 mismatch for $($entry.File): expected $expected, got $actual. Refusing to run unverified jar."
    exit 1
}

# 3. Atomic rename + sidecar.
Move-Item -Path $tmp -Destination $dest -Force
$expected | Set-Content -Path $sidecar -NoNewline
Write-Host "[fetch] verified + saved: $dest"
