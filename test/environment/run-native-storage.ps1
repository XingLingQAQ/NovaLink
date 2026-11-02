#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ProbeClassPathFile,
    [string[]]$Engines = @('mysql', 'postgresql', 'redis'),
    [string]$Jdk = "$env:USERPROFILE/scoop/apps/temurin21-jdk/current",
    [string]$MySqlBin = "$env:USERPROFILE/scoop/apps/mariadb/current/bin",
    [string]$PostgresBin = "$env:USERPROFILE/scoop/apps/postgresql/current/bin",
    [string]$RedisBin = "$env:USERPROFILE/scoop/apps/redis/current",
    [switch]$KeepDatabaseFiles
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'storage-probe.ps1')
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskRoot = [IO.Path]::GetFullPath((Join-Path $repo ('.e2e-artifacts/environment-native/' + [guid]::NewGuid().ToString('N'))))
$dataRoot = Join-Path $taskRoot 'private/data'
$logRoot = Join-Path $taskRoot 'private/logs'
$publicRoot = Join-Path $taskRoot 'public'
foreach ($directory in @($dataRoot, $logRoot, $publicRoot)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
$owned = [Collections.Generic.List[object]]::new()
$results = [Collections.Generic.List[object]]::new()
$ports = [Collections.Generic.List[int]]::new()
$utf8 = [Text.UTF8Encoding]::new($false)

function Get-NativeStoragePort {
    do {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try { $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    } while ($port -in @(3306, 5432, 6379) -or $ports.Contains($port))
    $ports.Add($port)
    return $port
}

function Start-NativeStorageProcess([string]$Name, [string]$Exe, [string[]]$Arguments) {
    if (-not [IO.File]::Exists($Exe)) { throw "Installed native executable missing: $Exe" }
    $quoted = @($Arguments | ForEach-Object { '"' + [regex]::Replace([regex]::Replace($_, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"' })
    $process = Start-Process -FilePath $Exe -ArgumentList ($quoted -join ' ') -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logRoot "$Name.stdout.log") -RedirectStandardError (Join-Path $logRoot "$Name.stderr.log")
    $null = $process.Handle
    $record = [pscustomobject]@{ Name = $Name; Process = $process; Executable = $Exe; Arguments = $Arguments }
    $owned.Add($record)
    return $record
}

function Invoke-NativeStorageTool([string]$Name, [string]$Exe, [string[]]$Arguments, [int]$TimeoutSec = 60) {
    $record = Start-NativeStorageProcess $Name $Exe $Arguments
    if (-not $record.Process.WaitForExit($TimeoutSec * 1000)) { throw "$Name timed out; see $logRoot" }
    $record.Process.WaitForExit()
    if ($record.Process.ExitCode -ne 0) { throw "$Name exited $($record.Process.ExitCode); see $logRoot" }
    return [IO.File]::ReadAllText((Join-Path $logRoot "$Name.stdout.log"))
}

function Wait-NativeStoragePort($Record, [int]$Port) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        if ($Record.Process.HasExited) { throw "$($Record.Name) exited before readiness; see $logRoot" }
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
            if ($pending.AsyncWaitHandle.WaitOne(250)) {
                $client.EndConnect($pending)
                return
            }
        } catch { } finally { $client.Dispose() }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$($Record.Name) did not bind its dedicated port"
}

function Assert-NativeStorageClasspath([string]$ClassPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($required in @('com/nova/link/environment/StorageEnvironmentProbe.class', 'com/nova/link/database/dialect/MigrationDialect.class')) {
        $found = $false
        foreach ($entry in $ClassPath.Split([IO.Path]::PathSeparator)) {
            if ([IO.Directory]::Exists($entry)) {
                if ([IO.File]::Exists((Join-Path $entry $required))) { $found = $true; break }
            } elseif ([IO.File]::Exists($entry)) {
                $archive = [IO.Compression.ZipFile]::OpenRead($entry)
                try { $found = $null -ne $archive.GetEntry($required) } finally { $archive.Dispose() }
                if ($found) { break }
            }
        }
        if (-not $found) {
            throw "Probe classpath is missing $required; rebuild with an isolated environmentBuildRoot and --project-cache-dir before starting databases"
        }
    }
}

$databases = @{}
$failure = $null
try {
    if ($Engines.Count -eq 0 -or @($Engines | Select-Object -Unique).Count -ne $Engines.Count -or
        @($Engines | Where-Object { $_ -notin @('mysql', 'postgresql', 'redis') }).Count -gt 0) {
        throw 'Choose unique engines from mysql, postgresql, redis'
    }
    $classpath = [IO.File]::ReadAllText([IO.Path]::GetFullPath($ProbeClassPathFile)).Trim()
    Assert-NativeStorageClasspath $classpath
    $java = Join-Path $Jdk 'bin/java.exe'
    foreach ($engine in $Engines) {
        $port = Get-NativeStoragePort
        $data = Join-Path $dataRoot $engine
        $config = @{ engine = $engine; host = '127.0.0.1'; port = $port; password = ''; database = 'novalink_environment' }
        if ($engine -eq 'mysql') {
            $mysqld = Join-Path $MySqlBin 'mysqld.exe'
            $version = Invoke-NativeStorageTool 'mysql-version' $mysqld @('--no-defaults', '--version')
            $config.username = 'root'; $config.sslMode = 'DISABLED'; $config.insecurePlaintext = $true
            if ($version -match 'MariaDB') {
                $null = Invoke-NativeStorageTool 'mysql-init' (Join-Path $MySqlBin 'mysql_install_db.exe') @("--datadir=$data", "--port=$port", '--silent') 90
            } else {
                $oracleBase = [IO.Path]::GetFullPath((Join-Path $MySqlBin '..'))
                $oracleOptions = @("--basedir=$oracleBase", '--innodb-buffer-pool-size=64M', '--innodb-redo-log-capacity=32M',
                    '--performance-schema=OFF', '--max-connections=16', '--table-open-cache=64')
                $null = Invoke-NativeStorageTool 'mysql-init' $mysqld (@('--no-defaults', '--initialize-insecure', "--datadir=$data") + $oracleOptions) 90
            }
            $serverArguments = @('--no-defaults', "--datadir=$data", '--bind-address=127.0.0.1', "--port=$port", '--console')
            if ($version -notmatch 'MariaDB') { $serverArguments += @('--mysqlx=OFF') + $oracleOptions }
            $server = Start-NativeStorageProcess 'mysql-server' $mysqld $serverArguments
            $databases[$engine] = @{ Record = $server; Port = $port; Data = $data }
            Wait-NativeStoragePort $server $port
            $null = Invoke-NativeStorageTool 'mysql-create-database' (Join-Path $MySqlBin 'mysql.exe') @(
                '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$port", '--user=root', '--execute=CREATE DATABASE novalink_environment')
        } elseif ($engine -eq 'postgresql') {
            $config.username = 'novalink_environment'; $config.sslMode = 'disable'; $config.insecurePlaintext = $true
            $null = Invoke-NativeStorageTool 'postgres-init' (Join-Path $PostgresBin 'initdb.exe') @('-D', $data, '-U', $config.username, '-A', 'trust', '--encoding=UTF8', '--no-locale') 90
            [IO.File]::WriteAllText((Join-Path $data 'pg_hba.conf'), "host all all 127.0.0.1/32 trust`nhost all all ::1/128 reject`n", $utf8)
            $server = Start-NativeStorageProcess 'postgres-server' (Join-Path $PostgresBin 'postgres.exe') @('-D', $data, '-h', '127.0.0.1', '-p', [string]$port, '-c', 'listen_addresses=127.0.0.1')
            $databases[$engine] = @{ Record = $server; Port = $port; Data = $data }
            Wait-NativeStoragePort $server $port
            $null = Invoke-NativeStorageTool 'postgres-create-database' (Join-Path $PostgresBin 'createdb.exe') @('-h', '127.0.0.1', '-p', [string]$port, '-U', $config.username, $config.database)
        } else {
            $config.database = 0
            [IO.Directory]::CreateDirectory($data) | Out-Null
            $server = Start-NativeStorageProcess 'redis-server' (Join-Path $RedisBin 'redis-server.exe') @(
                '--bind', '127.0.0.1', '--port', [string]$port, '--dir', ($data.Replace('\', '/')), '--save', '', '--appendonly', 'no')
            $databases[$engine] = @{ Record = $server; Port = $port; Data = $data }
            Wait-NativeStoragePort $server $port
        }
        $configPath = Join-Path $taskRoot "private/$engine.probe.json"
        $resultPath = Join-Path $publicRoot "$engine.storage-probe.json"
        [IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json), $utf8)
        $null = Invoke-NativeStorageTool "$engine-probe" $java @('-cp', $classpath, 'com.nova.link.environment.StorageEnvironmentProbe', $configPath, $resultPath) 90
        $result = [IO.File]::ReadAllText($resultPath) | ConvertFrom-Json
        Assert-EnvironmentStorageEvidence $result $engine
        $results.Add($result)
        Write-Host "$engine : $($result.server), $(@($result.assertions).Count) assertions PASS on 127.0.0.1:$port"
    }
} catch {
    $failure = $_.Exception.Message
} finally {
    foreach ($engine in @($databases.Keys)) {
        $database = $databases[$engine]
        if ($database.Record.Process.HasExited) { continue }
        try {
            if ($engine -eq 'mysql') {
                $null = Invoke-NativeStorageTool 'mysql-stop' (Join-Path $MySqlBin 'mysqladmin.exe') @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$($database.Port)", '--user=root', 'shutdown') 20
            } elseif ($engine -eq 'postgresql') {
                $null = Invoke-NativeStorageTool 'postgres-stop' (Join-Path $PostgresBin 'pg_ctl.exe') @('-D', $database.Data, '-m', 'fast', '-w', 'stop') 20
            } else {
                $null = Invoke-NativeStorageTool 'redis-stop' (Join-Path $RedisBin 'redis-cli.exe') @('-h', '127.0.0.1', '-p', [string]$database.Port, 'shutdown', 'nosave') 20
            }
        } catch {
            if (-not $failure) { $failure = $_.Exception.Message }
        }
    }
    foreach ($record in $owned) {
        if (-not $record.Process.HasExited -and -not $record.Process.WaitForExit(5000)) {
            Stop-Process -Id $record.Process.Id -Force -ErrorAction Continue
            $record.Process.WaitForExit(5000) | Out-Null
            if (-not $failure) { $failure = "Owned process required forced termination: $($record.Name)" }
        }
    }
    $ownership = @($owned | ForEach-Object { @{ name = $_.Name; pid = $_.Process.Id; exited = $_.Process.HasExited } })
    [IO.File]::WriteAllText((Join-Path $publicRoot 'owned-processes.json'), ($ownership | ConvertTo-Json -Depth 5), $utf8)
    if (-not $KeepDatabaseFiles) {
        $resolved = [IO.Path]::GetFullPath($dataRoot)
        $allowed = $taskRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
            ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            @($owned | Where-Object { -not $_.Process.HasExited }).Count -gt 0) {
            throw 'Refusing native database cleanup outside the task root or with live owned processes'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    $summary = @{ verification = 'real-database-provider'; engines = $Engines; results = $results.ToArray()
        status = $(if ($failure) { 'FAIL' } else { 'PASS' }); error = $failure
        transport = 'isolated loopback; SQL plaintext explicitly opted in; TLS game paths require manual environment workflow' }
    [IO.File]::WriteAllText((Join-Path $publicRoot 'native-storage-summary.json'), ($summary | ConvertTo-Json -Depth 14), $utf8)
    Write-Host "Evidence: $publicRoot"
}
if ($failure) { throw $failure }
