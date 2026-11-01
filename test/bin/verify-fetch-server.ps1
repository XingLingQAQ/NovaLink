# Offline fixtures only; never starts a server or contacts an upstream.
[CmdletBinding()]
param([string[]]$Case = @())

$ErrorActionPreference = 'Stop'
$fetchScript = Join-Path $PSScriptRoot 'fetch-server.ps1'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'versions.lock.ps1')
foreach ($name in @('nukkit', 'sponge', 'pocketmine')) {
    $pin = Get-LockedServer -Name $name
    if (-not $pin -or $pin.Sha256 -notmatch '\A[0-9a-fA-F]{64}\z') { throw "Missing SHA-256 pin: $name" }
}

$workRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('novalink-fetch-contract-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $workRoot | Out-Null
$shell = (Get-Process -Id $PID).Path
$payload = [System.Text.Encoding]::UTF8.GetBytes('offline fetch contract fixture')
$hasher = [System.Security.Cryptography.SHA256]::Create()
try { $payloadHash = ([System.BitConverter]::ToString($hasher.ComputeHash($payload)) -replace '-', '') } finally { $hasher.Dispose() }
$checks = 0

$wrapper = Join-Path $workRoot 'invoke-fixture.ps1'
@'
param([string]$CaseDir)
$ErrorActionPreference = 'Stop'
$global:novaLinkFetchFixture = Import-Clixml (Join-Path $CaseDir 'test/fixture.xml')
$global:novaLinkFixtureDownloads = 0
function Invoke-WebRequest {
    param([string]$Uri, [string]$OutFile, [switch]$UseBasicParsing, [int]$TimeoutSec)
    if ($OutFile) {
        if ($Uri -ne $global:novaLinkFetchFixture.Url) { throw "Unexpected fixture download: $Uri" }
        $global:novaLinkFixtureDownloads++
        Add-Content -LiteralPath (Join-Path $CaseDir 'downloads.log') -Value $Uri
        $bytes = [Convert]::FromBase64String($global:novaLinkFetchFixture.Payload)
        if ($global:novaLinkFetchFixture.TruncatedFirst -and $global:novaLinkFixtureDownloads -eq 1) { $bytes = $bytes[0..3] }
        if ($global:novaLinkFetchFixture.WrongDownload) { $bytes = [byte[]]@(1, 2, 3) }
        [System.IO.File]::WriteAllBytes($OutFile, $bytes)
        return
    }
    switch ($global:novaLinkFetchFixture.ApiMode) {
        'purpur' { return @{ Content = '{"builds":{"latest":"8"}}' } }
        'paper' { return @{ Content = '<a href="' + $global:novaLinkFetchFixture.Url + '">download</a>' } }
        'bds' {
            if ($Uri -like '*metadata.json') {
                return @{ Content = (@{ binary = @{ windows = @{ url = $global:novaLinkFetchFixture.Url; sha256 = $global:novaLinkFetchFixture.ApiHash } } } | ConvertTo-Json -Depth 5) }
            }
            return @{ Content = (@{ result = @{ links = @(@{ downloadType = 'serverBedrockWindows'; downloadUrl = $global:novaLinkFetchFixture.Url }) } } | ConvertTo-Json -Depth 5) }
        }
        default { throw 'Offline fixture: upstream unavailable' }
    }
}
try {
    & (Join-Path $CaseDir 'test/bin/fetch-server.ps1') -Name $global:novaLinkFetchFixture.Name -DistDir (Join-Path $CaseDir 'cache') -Auto:([bool]$global:novaLinkFetchFixture.Auto)
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
'@ | Set-Content -LiteralPath $wrapper -Encoding UTF8

function Invoke-FetchCase([string]$id, [hashtable]$options) {
    if ($Case.Count -gt 0 -and $id -notin $Case) { return }
    $spec = @{
        Name = 'nukkit'; File = 'fixture.jar'; Url = 'https://fixture.invalid/fixture.jar'
        PinHash = $payloadHash; Payload = [Convert]::ToBase64String($payload)
        Auto = $false; ApiMode = 'unavailable'; Cache = 'none'; Sidecar = $false
        ExpectedCode = 0; ExpectedDownloads = 0; Message = ''; NoCacheDir = $false
        TruncatedFirst = $false; WrongDownload = $false; Kind = 'jar'; ApiHash = $null
    }
    foreach ($key in $options.Keys) { $spec[$key] = $options[$key] }
    $caseDir = Join-Path $workRoot $id
    $binDir = Join-Path $caseDir 'test/bin'
    New-Item -ItemType Directory -Path $binDir -Force | Out-Null
    Copy-Item -LiteralPath $fetchScript -Destination (Join-Path $binDir 'fetch-server.ps1')
    $spec.Entry = @{
        Name = $spec.Name; File = $spec.File; Url = $spec.Url; Sha256 = $spec.PinHash
        MC = '1.2.3'; Build = '8'; Kind = $spec.Kind
    }
    $spec | Export-Clixml -LiteralPath (Join-Path $caseDir 'test/fixture.xml')
    @'
$fixtureEntry = (Import-Clixml (Join-Path $PSScriptRoot 'fixture.xml')).Entry
function Get-LockedServer([string]$Name) { return $fixtureEntry }
'@ | Set-Content -LiteralPath (Join-Path $caseDir 'test/versions.lock.ps1') -Encoding UTF8
    $cacheDir = Join-Path $caseDir 'cache'
    $artifact = Join-Path $cacheDir $spec.File
    if ($spec.Cache -ne 'none') {
        New-Item -ItemType Directory -Path $cacheDir | Out-Null
        $cachedBytes = if ($spec.Cache -eq 'valid') { [Convert]::FromBase64String($spec.Payload) } else { [byte[]]@(1, 2, 3) }
        [System.IO.File]::WriteAllBytes($artifact, $cachedBytes)
        if ($spec.Sidecar) {
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $sidecarHash = ([System.BitConverter]::ToString($sha.ComputeHash($cachedBytes)) -replace '-', '') } finally { $sha.Dispose() }
            [System.IO.File]::WriteAllText("$artifact.sha256", $sidecarHash)
        }
    }
    $process = New-Object System.Diagnostics.Process
    try {
        $process.StartInfo.FileName = $shell
        $process.StartInfo.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -CaseDir "{1}"' -f $wrapper, $caseDir
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $stdout.Result + $stderr.Result
        $exitCode = $process.ExitCode
    } finally { $process.Dispose() }
    $downloads = if (Test-Path (Join-Path $caseDir 'downloads.log')) { @(Get-Content (Join-Path $caseDir 'downloads.log')).Count } else { 0 }
    if ($exitCode -ne $spec.ExpectedCode -or $downloads -ne $spec.ExpectedDownloads) {
        throw "${id}: exit=$exitCode (expected $($spec.ExpectedCode)), downloads=$downloads (expected $($spec.ExpectedDownloads))`n$output"
    }
    if ($spec.Message -and $output -notlike "*$($spec.Message)*") { throw "${id}: missing '$($spec.Message)'`n$output" }
    if ($spec.NoCacheDir -and (Test-Path $cacheDir)) { throw "${id}: preflight created a cache directory" }
    if ($exitCode -eq 0) {
        if (-not (Test-Path $artifact)) { throw "${id}: success without an artifact" }
        $actual = [System.IO.File]::ReadAllBytes($artifact)
        if ([Convert]::ToBase64String($actual) -ne $spec.Payload) { throw "${id}: cached artifact differs from fixture" }
    } elseif ($spec.Cache -eq 'valid') {
        if ([Convert]::ToBase64String([System.IO.File]::ReadAllBytes($artifact)) -ne $spec.Payload) { throw "${id}: rejected fetch changed the cached artifact" }
    }
    if ((Test-Path $cacheDir) -and @(Get-ChildItem -LiteralPath $cacheDir -Filter '*.download.tmp').Count -gt 0) { throw "${id}: leaked download temporary file" }
    if ($spec.Kind -eq 'zip' -and $exitCode -eq 0 -and -not (Test-Path (Join-Path $cacheDir "$($spec.Name)-1.2.3/fixture.txt"))) { throw "${id}: verified ZIP was not extracted" }
    $script:checks++
    Write-Host "[fetch-contract] CHECK $id (offline fixture)"
}

try {
    Invoke-FetchCase 'FETCH_PIN_MISSING_PREFLIGHT' @{ PinHash = ''; ExpectedCode = 1; NoCacheDir = $true; Message = 'fetch:PINNED_SHA256_REQUIRED' }
    Invoke-FetchCase 'FETCH_PIN_MISSING_REJECTS_SIDECAR' @{ PinHash = ''; Cache = 'valid'; Sidecar = $true; ExpectedCode = 1; Message = 'fetch:PINNED_SHA256_REQUIRED' }
    Invoke-FetchCase 'FETCH_PIN_INVALID_PREFLIGHT' @{ PinHash = ('G' * 64); ExpectedCode = 1; NoCacheDir = $true; Message = 'fetch:EXPECTED_SHA256_INVALID' }
    Invoke-FetchCase 'FETCH_PIN_PLACEHOLDER_PREFLIGHT' @{ PinHash = ('0' * 64); ExpectedCode = 1; NoCacheDir = $true; Message = 'fetch:EXPECTED_SHA256_INVALID' }
    Invoke-FetchCase 'FETCH_PIN_CACHE_VERIFIED' @{ Cache = 'valid'; Message = 'SHA-256 verified' }
    Invoke-FetchCase 'FETCH_PIN_SIDECAR_CANNOT_OVERRIDE' @{ Cache = 'wrong'; Sidecar = $true; ExpectedDownloads = 1; Message = 'SHA-256 verified' }
    Invoke-FetchCase 'FETCH_PIN_MISMATCH_FAILS' @{ Cache = 'valid'; PinHash = ('0123456789abcdef' * 4); ExpectedCode = 1; ExpectedDownloads = 3; Message = 'Refusing to run unverified binary' }
    Invoke-FetchCase 'FETCH_AUTO_NO_HASH_DOWNLOAD' @{ Name = 'purpur'; Auto = $true; PinHash = ''; ApiMode = 'purpur'; File = 'purpur-1.2.3-8.jar'; Url = 'https://api.purpurmc.org/v2/purpur/1.2.3/8/download'; ExpectedDownloads = 1; Message = 'smoke testing only' }
    Invoke-FetchCase 'FETCH_AUTO_NO_HASH_SIDECAR' @{ Name = 'purpur'; Auto = $true; PinHash = ''; ApiMode = 'purpur'; File = 'purpur-1.2.3-8.jar'; Url = 'https://api.purpurmc.org/v2/purpur/1.2.3/8/download'; Cache = 'valid'; Sidecar = $true; Message = 'matches sidecar' }
    $paperUrl = 'https://fill-data.papermc.io/v1/objects/' + $payloadHash.ToLower() + '/folia-1.2.3-8.jar'
    Invoke-FetchCase 'FETCH_AUTO_API_HASH_VERIFIED' @{ Name = 'folia'; Auto = $true; ApiMode = 'paper'; File = 'folia-1.2.3-8.jar'; Url = $paperUrl; Cache = 'wrong'; Sidecar = $true; ExpectedDownloads = 1; Message = 'SHA-256 verified' }
    Invoke-FetchCase 'FETCH_AUTO_API_HASH_MISMATCH_FAILS' @{ Name = 'folia'; Auto = $true; ApiMode = 'paper'; File = 'folia-1.2.3-8.jar'; Url = $paperUrl; WrongDownload = $true; ExpectedCode = 1; ExpectedDownloads = 3; Message = 'Refusing to run unverified binary' }
    Invoke-FetchCase 'FETCH_AUTO_FALLBACK_PIN_REQUIRED' @{ Name = 'purpur'; Auto = $true; PinHash = ''; Cache = 'valid'; Sidecar = $true; ExpectedCode = 1; Message = 'fetch:PINNED_SHA256_REQUIRED' }
    Invoke-FetchCase 'FETCH_AUTO_FALLBACK_PIN_VERIFIED' @{ Name = 'purpur'; Auto = $true; Cache = 'valid'; Message = 'SHA-256 verified' }
    Invoke-FetchCase 'FETCH_BDS_UNAVAILABLE_NO_SKIP' @{ Name = 'bds'; Auto = $true; PinHash = ''; ExpectedCode = 1; NoCacheDir = $true; Message = 'fetch:PINNED_SHA256_REQUIRED' }
    Invoke-FetchCase 'FETCH_AUTO_API_INVALID_HASH' @{ Name = 'bds'; Auto = $true; ApiMode = 'bds'; ApiHash = 'not-a-sha256'; File = 'bedrock-server-1.2.3.4.zip'; Url = 'https://fixture.invalid/bedrock-server-1.2.3.4.zip'; ExpectedCode = 1; NoCacheDir = $true; Message = 'fetch:EXPECTED_SHA256_INVALID' }
    Add-Type -AssemblyName System.IO.Compression
    $zipStream = New-Object System.IO.MemoryStream
    $archive = New-Object System.IO.Compression.ZipArchive($zipStream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    $zipEntry = $archive.CreateEntry('fixture.txt')
    $entryStream = $zipEntry.Open()
    $entryStream.Write($payload, 0, $payload.Length)
    $entryStream.Dispose()
    $archive.Dispose()
    $zipBytes = $zipStream.ToArray()
    $zipStream.Dispose()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $zipHash = ([System.BitConverter]::ToString($sha.ComputeHash($zipBytes)) -replace '-', '') } finally { $sha.Dispose() }
    Invoke-FetchCase 'FETCH_BDS_TRUNCATED_RETRY' @{ Name = 'bds'; Kind = 'zip'; File = 'bedrock-server-1.2.3.4.zip'; PinHash = $zipHash; Payload = [Convert]::ToBase64String($zipBytes); TruncatedFirst = $true; ExpectedDownloads = 2; Message = 'SHA-256 verified' }
    if ($checks -eq 0) { throw 'No offline fetch fixtures selected.' }
    Write-Host "[fetch-contract] OFFLINE CHECKS: $checks; no real server validation."
} finally {
    $resolvedRoot = [System.IO.Path]::GetFullPath($workRoot)
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedRoot.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolvedRoot) -notlike 'novalink-fetch-contract-*') { throw "Unsafe fixture cleanup path: $resolvedRoot" }
    Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
}
