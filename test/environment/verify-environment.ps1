#requires -Version 5.1
[CmdletBinding()]
param([switch]$StaticOnly)
$ErrorActionPreference = 'Stop'
$files = @(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object Extension -In @('.ps1', '.psd1'))
foreach ($file in $files) {
    $parseErrors = $null
    $tokens = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) { throw "$($file.Name): $($parseErrors.Message -join '; ')" }
}
if ($StaticOnly) {
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $result = & node (Join-Path $PSScriptRoot 'verify-workflow.cjs') $repoRoot
    if ($LASTEXITCODE -ne 0) { throw 'Environment workflow static check failed' }
    $workflow = ($result -join "`n") | ConvertFrom-Json
    foreach ($step in $workflow.powershell) {
        $null = [Management.Automation.Language.Parser]::ParseInput($step.script, [ref]$tokens, [ref]$parseErrors)
        if ($parseErrors.Count -gt 0) { throw "$($step.name): $($parseErrors.Message -join '; ')" }
    }
    Write-Output 'Parsed environment PowerShell and workflow YAML; checked manual-only dispatch, Node 24, Ubuntu paths, module tasks and public-only uploads. No runtime validation was performed.'
    exit 0
}
$scenarioAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'scenarios.ps1'), [ref]$tokens, [ref]$parseErrors)
$pureFunctions = @('Get-EnvironmentCaseDefinitions', 'New-EnvironmentAssertion', 'Complete-EnvironmentAssertions', 'Test-EnvironmentDelivery')
foreach ($definition in $scenarioAst.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $true)) {
    if ($definition.Name -in $pureFunctions) { . ([scriptblock]::Create($definition.Extent.Text)) }
}
$definitions = @(Get-EnvironmentCaseDefinitions -SpecificationPath (Join-Path $PSScriptRoot 'cases.psd1'))
if ($definitions.Count -ne 4) { throw 'Expected four environment cases' }
$ids = @($definitions | ForEach-Object RequiredAssertionIds)
if (@($ids | Select-Object -Unique).Count -ne $ids.Count) { throw 'Assertion IDs must be unique' }
$missing = @(Complete-EnvironmentAssertions @() $ids)
if ($missing.Count -ne $ids.Count -or @($missing | Where-Object pass).Count -gt 0) { throw 'Missing evidence must fail every required assertion' }
$one = New-EnvironmentAssertion $ids[0] $true 'diagnostic fixture'
$partial = @(Complete-EnvironmentAssertions @($one) $ids)
if (@($partial | Where-Object pass).Count -ne 1 -or $partial.Count -ne $ids.Count) { throw 'Partial evidence must preserve failures' }
$rows = [pscustomobject]@{ received = @(
    [pscustomobject]@{ kind = 'messagestr'; text = '[Global] EnvBukkit: unique-token' },
    [pscustomobject]@{ kind = 'chat'; text = 'not-a-receiver-token' }
) }
if (-not (Test-EnvironmentDelivery $rows 'unique-token')) { throw 'Expected receiver chat match' }
if (Test-EnvironmentDelivery $rows 'not-a-receiver-token') { throw 'Non-rendered chat must not prove delivery' }
if (Test-EnvironmentDelivery $rows 'missing-token') { throw 'Absent token must fail' }
if (Test-EnvironmentDelivery $null 'unique-token') { throw 'Missing receiver must fail' }
Write-Output 'PowerShell syntax and pure evidence checks passed; no server, TLS handshake or SQL persistence was exercised.'
