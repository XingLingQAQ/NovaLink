<#
.SYNOPSIS
Verifies that committed test orchestration uses the canonical test/ source tree.

.DESCRIPTION
Runtime output directories such as .e2e/ and .e2e-artifacts/ are intentionally
allowed. Markdown is not scanned so historical documentation can discuss the
old layout without being treated as executable orchestration.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'
if (-not $RepoRoot) {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$failures = [System.Collections.Generic.List[string]]::new()

$requiredPaths = @(
    'test/README.md',
    'test/versions.lock.ps1',
    'test/conf/novalink.template.yml',
    'test/bot/package.json',
    'test/bot/package-lock.json',
    'test/bot/run-e2e.js',
    'test/bot/run-e2e-bedrock.js',
    'test/bin/build-plugin-phar.php',
    'test/bin/fetch-server.ps1',
    'test/bin/write-classpath.init.gradle',
    'test/bin/run-e2e-orchestrator.ps1',
    'test/bin/run-bukkit-e2e.ps1',
    'test/bin/run-endstone-e2e.ps1',
    'test/bin/run-levilamina-e2e.ps1',
    'test/bin/run-pmmp-e2e.ps1',
    'test/bin/multiplatform/run-multiplatform-e2e.ps1',
    'test/bin/multiplatform/start-backend.ps1',
    'test/bin/multiplatform/start-server.ps1'
)

foreach ($relativePath in $requiredPaths) {
    $absolutePath = Join-Path $RepoRoot ($relativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        $failures.Add("required harness file is missing: $relativePath")
    }
}

$scanFiles = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
foreach ($relativePath in @('build.gradle', 'settings.gradle')) {
    $absolutePath = Join-Path $RepoRoot $relativePath
    if (Test-Path -LiteralPath $absolutePath -PathType Leaf) {
        $scanFiles.Add((Get-Item -LiteralPath $absolutePath))
    }
}

$workflowRoot = Join-Path $RepoRoot '.github/workflows'
if (Test-Path -LiteralPath $workflowRoot -PathType Container) {
    Get-ChildItem -LiteralPath $workflowRoot -File |
        Where-Object { $_.Extension -in @('.yml', '.yaml') } |
        ForEach-Object { $scanFiles.Add($_) }
}

$testBinRoot = Join-Path $RepoRoot 'test/bin'
if (Test-Path -LiteralPath $testBinRoot -PathType Container) {
    Get-ChildItem -LiteralPath $testBinRoot -Recurse -File |
        Where-Object { $_.Extension -in @('.ps1', '.gradle', '.js', '.json', '.php') } |
        ForEach-Object { $scanFiles.Add($_) }
}

$testBotRoot = Join-Path $RepoRoot 'test/bot'
if (Test-Path -LiteralPath $testBotRoot -PathType Container) {
    Get-ChildItem -LiteralPath $testBotRoot -File |
        Where-Object { $_.Extension -in @('.js', '.json') } |
        ForEach-Object { $scanFiles.Add($_) }
}

Get-ChildItem -LiteralPath $RepoRoot -File |
    Where-Object { $_.Name -match '^build.*\.(ps1|cmd|bat|sh)$' } |
    ForEach-Object { $scanFiles.Add($_) }

$contractPath = $MyInvocation.MyCommand.Path
$legacyPathPattern = '(?<![.\w-])e2e[\\/]'
$legacyGradleDirPattern = '\bfile\s*\(\s*[''"]e2e[''"]\s*\)'

foreach ($file in ($scanFiles | Sort-Object FullName -Unique)) {
    if ($file.FullName -eq $contractPath) {
        continue
    }

    $lineNumber = 0
    foreach ($line in (Get-Content -LiteralPath $file.FullName)) {
        $lineNumber++
        if ($line -cmatch $legacyPathPattern -or $line -cmatch $legacyGradleDirPattern) {
            $relativePath = $file.FullName.Substring($RepoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
            $failures.Add("$relativePath`:$lineNumber references the removed e2e/ source directory")
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Host '[path-contract] FAIL'
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}

Write-Host '[path-contract] PASS: orchestration uses test/; runtime .e2e paths remain allowed.'
exit 0
