function Test-EnvironmentSyntheticMarker($Value) {
    if ($null -eq $Value) { return $false }
    if ($Value -is [Collections.IDictionary]) {
        if (-not $Value.Contains('synthetic')) { return $false }
        $marker = $Value['synthetic']
    } else {
        $property = $Value.PSObject.Properties['synthetic']
        if ($null -eq $property) { return $false }
        $marker = $property.Value
    }
    return $marker -isnot [bool] -or $marker
}

function Test-EnvironmentLegacyInvitations($Before, $After) {
    $beforeRows = @($Before); $afterRows = @($After)
    if ($beforeRows.Count -ne 1 -or $afterRows.Count -ne 1 -or
        @($beforeRows[0]).Count -ne 11 -or @($afterRows[0]).Count -ne 11) { return $false }
    for ($i = 0; $i -lt 11; $i++) {
        $a = $beforeRows[0][$i]; $b = $afterRows[0][$i]
        if ($null -eq $a -or $null -eq $b) { if ($null -ne $a -or $null -ne $b) { return $false } }
        elseif ($a -isnot [string] -or $b -isnot [string] -or $a -cne $b) { return $false }
    }
    return $true
}

function Assert-EnvironmentStorageEvidence($Result, [string]$Engine) {
    $spec = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'cases.psd1')
    $expected = @($spec.RequiredAssertions[$Engine] | Where-Object { $_ -notmatch '-(GAME-DELIVERY|STORED|RESTARTED|SAME-ROW|POST-RESTART-GAME|POST-RESTART-STORED)$' })
    $actual = @($Result.assertions | ForEach-Object id)
    if ((Test-EnvironmentSyntheticMarker $Result) -or $Result.status -cne 'PASS' -or $Result.engine -cne $Engine -or
        $Result.verification -cne 'real-database-provider' -or $expected.Count -eq 0 -or
        $actual.Count -ne $expected.Count -or @($actual | Select-Object -Unique).Count -ne $actual.Count -or
        @($expected | Where-Object { $_ -cnotin $actual }).Count -gt 0 -or
        @($Result.assertions | Where-Object { $_.pass -isnot [bool] -or -not $_.pass -or $_.verification -cne 'real-database-provider' }).Count -gt 0) {
        throw "$Engine storage probe has missing, duplicate or failed real-database evidence"
    }
    $serverPattern = switch ($Engine) { 'mysql' { '^(MySQL|MariaDB)\s+\d' }; 'postgresql' { '^PostgreSQL\s+\d' }; 'redis' { '^redis_version:\d' } }
    if (-not $serverPattern -or $Result.server -notmatch $serverPattern) { throw "$Engine database identity missing or inconsistent" }
    $op = $Result.operations
    if (-not $op -or $op.target -notmatch '^[0-9a-fA-F-]{36}$' -or $op.wrong -notmatch '^[0-9a-fA-F-]{36}$' -or $op.target -eq $op.wrong -or
        $op.wrongResult -cne 0 -or @($op.claims).Count -ne 8 -or
        @($op.claims | Where-Object { $_ -cne 0 -and $_ -cne 1 }).Count -ne 0 -or
        @($op.claims | Where-Object { $_ -ceq 1 }).Count -ne 1) { throw "$Engine operation results missing or invalid" }
    foreach ($state in @($op.saved, $op.databaseSaved, $op.afterWrong, $op.reconnected)) {
        if (-not $state -or $state.target -cne $op.target -or $state.used -isnot [bool] -or $state.used -or
            $state.usedCount -cne 0 -or $null -ne $state.usedBy -or $state.usedAt -cne 0) { throw "$Engine unconsumed state invalid" }
    }
    foreach ($state in @($op.consumed, $op.reconnectedConsumed, $op.databaseConsumed)) {
        if (-not $state -or $state.target -cne $op.target -or $state.used -isnot [bool] -or -not $state.used -or
            $state.usedCount -cne 1 -or $state.usedBy -cne $op.target -or [long]$state.usedAt -le 0) { throw "$Engine consumed state invalid" }
    }
    if ($op.consumed.usedAt -cne $op.reconnectedConsumed.usedAt -or $op.consumed.usedAt -cne $op.databaseConsumed.usedAt) { throw "$Engine reconnect changed the consumed state" }
    if ($Engine -ne 'redis') {
        if (@($Result.v15Migrations.PSObject.Properties).Count -ne 15 -or @($Result.v16Migrations.PSObject.Properties).Count -ne 16) { throw "$Engine migration snapshots missing" }
        foreach ($i in 1..15) {
            $old = $Result.v15Migrations."$i"; $new = $Result.v16Migrations."$i"
            if (@($old).Count -ne 7 -or $old[0] -cne "$i" -or $old[2] -cne 'COMPLETED' -or
                ($old | ConvertTo-Json -Compress) -cne ($new | ConvertTo-Json -Compress)) { throw "$Engine migration $i mismatch" }
        }
        if ($Result.v16Migrations.'16'[0] -cne '16' -or $Result.v16Migrations.'16'[2] -cne 'COMPLETED' -or
            -not (Test-EnvironmentLegacyInvitations $Result.v15Invitations $Result.v16Invitations) -or @($Result.v15Messages).Count -ne 1 -or
            ($Result.v15Messages | ConvertTo-Json -Depth 8 -Compress) -cne ($Result.v16Messages | ConvertTo-Json -Depth 8 -Compress)) { throw "$Engine migration data invalid" }
    }

}

function Invoke-EnvironmentStorageProbe($Context, $Database, [string]$ProbeClassPathFile) {
    if (-not [IO.File]::Exists($ProbeClassPathFile)) { throw 'Build environmentStorageProbeClasspath and pass -StorageProbeClassPathFile' }
    $classpath = (Read-EnvironmentText $ProbeClassPathFile).Trim()
    if (-not $classpath) { throw 'Storage probe classpath is empty' }
    $settings = @{
        engine = $Database.Type; host = $Database.Host; port = [int]$Database.Port
        database = $Database.Database; password = $Database.Password
    }
    if ($Database.Type -ne 'redis') {
        $settings.username = $Database.Username
        $settings.sslMode = if ($Database.Type -eq 'mysql') { 'REQUIRED' } else { 'require' }
    }
    $configPath = Join-Path $Context.Private 'storage-probe.json'
    $resultPath = Join-Path $Context.Public 'storage-probe.json'
    Write-EnvironmentText $configPath ($settings | ConvertTo-Json)
    $null = Invoke-EnvironmentNative $Context 'storage-probe' $Context.Java @(
        '-cp', $classpath, 'com.nova.link.environment.StorageEnvironmentProbe', $configPath, $resultPath) 90
    $result = (Read-EnvironmentText $resultPath) | ConvertFrom-Json
    Assert-EnvironmentStorageEvidence $result $Database.Type
    return $result.assertions
}
