function Start-E2EProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$Stdout,
        [Parameter(Mandatory = $true)][string]$Stderr
    )
    $process = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr -PassThru -WindowStyle Hidden
    # PS 5.1 needs the handle retained before the child exits.
    $null = $process.Handle
    return [pscustomobject]@{ Process = $process; Pid = $process.Id; Stdout = $Stdout; Stderr = $Stderr }
}

function Get-E2EProcessResult {
    param([Parameter(Mandatory = $true)]$Execution, [bool]$TimedOut = $false)
    $exitCode = $null
    try {
        if ($Execution.Process.HasExited) { $exitCode = $Execution.Process.ExitCode }
    } catch { }
    if ($null -eq $exitCode) { $exitCode = -1 }
    return [pscustomobject]@{
        Pid = $Execution.Pid; ExitCode = $exitCode; TimedOut = $TimedOut
        Stdout = $Execution.Stdout; Stderr = $Execution.Stderr
    }
}

function Wait-E2EProcess {
    param([Parameter(Mandatory = $true)]$Execution,
          [Parameter(Mandatory = $true)][ValidateRange(1, 2147483)][int]$TimeoutSec)
    $process = $Execution.Process
    $timedOut = -not $process.WaitForExit($TimeoutSec * 1000)
    if ($timedOut) {
        try { if (-not $process.HasExited) { $process.Kill() } }
        catch { if (-not $process.HasExited) { throw } }
    }
    $process.WaitForExit()
    Get-E2EProcessResult -Execution $Execution -TimedOut $timedOut
}
