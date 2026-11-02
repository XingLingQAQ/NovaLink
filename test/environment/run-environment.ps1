#requires -Version 7.4
[CmdletBinding()]
param(
    [string[]]$Cases = @('tls-trusted', 'tls-untrusted', 'mysql', 'postgresql', 'redis'),
    [string]$RepoRoot = '', [string]$DistDir = '', [string]$OutputRoot = '', [string]$ClassPathFile = '', [string]$BackendJar = '',
    [string]$Jdk21 = $env:JAVA_HOME, [string]$StorageProbeClassPathFile = '',
    [int]$BackendPort = 27820, [int]$RestPort = 27830, [int]$BukkitPort = 27800, [int]$FabricPort = 27801,
    [int]$ServerReadySec = 480, [switch]$ProvisionDatabases, [switch]$PlanOnly
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scenarios.ps1')
if ($PlanOnly) {
    $definitions = @(Get-EnvironmentCaseDefinitions)
    foreach ($case in $Cases) { if ($case -notin $definitions.Name) { throw "Unknown environment case: $case" } }
    if ($Cases.Count -eq 0) { throw 'Select at least one environment case' }
    if (@($Cases | Select-Object -Unique).Count -ne $Cases.Count) { throw 'Environment cases must be unique' }
    $definitions | Where-Object Name -In $Cases | ConvertTo-Json -Depth 6
    exit 0
}
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
$arguments = @{} + $PSBoundParameters
$arguments.Remove('PlanOnly')
$arguments.RepoRoot = $RepoRoot
try {
    $assertions = @(Invoke-EnvironmentScenarios @arguments)
    $assertions | ConvertTo-Json -Depth 7
    if ($assertions.Count -eq 0 -or @($assertions | Where-Object { -not $_.pass }).Count -gt 0) { exit 1 }
    exit 0
} catch {
    $failure = $_.Exception.Message
    foreach ($entry in [Environment]::GetEnvironmentVariables().GetEnumerator()) {
        if ($entry.Key -match '^NOVALINK_ENV_.*_PASSWORD$' -and $entry.Value) { $failure = $failure.Replace([string]$entry.Value, '[REDACTED]') }
    }
    try {
        if (-not $OutputRoot) { $OutputRoot = Join-Path $RepoRoot '.e2e-artifacts/environment' }
        if (-not [IO.Path]::IsPathRooted($OutputRoot)) { $OutputRoot = Join-Path $RepoRoot $OutputRoot }
        $required = @()
        foreach ($definition in Get-EnvironmentCaseDefinitions | Where-Object Name -In $Cases) {
            foreach ($id in $definition.RequiredAssertionIds) {
                $required += @{ id = $id; pass = $false; verification = 'not-completed'; evidence = $failure }
            }
        }
        $summary = @{ status = 'FAIL'; verification = 'not-completed'; error = $failure; assertions = $required }
        $runId = [guid]::NewGuid().ToString('N')
        Write-EnvironmentText (Join-Path ([IO.Path]::GetFullPath($OutputRoot)) "$runId-runner/public/runner-failure.json") ($summary | ConvertTo-Json -Depth 7)
    } catch { Write-Warning 'Could not write filtered environment failure evidence' }
    Write-Error $failure -ErrorAction Continue
    exit 2
}
