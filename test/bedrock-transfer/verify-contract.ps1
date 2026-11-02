[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Jdk21,
    [string]$OutputRoot = ''
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repo 'test/versions.lock.ps1')
$pin = Get-LockedServer -Name 'powernukkitx'
$serverJar = Join-Path $repo ".e2e-artifacts/dist/$($pin.File)"
if (-not (Test-Path -LiteralPath $serverJar -PathType Leaf)) { throw 'The pinned PNX 3.0.6 jar must already be cached; this check downloads and starts nothing.' }
if ((Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash -ine $pin.Sha256) { throw 'Cached PNX SHA-256 does not match the lock.' }
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo ('.e2e-artifacts/bedrock-transfer-contract-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputRoot) { throw 'Contract output must be a new isolated directory.' }
$null = New-Item -ItemType Directory -Path $OutputRoot
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
}
foreach ($file in @('run-bot.js', 'assert-evidence.js')) {
    & node --check (Join-Path $PSScriptRoot $file)
    if ($LASTEXITCODE -ne 0) { throw "JavaScript parse failed: $file" }
}
& node --test (Join-Path $PSScriptRoot 'contract.test.js') *> (Join-Path $OutputRoot 'protocol-contract.log')
if ($LASTEXITCODE -ne 0) { throw "Protocol contract failed: $OutputRoot/protocol-contract.log" }
$classes = Join-Path $OutputRoot 'classes'
$null = New-Item -ItemType Directory -Path $classes
$source = Join-Path $PSScriptRoot 'fixture/src/com/nova/bedrock/fixture/BedrockTransferFixture.java'
& (Join-Path $Jdk21 'bin/javac.exe') --release 21 -proc:none -encoding UTF-8 -cp $serverJar -d $classes $source `
    *> (Join-Path $OutputRoot 'fixture-compile.log')
if ($LASTEXITCODE -ne 0) { throw "Pinned PNX fixture compile failed: $OutputRoot/fixture-compile.log" }
$evidence = @{ pass = $true; scope = 'synthetic protocol contract + pinned native fixture compilation; no servers';
    serverSha256 = $pin.Sha256; serverVersion = $pin.Build; outputRoot = $OutputRoot }
$evidence | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputRoot 'contract-summary.json') -Encoding UTF8
Write-Host "PASS native transfer protocol contract + PNX 3.0.6 fixture compile: $OutputRoot"
