$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$orchestrator = Join-Path $PSScriptRoot 'run-e2e-orchestrator.ps1'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('novalink-orchestrator-' + [guid]::NewGuid().ToString('N'))
$previous = @{}
foreach ($name in @('E2E_TEST_ROWS', 'E2E_TEST_BATCH_EXIT', 'E2E_SELECTED', 'E2E_PLATFORM', 'GITHUB_OUTPUT', 'GITHUB_STEP_SUMMARY')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-Case([string]$name, [string]$platforms, [string]$rows, [int]$batchExit,
                     [int]$expectedExit, [string]$expectedStatus, [bool]$strict = $false) {
    $env:E2E_TEST_ROWS = $rows
    $env:E2E_TEST_BATCH_EXIT = "$batchExit"
    $args = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $orchestrator,
              '-RepoRoot', $temp, '-Platforms', $platforms)
    if ($strict) { $args += '-RequireAllPlatforms' }
    & powershell.exe @args | Out-Null
    if ($LASTEXITCODE -ne $expectedExit) { throw "${name}: exit $LASTEXITCODE, expected $expectedExit" }
    if ($expectedStatus) {
        $summary = Get-Content (Join-Path $temp '.e2e-artifacts/runs/e2e-orchestrator-summary.json') -Raw | ConvertFrom-Json
        if ($summary.status -ne $expectedStatus) { throw "${name}: status $($summary.status), expected $expectedStatus" }
    }
}

try {
    New-Item -ItemType Directory -Force -Path (Join-Path $temp 'test/bin/multiplatform'), (Join-Path $temp '.e2e-artifacts/runs') | Out-Null
    Set-Content (Join-Path $temp 'test/bin/verify-orchestration-paths.ps1') 'exit 0'
    @'
param($Platforms, $RepoRoot, $TimeoutSec, $BotWaitSec, [switch]$RequireAllPlatforms)
[IO.File]::WriteAllText((Join-Path $RepoRoot 'selected-platforms.txt'), $Platforms)
if ($env:E2E_TEST_ROWS -ne 'NONE') {
    [IO.File]::WriteAllText((Join-Path $RepoRoot '.e2e-artifacts/runs/e2e-summary.json'), $env:E2E_TEST_ROWS)
}
exit ([int]$env:E2E_TEST_BATCH_EXIT)
'@ | Set-Content (Join-Path $temp 'test/bin/multiplatform/run-multiplatform-e2e.ps1')
    Assert-Case 'valid' 'folia' '[{"platform":"folia","status":"PASS"}]' 0 0 'PASS'
    Assert-Case 'canonical platform' ' PNX ' '[{"platform":"pnx","status":"PASS"}]' 0 0 'PASS'
    if ([IO.File]::ReadAllText((Join-Path $temp 'selected-platforms.txt')) -cne 'pnx') { throw 'platform was not normalized before dispatch' }
    Assert-Case 'batch exit 1 despite pass' 'folia' '[{"platform":"folia","status":"PASS"}]' 1 1 'BATCH-FAILED'
    Assert-Case 'batch exit 2 despite pass' 'folia' '[{"platform":"folia","status":"PASS"}]' 2 1 'BATCH-FAILED'
    Assert-Case 'batch failure preserves skip' 'folia' '[{"platform":"folia","status":"SKIPPED"}]' 1 1 'SKIPPED'
    Assert-Case 'mixed pass and design skip' 'folia,velocity' '[{"platform":"folia","status":"PASS"},{"platform":"velocity","status":"SKIPPED-BY-DESIGN"}]' 0 0 ''
    Assert-Case 'unknown selection' 'folia,typo' 'NONE' 0 2 ''
    Assert-Case 'empty selection' ',' 'NONE' 0 2 ''
    Assert-Case 'zero rows' 'folia' '[]' 0 1 'FAIL'
    Assert-Case 'duplicate rows' 'folia' '[{"platform":"folia","status":"PASS"},{"platform":"folia","status":"PASS"}]' 0 1 'FAIL'
    Assert-Case 'unknown status' 'folia' '[{"platform":"folia","status":"BOGUS"}]' 0 1 'BOGUS'
    Assert-Case 'partial' 'folia' '[{"platform":"folia","status":"PARTIAL"}]' 0 1 'PARTIAL'
    Assert-Case 'strict environment skip' 'folia' '[{"platform":"folia","status":"SKIPPED"}]' 3 1 'SKIPPED' $true
    Assert-Case 'environment skip' 'folia' '[{"platform":"folia","status":"SKIPPED"}]' 3 0 'SKIPPED'
    Assert-Case 'strict design skip' 'velocity' '[{"platform":"velocity","status":"SKIPPED-BY-DESIGN"}]' 4 0 'SKIPPED-BY-DESIGN' $true

    $workflow = Get-Content (Join-Path $root '.github/workflows/e2e.yml') -Raw
    $match = [regex]::Match($workflow, '(?ms)^      - name: Validate requested platforms\r?\n.*?^        run: \|\r?\n(?<body>(?:^          [^\r\n]*\r?\n)+)')
    if (-not $match.Success) { throw 'workflow validation step not found' }
    $validation = [scriptblock]::Create(($match.Groups['body'].Value -replace '(?m)^          ', ''))
    foreach ($case in @(
        @{ input = ''; fails = $false },
        @{ input = 'folia, bukkit'; fails = $false },
        @{ input = ' PNX, Folia '; fails = $false },
        @{ input = 'folia,typo'; fails = $true },
        @{ input = 'typo'; fails = $true },
        @{ input = ','; fails = $true }
    )) {
        $env:E2E_SELECTED = $case.input
        $failed = $false
        try { & $validation } catch { $failed = $true }
        if ($failed -ne $case.fails) { throw "workflow validation failed for '$($case.input)'" }
    }

    $match = [regex]::Match($workflow, '(?ms)^      - name: Select E2E platform\r?\n.*?^        run: \|\r?\n(?<body>(?:^          [^\r\n]*\r?\n)+)')
    if (-not $match.Success) { throw 'workflow selection step not found' }
    $selection = [scriptblock]::Create(($match.Groups['body'].Value -replace '(?m)^          ', ''))
    $env:E2E_SELECTED = ' Folia '
    $env:E2E_PLATFORM = 'bukkit'
    $env:GITHUB_OUTPUT = Join-Path $temp 'selection-output.txt'
    $env:GITHUB_STEP_SUMMARY = Join-Path $temp 'selection-summary.txt'
    & $selection
    if ((Get-Content $env:GITHUB_OUTPUT -Raw) -notmatch 'selected=false' -or
        (Get-Content $env:GITHUB_STEP_SUMMARY -Raw) -cnotmatch 'requested set \(folia\)') {
        throw 'unselected workflow platform was not explicitly skipped'
    }
    $env:E2E_SELECTED = ' PNX '
    $env:E2E_PLATFORM = 'pnx'
    $env:GITHUB_OUTPUT = Join-Path $temp 'selection-normalized.txt'
    & $selection
    if ((Get-Content $env:GITHUB_OUTPUT -Raw) -notmatch 'selected=true') { throw 'normalized workflow selection did not match pnx' }
    Write-Output 'Orchestrator and selection offline checks passed'
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    if (Test-Path -LiteralPath $temp) {
        $resolved = (Resolve-Path -LiteralPath $temp).Path
        $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
        if ((Split-Path -Parent $resolved) -ne $parent -or (Split-Path -Leaf $resolved) -notmatch '^novalink-orchestrator-[0-9a-f]{32}$') {
            throw 'Refusing unexpected test cleanup path'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
