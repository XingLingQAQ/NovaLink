$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-process.ps1')
$folder = Join-Path ([IO.Path]::GetTempPath()) ('novalink-process-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder | Out-Null
try {
    $runs = @()
    foreach ($code in @(0, 7)) {
        $runs += Start-E2EProcess -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-Command', "exit $code") `
            -WorkingDirectory $folder -Stdout (Join-Path $folder "$code.out") -Stderr (Join-Path $folder "$code.err")
    }
    foreach ($i in @(0, 1)) {
        $result = Wait-E2EProcess $runs[$i] 10
        if ($result.ExitCode -ne @(0, 7)[$i] -or $result.TimedOut) { throw 'Exit status lost' }
        if (-not (Test-Path $result.Stdout)) { throw 'Log path missing' }
    }
    $slow = Start-E2EProcess -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-Command', 'Start-Sleep -Seconds 30') `
        -WorkingDirectory $folder -Stdout (Join-Path $folder 'slow.out') -Stderr (Join-Path $folder 'slow.err')
    $timeout = Wait-E2EProcess $slow 1
    if (-not $timeout.TimedOut -or -not $slow.Process.HasExited) { throw 'Timeout did not terminate the owned child' }
    $unknown = Get-E2EProcessResult ([pscustomobject]@{ Process = [pscustomobject]@{ HasExited = $true; ExitCode = $null }; Pid = 0; Stdout = ''; Stderr = '' })
    if ($unknown.ExitCode -ne -1) { throw 'Unknown exit was accepted' }
    Write-Host 'PASS process exit, concurrent starts, timeout, unknown exit and log paths'
} finally {
    foreach ($run in @($runs) + @($slow)) {
        if ($run -and -not $run.Process.HasExited) { $run.Process.Kill(); $run.Process.WaitForExit() }
    }
    $resolved = [IO.Path]::GetFullPath($folder)
    if (-not $resolved.StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
