#requires -Version 7.4
[CmdletBinding()]
param([string]$RepoRoot = '', [string]$DistDir = '')
$ErrorActionPreference = 'Stop'
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
$RepoRoot = [IO.Path]::GetFullPath($RepoRoot)
if (-not $DistDir) { $DistDir = Join-Path $RepoRoot '.e2e-artifacts/dist' }
if (-not [IO.Path]::IsPathRooted($DistDir)) { $DistDir = Join-Path $RepoRoot $DistDir }
$DistDir = [IO.Path]::GetFullPath($DistDir)
[IO.Directory]::CreateDirectory($DistDir) | Out-Null
& (Join-Path $PSHOME $(if ($IsWindows) { 'pwsh.exe' } else { 'pwsh' })) -NoProfile -File (Join-Path $RepoRoot 'test/bin/fetch-server.ps1') -Name purpur -DistDir $DistDir
if ($LASTEXITCODE -ne 0) { throw 'Locked Purpur download failed' }
$runtime = (Import-PowerShellDataFile (Join-Path $PSScriptRoot 'cases.psd1')).FabricRuntime
foreach ($kind in @('Launcher', 'Api')) {
    if ($runtime["${kind}Sha256"] -notmatch '\A[0-9a-fA-F]{64}\z') { throw "$kind requires a valid pinned SHA-256" }
    $path = Join-Path $DistDir $runtime["${kind}File"]
    if ([IO.File]::Exists($path) -and (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $runtime["${kind}Sha256"]) { continue }
    $temporary = "$path.$([guid]::NewGuid().ToString('N')).download"
    try {
        Invoke-WebRequest -Uri $runtime["${kind}Url"] -OutFile $temporary -TimeoutSec 180 -MaximumRetryCount 2 -RetryIntervalSec 2
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $runtime["${kind}Sha256"]) { throw "$kind SHA-256 mismatch" }
        Move-Item -LiteralPath $temporary -Destination $path -Force
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}
Write-Output 'Locked Purpur and Fabric runtime files prepared; no server was started.'
