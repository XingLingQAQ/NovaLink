# ============================================================================
# fetch-server.ps1 -- download + verify a Minecraft server binary for E2E.
#
# Two modes (per docs/REAL-SERVER-E2E.md §4.2, extended 2026-08-09):
#   PINNED (default):
#       fetch-server.ps1 -Name <platform> -DistDir <path>
#       Uses the Url + Sha256 pinned in versions.lock.ps1. Downloads to a .tmp,
#       Get-FileHash -Algorithm SHA256, compares to the lock. Mismatch -> delete
#       tmp, error out (never run an unverified jar). This is for functional +
#       joint E2E where the version must be reproducible.
#   AUTO (-Auto):
#       fetch-server.ps1 -Name <platform> -Auto -DistDir <path>
#       Calls the platform's API at runtime to discover the LATEST build + URL,
#       downloads it, computes SHA-256 on the fly, and logs the hash for
#       traceability. Used for the smoke matrix where each plugin is tested
#       against its declared api-version's latest supported server. Auto mode
#       does NOT fail on hash mismatch (the latest can change daily); it still
#       verifies when the API supplies a content hash (PaperMC fill-data URLs
#       embed the SHA-256 in the path -- those ARE verified).
#
# Cache: if dist/<File> exists AND the hash matches (pinned) OR the sidecar
# matches (Auto), reuse without re-downloading. BDS zips are extracted to
# dist/bds-<version>/ and the extracted dir is cached too.
#
# Cross-platform: Invoke-WebRequest with -TimeoutSec 300 and 3 retry attempts.
# Works on the Windows CI runner. Set $env:HTTP_PROXY / HTTPS_PROXY if a proxy
# is needed (e.g. http://127.0.0.1:7890).
#
# NOTE on interrupted transfers: a truncated body can still look like a
# completed request (the BDS zip is ~86 MB and one run here left a 13.8 MB
# .tmp that Invoke-WebRequest had reported as success). A hash mismatch on a
# pinned or content-addressed artifact is therefore retried from a fresh .tmp
# a bounded number of times before failing, so a dropped transfer does not
# look like a poisoned binary.
# ============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Name,

    [switch]$Auto,

    [Parameter(Mandatory = $true)]
    [string]$DistDir
)

$ErrorActionPreference = 'Stop'
# fetch-server.ps1 lives at <repo>/test/bin/. versions.lock.ps1 lives at
# <repo>/test/.
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$testDir = Split-Path -Parent $scriptDir
. (Join-Path $testDir 'versions.lock.ps1')

$entry = Get-LockedServer -Name $Name
if (-not $entry) {
    Write-Error "No version lock entry for server '$Name'. Add it to test/versions.lock.ps1."
    exit 1
}

if (-not (Test-Path $DistDir)) { New-Item -ItemType Directory -Path $DistDir -Force | Out-Null }

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------

# Get-FileHash lives in Microsoft.PowerShell.Utility, which the GitHub
# windows-2022 runner's powershell.exe does not always auto-load in a
# -NoProfile child process (observed as CommandNotFoundException only after the
# download had already succeeded, so the fetch looked like a network failure).
# Hashing through System.Security.Cryptography needs no module at all.
function Get-FileSha256([string]$file) {
    if (-not (Test-Path $file)) { return $null }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($file)
        try {
            return ([System.BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToUpper()
        } finally {
            $stream.Dispose()
        }
    } finally {
        $sha.Dispose()
    }
}

function Test-Sha256([string]$file, [string]$expectedHash) {
    if (-not $expectedHash) { return $false }
    if (-not (Test-Path $file)) { return $false }
    $actual = Get-FileSha256 $file
    return $actual -eq $expectedHash.ToUpper()
}

# Download with retry (3 attempts, 300s timeout each). $dest is a .tmp path;
# the caller renames it atomically on success.
function Invoke-DownloadWithRetry([string]$url, [string]$destTmp) {
    $maxAttempts = 3
    for ($i = 1; $i -le $maxAttempts; $i++) {
        try {
            Write-Host "[fetch] download attempt $i/$maxAttempts : $url"
            # -UseBasicParsing avoids the IE engine dependency on Server Core / CI.
            Invoke-WebRequest -Uri $url -OutFile $destTmp -UseBasicParsing -TimeoutSec 300
            return
        } catch {
            if (Test-Path $destTmp) { Remove-Item $destTmp -Force -ErrorAction SilentlyContinue }
            if ($i -eq $maxAttempts) {
                throw "Download failed after $maxAttempts attempts: $($_.Exception.Message)"
            }
            Write-Host "[fetch] attempt $i failed ($($_.Exception.Message)); retrying..."
            Start-Sleep -Seconds 2
        }
    }
}

# Read a sidecar hash file (dist/<File>.sha256) -- the last hash we verified.
function Read-Sidecar([string]$sidecarPath) {
    if (Test-Path $sidecarPath) {
        return (Get-Content $sidecarPath -Raw).Trim().ToUpper()
    }
    return $null
}

# ----------------------------------------------------------------------------
# Per-platform auto-detect: return @{ Url; File; ExpectedSha256 } where
# ExpectedSha256 is $null unless the API itself returns a content hash.
# ----------------------------------------------------------------------------

function Resolve-PurpurLatest([hashtable]$entry) {
    # api.purpurmc.org/v2/purpur/<MC> -> builds.latest, then download at
    #   https://api.purpurmc.org/v2/purpur/<MC>/<build>/download  (singular,
    #   no filename -- the OLD /builds/<b>/downloads/<file>.jar path 404s now).
    $mc = $entry.MC
    $apiUrl = "https://api.purpurmc.org/v2/purpur/$mc"
    $r = Invoke-WebRequest -Uri $apiUrl -UseBasicParsing -TimeoutSec 60
    $json = $r.Content | ConvertFrom-Json
    $latest = $json.builds.latest
    if (-not $latest) { throw "Purpur API returned no latest build for MC $mc" }
    $file = "purpur-$mc-$latest.jar"
    $url = "https://api.purpurmc.org/v2/purpur/$mc/$latest/download"
    return @{ Url = $url; File = $file; ExpectedSha256 = $null; Build = $latest }
}

function Resolve-PaperMcLatest([string]$project, [string]$filePrefix) {
    # papermc.io/downloads/<project> HTML embeds content-addressed URLs on
    # https://fill-data.papermc.io/v1/objects/<sha256>/<file>.jar -- the SHA is
    # IN the URL path. The first match on the page is the latest build.
    $pageUrl = "https://papermc.io/downloads/$project"
    $r = Invoke-WebRequest -Uri $pageUrl -UseBasicParsing -TimeoutSec 60
    # Match the first fill-data URL for this project's jars.
    $pattern = "https://fill-data\.papermc\.io/v1/objects/([0-9a-fA-F]{64})/($([regex]::Escape($filePrefix))-[^`"&]+\.jar)"
    $m = [regex]::Match($r.Content, $pattern)
    if (-not $m.Success) {
        throw "Could not find a $project jar URL on $pageUrl (PaperMC API v2 is sunset; page scrape is the fallback)."
    }
    return @{
        Url = $m.Groups[0].Value
        File = $m.Groups[2].Value
        ExpectedSha256 = $m.Groups[1].Value.ToUpper()  # content-addressed -> verify
    }
}

function Resolve-NukkitLatest {
    # repo.opencollab.dev maven-snapshots maven-metadata.xml -> timestamp +
    # buildNumber -> jar URL. (Jenkins is 502-down; the maven mirror is canonical.)
    $metaUrl = 'https://repo.opencollab.dev/maven-snapshots/cn/nukkit/nukkit/1.0-SNAPSHOT/maven-metadata.xml'
    $r = Invoke-WebRequest -Uri $metaUrl -UseBasicParsing -TimeoutSec 60
    $xml = [xml]$r.Content
    $ts = $xml.metadata.versioning.snapshot.timestamp
    $build = $xml.metadata.versioning.snapshot.buildNumber
    if (-not $ts -or -not $build) { throw "Nukkit maven-metadata.xml returned no timestamp/buildNumber" }
    $ver = "1.0-$ts-$build"
    $file = "nukkit-$ver.jar"
    $url = "https://repo.opencollab.dev/maven-snapshots/cn/nukkit/nukkit/1.0-SNAPSHOT/$file"
    return @{ Url = $url; File = $file; ExpectedSha256 = $null; Build = $build }
}

function Resolve-SpongeLatest {
    # spongepowered.org/downloads/spongevanilla HTML lists universal-jar URLs
    # from the maven-releases repo. The first stable (non-RC) universal jar is
    # the latest. (api.spongepowered.org is unreachable; maven /search returns
    # library jars only, NOT the runnable universal jar.)
    $pageUrl = 'https://spongepowered.org/downloads/spongevanilla'
    $r = Invoke-WebRequest -Uri $pageUrl -UseBasicParsing -TimeoutSec 60
    # Match universal jar URLs; prefer stable (no -RC) -- filter RC out.
    $pattern = 'https://repo\.spongepowered\.org/repository/maven-releases/org/spongepowered/spongevanilla/[^"]+/spongevanilla-[^"]+-universal\.jar'
    $all = [regex]::Matches($r.Content, $pattern) | ForEach-Object { $_.Value }
    $stable = $all | Where-Object { $_ -notmatch '-RC\d' } | Select-Object -First 1
    $url = $stable
    if (-not $url) {
        # Fall back to any universal jar if no stable match.
        $url = $all | Select-Object -First 1
    }
    if (-not $url) { throw "Could not find a spongevanilla universal jar URL on $pageUrl" }
    $file = Split-Path -Leaf $url
    return @{ Url = $url; File = $file; ExpectedSha256 = $null }
}

function Resolve-PocketMineLatest {
    # update.pmmp.io/api?channel=stable -> JSON with base_version + download_url.
    $apiUrl = 'https://update.pmmp.io/api?channel=stable'
    $r = Invoke-WebRequest -Uri $apiUrl -UseBasicParsing -TimeoutSec 60
    $json = $r.Content | ConvertFrom-Json
    if (-not $json.download_url) { throw "update.pmmp.io returned no download_url" }
    $file = Split-Path -Leaf $json.download_url
    return @{
        Url = $json.download_url
        File = $file
        ExpectedSha256 = $null
        Build = $json.build
        MC = $json.base_version
    }
}

function Resolve-BdsLatest {
    # Mojang serves the current BDS binaries from the Minecraft services links
    # API (the same JSON the minecraft.net download page consumes):
    #   result.links[] -> { downloadType, downloadUrl }
    # serverBedrockWindows is the Windows BDS zip.
    #
    # The download host changed shape: the old
    #   minecraft.net/bedrockdedicatedserverbinaries/win/<file>
    # form 404s for every variant; links now point at
    #   minecraft.net/bedrockdedicatedserver/bin-win/<file>
    $apiUrl = 'https://net-secondary.web.minecraft-services.net/api/v1.0/download/links'
    try {
        $r = Invoke-WebRequest -Uri $apiUrl -UseBasicParsing -TimeoutSec 60
    } catch {
        Write-Warning "[fetch] BDS auto-detect failed: the Minecraft services links API ($apiUrl) is unreachable from this environment: $($_.Exception.Message)"
        return @{ Skip = $true; SkipReason = 'Minecraft services links API unreachable' }
    }

    $json = $r.Content | ConvertFrom-Json
    $link = @($json.result.links) | Where-Object { $_.downloadType -eq 'serverBedrockWindows' } | Select-Object -First 1
    if (-not $link -or -not $link.downloadUrl) {
        Write-Warning "[fetch] BDS auto-detect failed: the links API answered but carried no serverBedrockWindows entry."
        return @{ Skip = $true; SkipReason = 'links API returned no serverBedrockWindows entry' }
    }

    $url = $link.downloadUrl
    $file = Split-Path -Leaf $url
    $m = [regex]::Match($file, '^bedrock-server-(?<ver>[0-9][0-9.]*)\.zip$')
    if (-not $m.Success) { throw "Unrecognised BDS file name '$file' from the links API." }
    # "1.26.52.3" -> MC 1.26.52 + Build 3; a three-part "1.26.40" -> MC 1.26.40.
    $parts = $m.Groups['ver'].Value.Split('.')
    if ($parts.Count -ge 4) {
        $mc = $parts[0..2] -join '.'
        $build = $parts[3..($parts.Count - 1)] -join '.'
    } else {
        $mc = $m.Groups['ver'].Value
        $build = ''
    }

    # Cross-check the artifact against Endstone's per-version BDS metadata. Only
    # adopt the hash when the metadata names this EXACT URL, so it stays keyed to
    # the artifact we are about to fetch rather than to a version string that
    # Mojang could re-publish. No metadata (or a mismatch of URLs) is not an
    # error -- the caller then just logs the hash it computes after download.
    $expected = $null
    try {
        $metaUrl = "https://raw.githubusercontent.com/EndstoneMC/bedrock-server-data/v2/release/$mc/metadata.json"
        $meta = (Invoke-WebRequest -Uri $metaUrl -UseBasicParsing -TimeoutSec 30).Content | ConvertFrom-Json
        $win = $meta.binary.windows
        if ($win -and $win.url -eq $url -and $win.sha256) {
            $expected = $win.sha256.ToUpper()
            Write-Host "[fetch] BDS: using the published SHA-256 for $mc from Endstone bedrock-server-data."
        }
    } catch {
        Write-Host "[fetch] BDS: no published hash for $mc ($($_.Exception.Message)); the computed SHA-256 will be logged instead."
    }

    return @{ Url = $url; File = $file; ExpectedSha256 = $expected; Build = $build; MC = $mc }
}

function Resolve-Latest([hashtable]$entry) {
    switch ($entry.Name) {
        'purpur'      { return Resolve-PurpurLatest $entry }
        'folia'       { return Resolve-PaperMcLatest 'folia' 'folia' }
        'velocity'    { return Resolve-PaperMcLatest 'velocity' 'velocity' }
        'waterfall'   { return Resolve-PaperMcLatest 'waterfall' 'waterfall' }
        'nukkit'      { return Resolve-NukkitLatest }
        'sponge'      { return Resolve-SpongeLatest }
        'pocketmine'  { return Resolve-PocketMineLatest }
        'bds'         { return Resolve-BdsLatest }
        default       { throw "No auto-detect resolver implemented for '$($_)'" }
    }
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------

# Decide Url + File + ExpectedSha256.
$resolved = $null
if ($Auto) {
    Write-Host "[fetch] -Auto: detecting latest $($entry.Name)..."
    try {
        $resolved = Resolve-Latest $entry
    } catch {
        Write-Warning "[fetch] -Auto detect failed: $($_.Exception.Message)"
        Write-Warning "[fetch] falling back to pinned entry ($($entry.File))."
    }
}

# The version the extracted dist/<name>-<version>/ dir is keyed on. Auto mode
# can resolve a version the lock has not caught up with yet, so prefer the
# resolved one and fall back to the pin.
$effectiveMc = $entry.MC

if ($resolved) {
    # Auto-detect can return a Skip sentinel when the platform API is genuinely
    # unreachable. Callers treat exit 0 + no artifact as a documented skip, so
    # report the real reason and leave the decision to them.
    if ($resolved.Skip) {
        $why = if ($resolved.SkipReason) { $resolved.SkipReason } else { 'auto-detect unavailable' }
        Write-Host "[fetch] $($entry.Name): no download performed ($why). Exit 0 (not a script failure)."
        exit 0
    }
    $url = $resolved.Url
    $file = $resolved.File
    $expected = $resolved.ExpectedSha256  # may be $null (no API-supplied hash)
    if ($resolved.MC) { $effectiveMc = $resolved.MC }
    $autoMode = $true
} else {
    # Pinned mode (or Auto-detect fell back to the pin).
    $url = $entry.Url
    $file = $entry.File
    $expected = $entry.Sha256
    $autoMode = $false
    if ($Auto) {
        Write-Host "[fetch] using pinned $($entry.File) (Auto fallback / no resolver result)."
    }
}

$dest = Join-Path $DistDir $file
$sidecar = "$dest.sha256"

# BDS (Kind=zip): the extracted dir is the real cache target. Kind is the
# canonical field (jar|zip|phar); Extract=$true is a legacy fallback kept so
# older lock entries without Kind still trigger extraction.
$extractDir = $null
if ($entry.Kind -eq 'zip' -or $entry.Extract) {
    $versionTag = $effectiveMc
    $extractDir = Join-Path $DistDir "$($entry.Name)-$versionTag"
}

# Expand a Kind=zip archive to dist/<name>-<version>/ (the BDS run dir).
function Expand-ServerZip([string]$zipPath, [string]$targetDir) {
    if (Test-Path $targetDir) {
        Write-Host "[fetch] extracted dir already exists: $targetDir (reusing)"
        return
    }
    Write-Host "[fetch] extracting $zipPath -> $targetDir"
    Expand-Archive -Path $zipPath -DestinationPath $targetDir -Force
    Write-Host "[fetch] extracted: $targetDir"
}

# 1. Reuse if the cached file still verifies (pinned hash) or the sidecar
#    matches (Auto -- last hash we computed).
$cachedHash = $null
if (Test-Path $dest) {
    if ($expected) {
        if (Test-Sha256 -file $dest -expectedHash $expected) {
            Write-Host "[fetch] cache hit: $dest (SHA-256 verified)"
            if (-not (Test-Path $sidecar)) { $expected | Set-Content -Path $sidecar -NoNewline }
            # A cached zip whose extracted dir was cleaned out still needs the
            # extraction -- returning here would leave the caller with no BDS tree.
            if ($extractDir) { Expand-ServerZip $dest $extractDir }
            return
        }
    } else {
        # Auto mode without an API hash: reuse if the sidecar matches the
        # cached file (means we already downloaded + hashed it once).
        $sidecarHash = Read-Sidecar $sidecar
        $cachedHash = Get-FileSha256 $dest
        if ($sidecarHash -and $cachedHash -and ($sidecarHash -eq $cachedHash)) {
            Write-Host "[fetch] cache hit: $dest (SHA-256 $cachedHash matches sidecar; auto-mode, no pin to compare)"
            if ($extractDir) { Expand-ServerZip $dest $extractDir }
            return
        }
    }
}

# 2. Download to a PID-unique .tmp with retry. The tmp name embeds the current
#    PID so two concurrent fetch-server.ps1 invocations for the SAME platform
#    (e.g. two agents verifying purpur at once) do NOT collide on the same tmp
#    file handle -- which previously surfaced as "being used by another process"
#    on every retry attempt.
Write-Host "[fetch] downloading $url -> $dest"
$tmp = "$dest.$PID.download.tmp"
if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
# Best-effort cleanup of any orphaned tmps from a prior crashed run (different
# PID). We only remove tmps we can exclusively open (no lock); a locked tmp
# belongs to a live concurrent run and is left alone.
Get-ChildItem -Path $DistDir -Filter "$($entry.File).*download.tmp" -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.FullName -eq $tmp) { return }
    try {
        $fs = [System.IO.File]::Open($_.FullName, 'Open', 'ReadWrite', 'None')
        $fs.Close(); $fs.Dispose()
        Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
    } catch { }
}
# A transfer that is cut short can still report success to Invoke-WebRequest,
# so the hash check is the only thing that catches it. Retry the whole download
# from a fresh .tmp when a verified artifact comes back wrong -- a dropped
# transfer must not look like a tampered binary.
$maxVerifyAttempts = 3
$actual = $null
for ($attempt = 1; $attempt -le $maxVerifyAttempts; $attempt++) {
    Invoke-DownloadWithRetry -url $url -destTmp $tmp

    # 3. Verify / log hash.
    $actual = Get-FileSha256 $tmp
    if (-not $expected) { break }
    if ($actual -eq $expected.ToUpper()) { break }

    $gotSize = (Get-Item $tmp).Length
    Write-Warning "[fetch] SHA-256 mismatch for ${file} (attempt $attempt/$maxVerifyAttempts): expected $expected, got $actual, size $gotSize. Re-downloading from scratch."
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    if ($attempt -eq $maxVerifyAttempts) {
        Write-Error "SHA-256 mismatch for ${file} after $maxVerifyAttempts attempts: expected $expected, got $actual. Refusing to run unverified binary."
        exit 1
    }
}

if ($expected) {
    Write-Host "[fetch] SHA-256 verified: $actual"
} else {
    # Auto mode without an API-supplied hash: log the computed hash for
    # traceability but do NOT fail (the latest can change daily). This is the
    # documented smoke-testing-only path.
    Write-Warning "[fetch] -Auto mode: no pinned SHA-256 to compare. Computed SHA-256 = $actual (logged for traceability; smoke testing only)."
}

# 4. Atomic rename + sidecar.
Move-Item -Path $tmp -Destination $dest -Force
$actual | Set-Content -Path $sidecar -NoNewline
Write-Host "[fetch] verified + saved: $dest"

# 5. BDS extract (zip -> dist/bds-<version>/). Cached separately so BDS doesn't
#    re-extract each run.
if ($extractDir) { Expand-ServerZip $dest $extractDir }
