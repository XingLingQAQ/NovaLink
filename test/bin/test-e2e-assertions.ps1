$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-assertions.ps1')

$script:checks = 0
$stdout = [System.IO.Path]::GetTempFileName()
$stderr = [System.IO.Path]::GetTempFileName()
$resultsFile = [System.IO.Path]::GetTempFileName()

function New-GoodResults {
    return [pscustomobject]@{
        runId = 'current-run'
        sequenceComplete = $true
        received = @(
            [pscustomobject]@{ kind = 'spawn'; text = 'spawned' },
            [pscustomobject]@{ kind = 'text:raw'; text = '[Global] Bot: hello' }
        )
    }
}

function Assert-Run {
    param([string]$Name, $Results, $ExitCode = 0, [bool]$TimedOut = $false,
          [bool]$ShouldFail = $false, [string[]]$Logs = @($stdout, $stderr))
    $reason = Get-BotRunFailure -Results $Results -ExitCode $ExitCode -TimedOut $TimedOut `
        -ExpectedRunId 'current-run' -ServerLogPaths $Logs
    if ([bool]$reason -ne $ShouldFail) { throw "${Name}: unexpected verdict '$reason'" }
    $script:checks++
}

try {
    Assert-Run 'completed' (New-GoodResults)
    Assert-Run 'runner timeout' (New-GoodResults) -TimedOut $true -ShouldFail $true
    Assert-Run 'nonzero exit after chat' (New-GoodResults) -ExitCode 1 -ShouldFail $true
    Assert-Run 'unknown exit code' (New-GoodResults) -ExitCode $null -ShouldFail $true
    Assert-Run 'missing results' $null -ShouldFail $true

    $result = New-GoodResults; $result.runId = 'previous-run'
    Assert-Run 'stale results' $result -ShouldFail $true
    $result = New-GoodResults; $result.sequenceComplete = $false
    Assert-Run 'unfinished sequence after chat' $result -ShouldFail $true
    $result = New-GoodResults; $result.sequenceComplete = 'true'
    Assert-Run 'nonboolean completion' $result -ShouldFail $true
    $result = New-GoodResults; $result.PSObject.Properties.Remove('sequenceComplete')
    Assert-Run 'missing completion' $result -ShouldFail $true
    $result = New-GoodResults; $result | Add-Member timedOut $true
    Assert-Run 'bot timeout after chat' $result -ShouldFail $true
    $result = New-GoodResults; $result | Add-Member error 'sequence failed'
    Assert-Run 'top-level error' $result -ShouldFail $true
    $result = New-GoodResults; $result.received = @()
    Assert-Run 'no spawn' $result -ShouldFail $true
    foreach ($kind in @('error', 'kicked')) {
        $result = New-GoodResults
        $result.received += [pscustomobject]@{ kind = $kind; text = 'failed after chat' }
        Assert-Run $kind $result -ShouldFail $true
    }
    $result = New-GoodResults
    $result.received += [pscustomobject]@{ kind = 'end'; text = 'normal quit' }
    Assert-Run 'normal quit' $result
    Assert-Run 'missing log' (New-GoodResults) -ShouldFail $true -Logs @("$stdout.missing")
    $lockedLog = [System.IO.File]::Open($stderr, 'Open', 'ReadWrite', 'None')
    try { Assert-Run 'unreadable log' (New-GoodResults) -ShouldFail $true } finally { $lockedLog.Dispose() }
    [System.IO.File]::WriteAllText($stderr, 'FATAL: failed after chat')
    Assert-Run 'fatal server log' (New-GoodResults) -ShouldFail $true

    [System.IO.File]::WriteAllText($resultsFile, 'old run')
    $runId = Initialize-BotRun $resultsFile
    if ((Test-Path -LiteralPath $resultsFile) -or -not $runId -or $env:E2E_RUN_ID -ne $runId) {
        throw 'stale result initialization failed'
    }
    $script:checks++

    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'run-e2e-orchestrator.ps1'), [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw 'orchestrator parse failed' }
    $mapping = $ast.Find({ param($node)
        $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left.Extent.Text -eq '$mpRowExit'
    }, $true)
    if (-not $mapping) { throw 'platform status mapping missing' }
    $mapStatus = [scriptblock]::Create($mapping.Extent.Text)
    foreach ($case in @(@('PASS', 0), @('FAIL', 1), @('PARTIAL', 1), @('UNKNOWN', 1),
                         @('SKIPPED', 3), @('SKIPPED-BY-DESIGN', 4))) {
        $mpStatus = $case[0]
        . $mapStatus
        if ($mpRowExit -ne $case[1]) { throw "incorrect status mapping: $mpStatus" }
        $script:checks++
    }
    Write-Output "E2E verdict checks: $script:checks passed"
} finally {
    foreach ($file in @($stdout, $stderr, $resultsFile)) {
        if (Test-Path -LiteralPath $file -PathType Leaf) { Remove-Item -LiteralPath $file -Force }
    }
}
