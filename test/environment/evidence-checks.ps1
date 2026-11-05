function ConvertTo-EnvironmentTimestamp($Value) {
    if ($Value -is [DateTimeOffset]) { return $Value }
    if ($Value -is [DateTime]) { return [DateTimeOffset]::new($Value) }
    return [DateTimeOffset]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Test-EnvironmentBotRecording($Result, [string]$RunId, [string]$Phase, [string]$Role, [string]$HandoffDir) {
    $name = if ($Role -ceq 'bukkit') { 'EnvBukkit' } elseif ($Role -ceq 'fabric') { 'EnvFabric' } else { return $false }
    if (-not $Result -or $Result.runId -cne "$RunId-$Phase" -or $Result.role -cne $Role -or
        $Result.kind -cne 'java' -or $Result.username -cne $name -or $Result.timedOut -isnot [bool] -or $Result.timedOut -or
        $Result.error -or $Result.state.kicked -or $Result.state.self -cne $name) { return $false }
    foreach ($item in @($Result) + @($Result.sent) + @($Result.received) + @($Result.assertions) + @($Result.absentChecks)) {
        if ($null -eq $item) { return $false }
        if ($null -ne $item.synthetic -and ($item.synthetic -isnot [bool] -or $item.synthetic)) { return $false }
    }
    if (@(@($Result.assertions) + @($Result.absentChecks) | Where-Object { $_.pass -isnot [bool] -or -not $_.pass }).Count -gt 0 -or
        @($Result.received | Where-Object { $_.kind -in @('error', 'planError', 'kicked') }).Count -gt 0) { return $false }
    $spawn = @($Result.received | Where-Object { $_.kind -ceq 'spawn' -and $_.raw -ceq 'spawned' -and [long]$_.atMs -gt 0 })
    $ends = @($Result.received | Where-Object { $_.kind -ceq 'end' -and [long]$_.atMs -gt 0 })
    if ($spawn.Count -ne 1 -or $ends.Count -ne 1) { return $false }
    try {
        $key = "$Role-complete"
        $complete = Get-Content -Raw -LiteralPath (Join-Path $HandoffDir "$RunId-$Phase-$Role-$key.json") | ConvertFrom-Json
        $completedAt = (ConvertTo-EnvironmentTimestamp $complete.t).ToUnixTimeMilliseconds()
        if ($complete.runId -cne "$RunId-$Phase" -or $complete.role -cne $Role -or $complete.key -cne $key -or
            $complete.value -cne 'spawned' -or $completedAt -lt [long]$spawn[0].atMs -or [long]$ends[0].atMs -lt $completedAt) { return $false }
        if (@($Result.sent | Where-Object { [long]$_.atMs -lt [long]$spawn[0].atMs -or [long]$_.atMs -gt $completedAt }).Count -gt 0 -or
            @($Result.received | Where-Object { [long]$_.atMs -gt [long]$ends[0].atMs }).Count -gt 0) { return $false }
    } catch { return $false }
    if ($Phase -ceq 'rejected') {
        if (@($Result.assertions).Count -ne 0 -or ($Role -ceq 'bukkit' -and @($Result.absentChecks).Count -ne 0)) { return $false }
    } else {
        $expectedId = if ($Role -ceq 'bukkit') { 'from-fabric' } else { 'to-fabric' }
        $origin = if ($Role -ceq 'bukkit') { 'fabric' } else { 'bukkit' }
        $assertions = @($Result.assertions)
        if ($assertions.Count -ne 1 -or $assertions[0].id -cne $expectedId -or
            $assertions[0].token -cne "$RunId-$Phase-$origin" -or @($Result.absentChecks).Count -ne 0) { return $false }
    }
    return $true
}

function Test-EnvironmentDelivery($Sender, $Receiver, [string]$Token) {
    if (-not $Sender -or -not $Receiver -or $Sender.runId -cne $Receiver.runId -or
        $Sender.role -ceq $Receiver.role -or $Sender.username -ceq $Receiver.username) { return $false }
    $expectedSender = if ($Sender.role -ceq 'bukkit' -and $Receiver.role -ceq 'fabric') { 'EnvBukkit' }
        elseif ($Sender.role -ceq 'fabric' -and $Receiver.role -ceq 'bukkit') { 'EnvFabric' } else { return $false }
    if ($Sender.username -cne $expectedSender) { return $false }
    $sent = @($Sender.sent | Where-Object { $_.text -ceq $Token -and [long]$_.atMs -gt 0 })
    if ($sent.Count -ne 1) { return $false }
    $envelope = "[Global] ${expectedSender}: $Token"
    return @($Receiver.received | Where-Object { $_.kind -ceq 'messagestr' -and $_.text -ceq $envelope -and
        $_.raw -ceq $envelope -and [long]$_.atMs -ge [long]$sent[0].atMs }).Count -gt 0
}

function Test-EnvironmentGameRow($Row, $Sender, [string]$Token) {
    $sent = @($Sender.sent | Where-Object { $_.text -ceq $Token -and [long]$_.atMs -gt 0 })
    $player = @($Sender.received | Where-Object { $_.kind -ceq 'playerJoined' -and $_.data.player -ceq 'EnvBukkit' })
    return $null -ne $Row -and $sent.Count -eq 1 -and $player.Count -eq 1 -and
        [long]$Row.id -gt 0 -and $Row.channelId -ceq 'global' -and
        $Row.senderId -ceq $player[0].data.uuid -and $Row.senderName -ceq 'EnvBukkit' -and
        $Row.clientId -ceq 'ENV_Bukkit' -and $Row.content -ceq $Token -and
        [long]$Row.timestamp -ge [long]$sent[0].atMs -and [long]$Row.timestamp -le ([long]$sent[0].atMs + 30000)
}

function Test-EnvironmentNegativeEvidence($Sender, $Receiver, [string]$Token, [string]$HandoffDir, [string]$RunId) {
    $checks = @($Receiver.absentChecks)
    $sent = @($Sender.sent | Where-Object { $_.text -ceq $Token -and [long]$_.atMs -gt 0 })
    if ($checks.Count -ne 1 -or $checks[0].id -cne 'rejected-token' -or
        $checks[0].token -cne $Token -or $checks[0].pass -isnot [bool] -or -not $checks[0].pass -or $sent.Count -ne 1 -or
        @($Receiver.received | Where-Object { $_.kind -ceq 'messagestr' -and ([string]$_.text).Contains($Token) }).Count -ne 0) { return $false }
    $spawn = @($Receiver.received | Where-Object { $_.kind -ceq 'spawn' -and $_.raw -ceq 'spawned' -and [long]$_.atMs -le [long]$sent[0].atMs })
    if ($spawn.Count -ne 1) { return $false }
    try {
        $ready = Get-Content -Raw -LiteralPath (Join-Path $HandoffDir "$RunId-fabric-fabric-ready.json") | ConvertFrom-Json
        $published = Get-Content -Raw -LiteralPath (Join-Path $HandoffDir "$RunId-bukkit-bukkit-sent.json") | ConvertFrom-Json
        $observed = Get-Content -Raw -LiteralPath (Join-Path $HandoffDir "$RunId-fabric-fabric-observed.json") | ConvertFrom-Json
        if ($ready.runId -cne $RunId -or $ready.role -cne 'fabric' -or $ready.key -cne 'fabric-ready' -or $ready.value -cne 'spawned' -or
            (ConvertTo-EnvironmentTimestamp $ready.t).ToUnixTimeMilliseconds() -gt [long]$sent[0].atMs -or $published.runId -cne $RunId -or $published.role -cne 'bukkit' -or $published.key -cne 'bukkit-sent' -or $published.value -cne 'spawned' -or
            $observed.runId -cne $RunId -or $observed.role -cne 'fabric' -or $observed.key -cne 'fabric-observed' -or $observed.value -cne 'spawned') { return $false }
        $start = (ConvertTo-EnvironmentTimestamp $published.t).ToUnixTimeMilliseconds()
        $end = (ConvertTo-EnvironmentTimestamp $observed.t).ToUnixTimeMilliseconds()
        $receiverEnd = @($Receiver.received | Where-Object { $_.kind -ceq 'end' -and [long]$_.atMs -ge $end })
        return $start -ge [long]$sent[0].atMs -and ($end - $start) -ge 12000 -and $receiverEnd.Count -gt 0 -and
            $Receiver.handoff.'bukkit-sent' -ceq 'spawned' -and $Sender.handoff.'fabric-observed' -ceq 'spawned'
    } catch { return $false }
}

function Test-EnvironmentRestartLedger($Ledger, [string]$ExpectedRunId, [string]$InitialLog, [string]$RestartLog) {
    try {
        $old = $Ledger.old; $new = $Ledger.new
        if ([string]::IsNullOrWhiteSpace($ExpectedRunId) -or $Ledger.runId -cne $ExpectedRunId -or
            -not $old -or -not $new -or [long]$old.pid -le 0 -or [long]$new.pid -le 0 -or [long]$old.pid -eq [long]$new.pid -or
            $old.forced -isnot [bool] -or $old.forced -or $old.wasRunning -isnot [bool] -or -not $old.wasRunning -or
            ($old.exitCode -isnot [int] -and $old.exitCode -isnot [long]) -or $old.stopMode -cnotin @('sigterm', 'stdin')) { return $false }
        $oldStart = (ConvertTo-EnvironmentTimestamp $old.startedAt)
        $requested = (ConvertTo-EnvironmentTimestamp $old.stopRequestedAt)
        $oldExit = (ConvertTo-EnvironmentTimestamp $old.exitedAt)
        $newStart = (ConvertTo-EnvironmentTimestamp $new.startedAt)
        return $oldStart -lt $requested -and $requested -le $oldExit -and $oldExit -le $newStart -and
            $InitialLog -match 'NovaLink core stopped in \d+ ms' -and $RestartLog -match '(?i)started successfully'
    } catch { return $false }
}
