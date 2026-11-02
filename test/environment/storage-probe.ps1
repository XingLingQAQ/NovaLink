function Assert-EnvironmentStorageEvidence($Result, [string]$Engine) {
    $spec = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'cases.psd1')
    $expected = @($spec.RequiredAssertions[$Engine] | Where-Object { $_ -notmatch '-(GAME-DELIVERY|STORED|RESTARTED|SAME-ROW|POST-RESTART-GAME)$' })
    $actual = @($Result.assertions | ForEach-Object id)
    if ($Result.status -cne 'PASS' -or $Result.engine -cne $Engine -or
        $Result.verification -cne 'real-database-provider' -or $expected.Count -eq 0 -or
        $actual.Count -ne $expected.Count -or @($actual | Select-Object -Unique).Count -ne $actual.Count -or
        @($expected | Where-Object { $_ -cnotin $actual }).Count -gt 0 -or
        @($Result.assertions | Where-Object { $_.pass -ne $true -or $_.verification -cne 'real-database-provider' }).Count -gt 0) {
        throw "$Engine storage probe has missing, duplicate or failed real-database evidence"
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
