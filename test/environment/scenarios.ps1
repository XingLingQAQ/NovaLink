#requires -Version 7.4

function Get-EnvironmentCaseDefinitions {
    param([string]$SpecificationPath = (Join-Path $PSScriptRoot 'cases.psd1'))
    $spec = Import-PowerShellDataFile $SpecificationPath
    foreach ($name in $spec.Cases) {
        [pscustomobject]@{
            Name = $name; ScenarioGroup = 'manual'; Topology = $spec.Topology
            RequiredAssertionIds = $spec.RequiredAssertions[$name]
            Preconditions = @('PowerShell >= 7.4', 'JDK21', 'Node24 + locked bot dependencies', 'prebuilt backend fat jar or classpath, Bukkit/Fabric fat jars',
                'locked Purpur + Fabric launcher + Fabric API in DistDir', 'OpenSSL', 'curl with CA verification') +
                $(if ($name -in @('mysql', 'postgresql')) { 'dedicated SQL database with TLS or -ProvisionDatabases' })
        }
    }
}

function Write-EnvironmentText([string]$Path, [string]$Value) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Read-EnvironmentText([string]$Path) {
    if (-not [IO.File]::Exists($Path)) { return '' }
    $stream = [IO.FileStream]::new($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }
}

function New-EnvironmentAssertion([string]$Id, [bool]$Pass, [string]$Evidence, [string]$Token = '') {
    [pscustomobject]@{ id = $Id; pass = $Pass; evidence = $Evidence; token = $Token; kind = 'environment'; verification = 'real-game' }
}

function Complete-EnvironmentAssertions([object[]]$Assertions, [string[]]$RequiredIds) {
    foreach ($assertion in $Assertions) { $assertion }
    foreach ($id in $RequiredIds) {
        if (@($Assertions | Where-Object id -eq $id).Count -eq 0) {
            New-EnvironmentAssertion $id $false 'Required real-game evidence was not produced'
        }
    }
}

function Start-EnvironmentProcess {
    param($Context, [string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory)
    [IO.Directory]::CreateDirectory($Directory) | Out-Null
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.WorkingDirectory = $Directory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $info.Environment['JOINT_BOT_MODULES'] = Join-Path $Context.RepoRoot 'test/bot/node_modules'
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $stdout = Join-Path $Directory "$Name.stdout.log"
    $stderr = Join-Path $Directory "$Name.stderr.log"
    $outStream = [IO.FileStream]::new($stdout, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite, 1, [IO.FileOptions]::Asynchronous)
    $errStream = [IO.FileStream]::new($stderr, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite, 1, [IO.FileOptions]::Asynchronous)
    $started = $false
    try {
        if (-not $process.Start()) { throw "Could not start $Name" }
        $started = $true
        $record = [pscustomobject]@{
            Name = $Name; Process = $process; Stdout = $stdout; Stderr = $stderr
            OutStream = $outStream; ErrStream = $errStream
            OutTask = $process.StandardOutput.BaseStream.CopyToAsync($outStream)
            ErrTask = $process.StandardError.BaseStream.CopyToAsync($errStream)
            Closed = $false; StopMode = 'stdin'; Forced = $false
        }
        $Context.Processes.Add($record)
        return $record
    } catch {
        if ($started -and -not $process.HasExited) { $process.Kill($true) }
        $outStream.Dispose(); $errStream.Dispose(); $process.Dispose()
        throw
    }
}

function Complete-EnvironmentProcess($Record) {
    if ($Record.Closed) { return }
    if (-not $Record.Process.HasExited) { throw "Process still running: $($Record.Name)" }
    $null = $Record.OutTask.GetAwaiter().GetResult()
    $null = $Record.ErrTask.GetAwaiter().GetResult()
    $Record.OutStream.Dispose(); $Record.ErrStream.Dispose()
    $Record.Closed = $true
}

function Stop-EnvironmentProcess($Record) {
    if ($null -eq $Record -or $Record.Closed) { return }
    if (-not $Record.Process.HasExited) {
        if ($Record.StopMode -eq 'sigterm') {
            & /bin/kill -TERM ([string]$Record.Process.Id)
            if ($LASTEXITCODE -ne 0 -and -not $Record.Process.HasExited) { throw "Could not signal owned process $($Record.Name)" }
        } else {
            try { $Record.Process.StandardInput.WriteLine('stop'); $Record.Process.StandardInput.Flush() } catch { }
        }
        if (-not $Record.Process.WaitForExit(15000)) {
            $Record.Forced = $true
            $Record.Process.Kill($true)
            if (-not $Record.Process.WaitForExit(10000)) { throw "Could not stop owned process $($Record.Name)" }
        }
    }
    Complete-EnvironmentProcess $Record
}

function Wait-EnvironmentMarker($Record, [string]$Pattern, [int]$TimeoutSec) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSec)
    do {
        if ($Record.Process.HasExited) { throw "$($Record.Name) exited before readiness; see private logs" }
        $text = (Read-EnvironmentText $Record.Stdout) + (Read-EnvironmentText $Record.Stderr)
        if ($text -match 'BindException|FAILED TO BIND TO PORT') { throw "$($Record.Name) listener bind failed" }
        if ($text -match $Pattern) { return }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$($Record.Name) readiness timed out after ${TimeoutSec}s"
}

function Invoke-EnvironmentNative($Context, [string]$Name, [string]$Executable, [string[]]$Arguments, [int]$TimeoutSec = 60) {
    $record = Start-EnvironmentProcess $Context $Name $Executable $Arguments (Join-Path $Context.Private "tools/$Name")
    if (-not $record.Process.WaitForExit($TimeoutSec * 1000)) {
        Stop-EnvironmentProcess $record
        throw "$Name timed out"
    }
    Complete-EnvironmentProcess $record
    if ($record.Process.ExitCode -ne 0) { throw "$Name failed (exit $($record.Process.ExitCode)); see private logs" }
    return Read-EnvironmentText $record.Stdout
}

function New-EnvironmentCertificates($Context) {
    $certDir = Join-Path $Context.Private 'certificates'
    [IO.Directory]::CreateDirectory($certDir) | Out-Null
    foreach ($authority in @('trusted', 'untrusted')) {
        $null = Invoke-EnvironmentNative $Context "openssl-$authority" 'openssl' @(
            'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '2',
            '-subj', "/CN=NovaLink-environment-$authority", '-addext', 'basicConstraints=critical,CA:TRUE',
            '-keyout', (Join-Path $certDir "$authority.key"), '-out', (Join-Path $certDir "$authority.pem"))
    }
    $null = Invoke-EnvironmentNative $Context 'openssl-server-request' 'openssl' @(
        'req', '-new', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-subj', '/CN=localhost',
        '-keyout', (Join-Path $certDir 'server.key'), '-out', (Join-Path $certDir 'server.csr'))
    $extensions = Join-Path $certDir 'server.ext'
    Write-EnvironmentText $extensions "subjectAltName=DNS:localhost,IP:127.0.0.1`nextendedKeyUsage=serverAuth`nbasicConstraints=critical,CA:FALSE`n"
    $null = Invoke-EnvironmentNative $Context 'openssl-server-sign' 'openssl' @(
        'x509', '-req', '-in', (Join-Path $certDir 'server.csr'), '-CA', (Join-Path $certDir 'trusted.pem'),
        '-CAkey', (Join-Path $certDir 'trusted.key'), '-CAcreateserial', '-days', '2', '-sha256',
        '-extfile', $extensions, '-out', (Join-Path $certDir 'server.pem'))
    return $certDir
}

function Resolve-EnvironmentArtifact([string]$RepoRoot, [string]$Pattern) {
    $files = @(Get-ChildItem -Path (Join-Path $RepoRoot $Pattern) -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(-plain|-sources|-javadoc)\.jar$' } | Sort-Object LastWriteTimeUtc -Descending)
    if ($files.Count -eq 0) { throw "Prebuilt fat jar missing: $Pattern; the environment runner never invokes Gradle" }
    return $files[0].FullName
}

function Assert-EnvironmentHash([string]$Path, [string]$Expected) {
    if (-not [IO.File]::Exists($Path)) { throw "Locked runtime is missing: $Path" }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Expected) { throw "Locked runtime hash mismatch: $Path" }
}

function Copy-EnvironmentBackendClasspath($Context) {
    if ($Context.BackendJar) { $entries = @($Context.BackendJar) }
    else { $entries = (Read-EnvironmentText $Context.ClassPathFile).Trim() -split [regex]::Escape([string][IO.Path]::PathSeparator) }
    if ($entries.Count -eq 0 -or -not $entries[0]) { throw 'Backend classpath is empty' }
    $copies = @()
    $index = 0
    foreach ($entry in $entries) {
        $source = $entry.Trim()
        if (-not [IO.Path]::IsPathRooted($source)) { $source = [IO.Path]::GetFullPath((Join-Path $Context.RepoRoot $source)) }
        if (-not [IO.File]::Exists($source)) { throw "Backend classpath needs prebuilt jar files on this OS: $source" }
        $target = Join-Path $Context.Private ("classpath/{0}-{1}" -f $index, [IO.Path]::GetFileName($source))
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
        Copy-Item -LiteralPath $source -Destination $target
        $copies += $target
        $index++
    }
    return $copies -join [IO.Path]::PathSeparator
}

function New-EnvironmentDatabase($Context, [string]$Engine, [switch]$Provision) {
    if ($Engine -eq 'memory') { return @{ Type = 'memory' } }
    $prefix = if ($Engine -eq 'mysql') { 'NOVALINK_ENV_MYSQL_' } else { 'NOVALINK_ENV_POSTGRES_' }
    if ($Provision) {
        $password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        $db = @{ Type = $Engine; Host = '127.0.0.1'; Port = 0; Database = 'novalink_environment'; Username = 'novalink_environment'; Password = $password }
        $name = "novalink-env-$Engine-$($Context.RunId.ToLowerInvariant())"
        $envFile = Join-Path $Context.Private "$Engine.container.env"
        if ($Engine -eq 'mysql') {
            Write-EnvironmentText $envFile "MYSQL_DATABASE=$($db.Database)`nMYSQL_USER=$($db.Username)`nMYSQL_PASSWORD=$password`nMYSQL_RANDOM_ROOT_PASSWORD=yes`n"
            $image = 'mysql:8.4'; $internalPort = 3306
        } else {
            Write-EnvironmentText $envFile "POSTGRES_DB=$($db.Database)`nPOSTGRES_USER=$($db.Username)`nPOSTGRES_PASSWORD=$password`n"
            $image = 'postgres:17'; $internalPort = 5432
        }
        $Context.Secrets.Add($password)
        $Context.Containers.Add($name)
        $owner = if ($Engine -eq 'mysql') { 'mysql:mysql' } else { 'postgres:postgres' }
        $bootstrap = @'
set -eu
cp /run/novalink-tls/server.key /tmp/novalink-server.key
chown __OWNER__ /tmp/novalink-server.key
chmod 600 /tmp/novalink-server.key
exec docker-entrypoint.sh "$@"
'@
        $bootstrap = $bootstrap.Replace('__OWNER__', $owner).Replace("`r`n", "`n")
        Write-EnvironmentText (Join-Path $Context.Certificates "bootstrap-$Engine.sh") $bootstrap
        $serverArguments = if ($Engine -eq 'mysql') {
            @('mysqld', '--ssl-ca=/run/novalink-tls/trusted.pem', '--ssl-cert=/run/novalink-tls/server.pem',
                '--ssl-key=/tmp/novalink-server.key', '--require-secure-transport=ON')
        } else {
            Write-EnvironmentText (Join-Path $Context.Certificates 'pg_hba.conf') "local all all trust`nhostssl all all 0.0.0.0/0 scram-sha-256`nhostssl all all ::/0 scram-sha-256`n"
            @('postgres', '-c', 'ssl=on', '-c', 'ssl_cert_file=/run/novalink-tls/server.pem',
                '-c', 'ssl_key_file=/tmp/novalink-server.key', '-c', 'hba_file=/run/novalink-tls/pg_hba.conf')
        }
        $containerArguments = @(
            'run', '--detach', '--rm', '--name', $name, '--env-file', $envFile, '--publish', "127.0.0.1::${internalPort}",
            '--mount', "type=bind,source=$($Context.Certificates),target=/run/novalink-tls,readonly", '--entrypoint', '/bin/sh',
            $image, "/run/novalink-tls/bootstrap-$Engine.sh") + $serverArguments
        $null = Invoke-EnvironmentNative $Context "docker-run-$Engine" 'docker' $containerArguments 180
        $mapping = Invoke-EnvironmentNative $Context "docker-port-$Engine" 'docker' @('port', $name, "$internalPort/tcp")
        if ($mapping.Trim() -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected database port mapping' }
        $db.Port = [int]$Matches[1]
        $deadline = [DateTime]::UtcNow.AddSeconds(180)
        do {
            try {
                if ($Engine -eq 'mysql') {
                    $null = Invoke-EnvironmentNative $Context "docker-ready-$([guid]::NewGuid().ToString('N'))" 'docker' @(
                        'exec', $name, 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --ssl-mode=REQUIRED --protocol=tcp -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SELECT 1"') 10
                } else {
                    $null = Invoke-EnvironmentNative $Context "docker-ready-$([guid]::NewGuid().ToString('N'))" 'docker' @(
                        'exec', $name, 'sh', '-c', 'PGSSLMODE=require PGPASSWORD="$POSTGRES_PASSWORD" psql -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT 1"') 10
                }
                return $db
            } catch { Start-Sleep -Seconds 2 }
        } while ([DateTime]::UtcNow -lt $deadline)
        throw "$Engine never accepted a SQL query"
    }
    $db = @{ Type = $Engine }
    foreach ($pair in @(@('Host', 'HOST'), @('Port', 'PORT'), @('Database', 'DATABASE'), @('Username', 'USERNAME'), @('Password', 'PASSWORD'))) {
        $value = [Environment]::GetEnvironmentVariable($prefix + $pair[1])
        if ([string]::IsNullOrWhiteSpace($value)) { throw "Required dedicated database setting: $prefix$($pair[1])" }
        $db[$pair[0]] = $value
    }
    $Context.Secrets.Add($db.Password)
    return $db
}

function Write-EnvironmentBackendConfig($Context, $Database) {
    $yaml = @"
server:
  bind-address: 127.0.0.1
  port: $($Context.BackendPort)
  websocket-port: $($Context.RestPort)
  secret-key: "$($Context.JwtSecret)"
  locale: en_US
  worker-threads: 2
  insecure-allow-plaintext: false
  tls:
    cert-chain-file: '$($Context.Certificates.Replace('\', '/'))/server.pem'
    private-key-file: '$($Context.Certificates.Replace('\', '/'))/server.key'
database:
  type: $($Database.Type)
"@
    if ($Database.Type -ne 'memory') {
        $sslMode = if ($Database.Type -eq 'mysql') { 'REQUIRED' } else { 'require' }
        $yaml += "`n  $($Database.Type):`n"
        foreach ($pair in @(@('host', 'Host'), @('port', 'Port'), @('database', 'Database'), @('username', 'Username'), @('password', 'Password'))) {
            $yaml += "    $($pair[0]): '$(([string]$Database[$pair[1]]).Replace("'", "''"))'`n"
        }
        $yaml += "    pool-size: 3`n    ssl-mode: $sslMode`n"
    }
    $yaml += @"

security:
  allowed-ips: [127.0.0.1]
panel-users: []
super-admins:
  - uuid: '00000000-0000-0000-0000-000000000005'
    username: EnvironmentAdmin
    password: '$($Context.AdminPassword)'
global_channels:
  global:
    display_name: Global
    permission: ''
    max_capacity: 100
clients:
  - username: ENV_Bukkit
    password: '$($Context.ClientPassword)'
    display_name: Environment Bukkit
  - username: ENV_Fabric
    password: '$($Context.ClientPassword)'
    display_name: Environment Fabric
features:
  filter-enabled: false
  cross-server-chat-enabled: true
  message-log-enabled: true
  message-log-retention-days: 1
filter:
  words: []
  patterns: []
debug: false
"@
    $path = Join-Path $Context.Private 'backend/novalink.yml'
    Write-EnvironmentText $path $yaml
    return $path
}

function Start-EnvironmentBackend($Context, [string]$Phase) {
    $arguments = @(
        '-Xmx512m', '-Dorg.jline.terminal.dumb=true', '-cp', $Context.BackendClasspath,
        'com.nova.link.NovaLinkMain', '--data-dir', (Split-Path -Parent $Context.BackendConfig))
    if (-not $IsWindows) { $arguments += '--daemon' }
    $record = Start-EnvironmentProcess $Context "backend-$Phase" $Context.Java $arguments (Join-Path $Context.Private "backend/$Phase")
    if (-not $IsWindows) { $record.StopMode = 'sigterm' }
    Wait-EnvironmentMarker $record '(?i)started successfully' 90
    return $record
}

function Start-EnvironmentGame($Context, [string]$Platform, [string]$Phase, [string]$Authority = 'trusted') {
    $dir = Join-Path $Context.Private "$Platform-$Phase"
    $fabric = $Platform -eq 'fabric'
    $port = if ($fabric) { $Context.FabricPort } else { $Context.BukkitPort }
    $identity = if ($fabric) { 'ENV_Fabric' } else { 'ENV_Bukkit' }
    $pluginDir = if ($fabric) { 'mods' } else { 'plugins' }
    $configPath = if ($fabric) { 'config/novachat.yml' } else { 'plugins/NovaChat/config.yml' }
    $plugin = if ($fabric) { $Context.FabricPlugin } else { $Context.BukkitPlugin }
    [IO.Directory]::CreateDirectory((Join-Path $dir $pluginDir)) | Out-Null
    Copy-Item -LiteralPath $plugin -Destination (Join-Path $dir "$pluginDir/novachat-environment.jar")
    if ($fabric) {
        Copy-Item -LiteralPath (Join-Path $Context.DistDir $Context.FabricRuntime.ApiFile) -Destination (Join-Path $dir 'mods/fabric-api.jar')
    }
    Write-EnvironmentText (Join-Path $dir $configPath) @"
config-version: 1
backend:
  host: 127.0.0.1
  port: $($Context.BackendPort)
  username: $identity
  password: '$($Context.ClientPassword)'
  reconnect-delay: 1
chat:
  replace_vanilla: true
  default_channel: global
  locale: en_US
  channel-prefixes: {}
format:
  channels:
    global: '[Global] {player}: {message}'
plugin-messaging:
  enabled: false
debug: true
"@
    Write-EnvironmentText (Join-Path $dir 'eula.txt') "eula=true`n"
    Write-EnvironmentText (Join-Path $dir 'server.properties') @"
server-port=$port
server-ip=127.0.0.1
online-mode=false
enforce-secure-profile=false
level-name=world
level-type=minecraft\:flat
gamemode=creative
difficulty=peaceful
spawn-protection=0
view-distance=3
simulation-distance=3
max-players=4
allow-flight=true
"@
    $jar = if ($fabric) { Join-Path $Context.DistDir $Context.FabricRuntime.LauncherFile } else { $Context.BukkitServer }
    $record = Start-EnvironmentProcess $Context "$Platform-$Phase" $Context.Java @(
        '-Xmx1024m', '-Dnovachat.backend.tls.enabled=true',
        "-Dnovachat.backend.tls.caCertFile=$(Join-Path $Context.Certificates "$Authority.pem")",
        '-jar', $jar, 'nogui') $dir
    Wait-EnvironmentMarker $record '(?i)Done \(' $Context.ServerReadySec
    return $record
}

function Invoke-EnvironmentRest($Context, [string]$Path, [string]$Jwt = '', $Body = $null) {
    $name = [guid]::NewGuid().ToString('N')
    $requestFile = Join-Path $Context.Private "requests/$name.json"
    $headersFile = Join-Path $Context.Private "requests/$name.headers"
    $arguments = @('--silent', '--show-error', '--fail', '--noproxy', '*', '--max-time', '15',
        '--cacert', (Join-Path $Context.Certificates 'trusted.pem'), "https://127.0.0.1:$($Context.RestPort)$Path")
    if ($Jwt) {
        Write-EnvironmentText $headersFile "Authorization: Bearer $Jwt`n"
        $arguments += @('--header', "@$headersFile")
    }
    if ($null -ne $Body) {
        Write-EnvironmentText $requestFile ($Body | ConvertTo-Json -Depth 5 -Compress)
        $arguments += @('--header', 'Content-Type: application/json', '--data-binary', "@$requestFile")
    }
    $text = Invoke-EnvironmentNative $Context "curl-$name" 'curl' $arguments
    return $text | ConvertFrom-Json
}

function Connect-EnvironmentRest($Context) {
    $response = Invoke-EnvironmentRest $Context '/api/auth/login' -Body @{ username = 'EnvironmentAdmin'; password = $Context.AdminPassword }
    if (-not $response.token) { throw 'Panel login did not return a token' }
    $Context.Secrets.Add([string]$response.token)
    return [string]$response.token
}

function Get-EnvironmentMessage($Context, [string]$Jwt, [string]$Token) {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $response = Invoke-EnvironmentRest $Context "/api/messages?q=$([uri]::EscapeDataString($Token))&size=50" $Jwt
        $items = @($response.items | Where-Object { $_.content -ceq $Token -and $_.senderName -ceq 'EnvBukkit' -and $_.clientId -ceq 'ENV_Bukkit' })
        if ($items.Count -gt 1) { throw "Expected one game message row for $Token, found $($items.Count)" }
        if ($items.Count -eq 1) { return $items[0] }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Invoke-EnvironmentBotPair($Context, [string]$Phase, [switch]$RejectFabric) {
    $dir = Join-Path $Context.Private "bots/$Phase"
    $handoff = Join-Path $dir 'handoff'
    [IO.Directory]::CreateDirectory($handoff) | Out-Null
    $token = "$($Context.RunId)-$Phase-bukkit"
    $reply = "$($Context.RunId)-$Phase-fabric"
    $senderSteps = @(
        @{ action = 'wait'; ms = 3000 },
        @{ action = 'cmd'; text = '/nc join global' },
        @{ action = 'wait'; ms = 1000 },
        @{ action = 'arm'; token = $reply },
        @{ action = 'handoffGet'; key = 'fabric-ready'; timeoutMs = 45000 },
        @{ action = 'chat'; text = $token }
    )
    $receiverSteps = @(
        @{ action = 'wait'; ms = 3000 },
        $(if (-not $RejectFabric) { @{ action = 'cmd'; text = '/nc join global' } } else { @{ action = 'wait'; ms = 1000 } }),
        @{ action = 'wait'; ms = 1000 },
        @{ action = 'arm'; token = $token },
        @{ action = 'handoffSet'; key = 'fabric-ready'; pattern = '^spawned$' }
    )
    if ($RejectFabric) {
        $senderSteps += @(
            @{ action = 'handoffSet'; key = 'bukkit-sent'; pattern = '^spawned$' },
            @{ action = 'handoffGet'; key = 'fabric-observed'; timeoutMs = 45000 }
        )
        $receiverSteps += @(
            @{ id = 'rejected-token'; action = 'expectAbsent'; token = $token },
            @{ action = 'handoffGet'; key = 'bukkit-sent'; timeoutMs = 45000 },
            @{ action = 'wait'; ms = 12000 },
            @{ action = 'handoffSet'; key = 'fabric-observed'; pattern = '^spawned$' }
        )
    } else {
        $senderSteps += @{ id = 'from-fabric'; action = 'expect'; token = $reply; timeoutMs = 45000 }
        $receiverSteps += @(
            @{ id = 'to-fabric'; action = 'expect'; token = $token; timeoutMs = 45000 },
            @{ action = 'chat'; text = $reply }, @{ action = 'wait'; ms = 2000 }
        )
    }
    $senderSteps += @{ action = 'quit' }
    $receiverSteps += @{ action = 'quit' }
    $records = @()
    foreach ($role in @('bukkit', 'fabric')) {
        $plan = @{
            runId = "$($Context.RunId)-$Phase"; role = $role; kind = 'java'; platform = $role
            username = $(if ($role -eq 'bukkit') { 'EnvBukkit' } else { 'EnvFabric' })
            host = '127.0.0.1'; port = $(if ($role -eq 'bukkit') { $Context.BukkitPort } else { $Context.FabricPort })
            mcVersion = $(if ($role -eq 'bukkit') { $Context.BukkitMcVersion } else { $Context.FabricRuntime.McVersion })
            resultsFile = Join-Path $dir "$role.results.json"; handoffDir = $handoff; timeoutMs = 120000
            steps = $(if ($role -eq 'bukkit') { $senderSteps } else { $receiverSteps })
        }
        $path = Join-Path $dir "$role.plan.json"
        Write-EnvironmentText $path ($plan | ConvertTo-Json -Depth 10)
        $records += Start-EnvironmentProcess $Context "bot-$role" 'node' @((Join-Path $Context.RepoRoot 'test/joint/bots/joint-bot.js'), '--plan', $path) $dir
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(130)
    while (@($records | Where-Object { -not $_.Process.HasExited }).Count -gt 0) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Real game bots timed out' }
        Start-Sleep -Milliseconds 250
    }
    $results = @{}
    for ($i = 0; $i -lt $records.Count; $i++) {
        Complete-EnvironmentProcess $records[$i]
        if ($records[$i].Process.ExitCode -ne 0) { throw 'Real game bot failed; see private logs' }
        $role = @('bukkit', 'fabric')[$i]
        $result = (Read-EnvironmentText (Join-Path $dir "$role.results.json")) | ConvertFrom-Json
        $expectedName = if ($role -eq 'bukkit') { 'EnvBukkit' } else { 'EnvFabric' }
        if (-not $result -or $result.runId -cne "$($Context.RunId)-$Phase" -or $result.role -cne $role -or
            $result.username -cne $expectedName -or $result.timedOut -or $result.error -or
            @($result.received | Where-Object { $_.kind -eq 'spawn' -and $_.raw -eq 'spawned' }).Count -eq 0 -or
            @(@($result.assertions) + @($result.absentChecks) | Where-Object { $null -ne $_ -and $_.pass -ne $true }).Count -gt 0) {
            throw "Invalid or failed real bot recording for $role"
        }
        $results[$role] = $result
    }
    return @{ Bukkit = $results.bukkit; Fabric = $results.fabric; Token = $token; Reply = $reply }
}

function Test-EnvironmentDelivery($Result, [string]$Token) {
    return @($Result.received | Where-Object { $_.kind -eq 'messagestr' -and ([string]$_.text).Contains($Token) }).Count -gt 0
}

function Publish-EnvironmentEvidence($Context) {
    foreach ($file in Get-ChildItem -LiteralPath $Context.Private -Recurse -File) {
        $relative = [IO.Path]::GetRelativePath($Context.Private, $file.FullName).Replace('\', '/')
        if ($relative -notmatch '^(bots/[^/]+/[^/]+\.(results|plan)\.json|(?:backend/[^/]+|bukkit-[^/]+|fabric-[^/]+)/[^/]+\.(stdout|stderr)\.log|tools/[^/]+/[^/]+\.stderr\.log)$') { continue }
        $body = Read-EnvironmentText $file.FullName
        foreach ($secret in $Context.Secrets) { if ($secret) { $body = $body.Replace($secret, '[REDACTED]') } }
        $body = [regex]::Replace($body, '(?im)(password|secret-key|authorization|bearer)(\s*[:=]\s*|\s+)[^\r\n]+', '$1=[REDACTED]')
        $body = [regex]::Replace($body, '(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----', '[PRIVATE KEY REMOVED]')
        Write-EnvironmentText (Join-Path $Context.Public $relative) $body
    }
}

function Invoke-EnvironmentScenarios {
    [CmdletBinding()]
    param(
        [string[]]$Cases = @('tls-trusted', 'tls-untrusted', 'mysql', 'postgresql'),
        [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
        [string]$DistDir = '', [string]$OutputRoot = '', [string]$ClassPathFile = '', [string]$BackendJar = '',
        [string]$Jdk21 = $env:JAVA_HOME, [hashtable]$FabricRuntime = @{},
        [int]$BackendPort = 27820, [int]$RestPort = 27830, [int]$BukkitPort = 27800, [int]$FabricPort = 27801,
        [int]$ServerReadySec = 480, [switch]$ProvisionDatabases
    )
    $spec = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'cases.psd1')
    foreach ($case in $Cases) { if ($case -notin $spec.Cases) { throw "Unknown environment case: $case" } }
    if ($Cases.Count -eq 0) { throw 'Select at least one environment case' }
    if (@($Cases | Select-Object -Unique).Count -ne $Cases.Count) { throw 'Environment cases must be unique' }
    $RepoRoot = [IO.Path]::GetFullPath($RepoRoot)
    if (-not $DistDir) { $DistDir = Join-Path $RepoRoot '.e2e-artifacts/dist' }
    if (-not $OutputRoot) { $OutputRoot = Join-Path $RepoRoot '.e2e-artifacts/environment' }
    if ($BackendJar -and $ClassPathFile) { throw 'Select either BackendJar or ClassPathFile' }
    if (-not $BackendJar -and -not $ClassPathFile) {
        $BackendJar = Resolve-EnvironmentArtifact $RepoRoot 'StarLink/core/build/libs/*-all.jar'
    }
    foreach ($pathName in @('DistDir', 'OutputRoot', 'ClassPathFile', 'BackendJar')) {
        $value = Get-Variable -Name $pathName -ValueOnly
        if ($value) {
            if (-not [IO.Path]::IsPathRooted($value)) { $value = Join-Path $RepoRoot $value }
            Set-Variable -Name $pathName -Value ([IO.Path]::GetFullPath($value))
        }
    }
    if ($FabricRuntime.Count -eq 0) { $FabricRuntime = $spec.FabricRuntime }
    if ([string]::IsNullOrWhiteSpace($Jdk21)) { throw 'Set Jdk21 or JAVA_HOME to an installed JDK 21' }
    $Jdk21 = [IO.Path]::GetFullPath($Jdk21)
    $java = Join-Path $Jdk21 $(if ($IsWindows) { 'bin/java.exe' } else { 'bin/java' })
    if (-not [IO.File]::Exists($java)) { throw 'Jdk21 must point to an installed JDK 21' }
    $javaVersion = & $java -version 2>&1
    if ($LASTEXITCODE -ne 0 -or ($javaVersion -join "`n") -notmatch 'version "21[.]') { throw 'Jdk21 must select Java 21' }
    foreach ($tool in @('node', 'openssl', 'curl')) { $null = Get-Command $tool -CommandType Application -ErrorAction Stop }
    $node = Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $nodeVersion = & $node.Source --version
    if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v24\.') { throw 'The environment runner requires Node 24' }
    if ($ProvisionDatabases -and @($Cases | Where-Object { $_ -in @('mysql', 'postgresql') }).Count -gt 0) {
        $null = Get-Command docker -CommandType Application -ErrorAction Stop
    }
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot 'test/bot/node_modules/mineflayer'))) { throw 'Install locked test/bot dependencies before running' }
    . (Join-Path $RepoRoot 'test/versions.lock.ps1')
    $bukkit = Get-LockedServer 'purpur'
    if (-not $bukkit) { throw 'Purpur lock entry is missing' }
    Assert-EnvironmentHash (Join-Path $DistDir $bukkit.File) $bukkit.Sha256
    Assert-EnvironmentHash (Join-Path $DistDir $FabricRuntime.LauncherFile) $FabricRuntime.LauncherSha256
    Assert-EnvironmentHash (Join-Path $DistDir $FabricRuntime.ApiFile) $FabricRuntime.ApiSha256
    $bukkitPlugin = Resolve-EnvironmentArtifact $RepoRoot 'NovaChat/Plugin/bukkit/build/libs/bukkit-*.jar'
    $fabricPlugin = Resolve-EnvironmentArtifact $RepoRoot 'NovaChat/MOD/fabric/build/libs/nova-chat-fabric-e2e-*.jar'
    $ports = @($BackendPort, $RestPort, $BukkitPort, $FabricPort)
    if (@($ports | Select-Object -Unique).Count -ne 4 -or @($ports | Where-Object { $_ -lt 1 -or $_ -gt 65535 }).Count -gt 0) {
        throw 'Backend, REST, Bukkit and Fabric ports must be distinct and in 1..65535'
    }
    $all = [Collections.Generic.List[object]]::new()
    foreach ($case in $Cases) {
        $runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
        $root = [IO.Path]::GetFullPath((Join-Path $OutputRoot "$runId-$case"))
        $context = @{
            RunId = $runId; RepoRoot = $RepoRoot; DistDir = [IO.Path]::GetFullPath($DistDir)
            Private = Join-Path $root 'private'; Public = Join-Path $root 'public'
            Processes = [Collections.Generic.List[object]]::new(); Containers = [Collections.Generic.List[string]]::new()
            Secrets = [Collections.Generic.List[string]]::new(); Java = $java; ClassPathFile = $ClassPathFile; BackendJar = $BackendJar
            BackendPort = $BackendPort; RestPort = $RestPort; BukkitPort = $BukkitPort; FabricPort = $FabricPort
            ServerReadySec = $ServerReadySec; FabricRuntime = $FabricRuntime
            BukkitServer = Join-Path $DistDir $bukkit.File; BukkitMcVersion = $bukkit.BotMcVersion
            BukkitPlugin = Join-Path $root 'private/artifacts/bukkit.jar'
            FabricPlugin = Join-Path $root 'private/artifacts/fabric.jar'
        }
        [IO.Directory]::CreateDirectory($context.Private) | Out-Null
        if (-not $IsWindows) { [IO.File]::SetUnixFileMode($context.Private, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute) }
        foreach ($key in @('AdminPassword', 'ClientPassword', 'JwtSecret')) {
            $context[$key] = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
            $context.Secrets.Add($context[$key])
        }
        $assertions = [Collections.Generic.List[object]]::new()
        $backend = $null
        try {
            [IO.Directory]::CreateDirectory((Join-Path $context.Private 'artifacts')) | Out-Null
            Copy-Item -LiteralPath $bukkitPlugin -Destination $context.BukkitPlugin
            Copy-Item -LiteralPath $fabricPlugin -Destination $context.FabricPlugin
            $context.BackendClasspath = Copy-EnvironmentBackendClasspath $context
            $context.Certificates = New-EnvironmentCertificates $context
            $engine = if ($case -in @('mysql', 'postgresql')) { $case } else { 'memory' }
            $db = New-EnvironmentDatabase $context $engine -Provision:$ProvisionDatabases
            $context.BackendConfig = Write-EnvironmentBackendConfig $context $db
            $backend = Start-EnvironmentBackend $context 'initial'
            $jwt = Connect-EnvironmentRest $context
            $null = Start-EnvironmentGame $context 'bukkit' 'trusted'
            $fabric = Start-EnvironmentGame $context 'fabric' 'trusted'
            Wait-EnvironmentMarker $backend 'Client authenticated:\s*ENV_Fabric\s+from' 45
            Wait-EnvironmentMarker $backend 'Client authenticated:\s*ENV_Bukkit\s+from' 45
            $control = Invoke-EnvironmentBotPair $context 'control'
            $toFabric = Test-EnvironmentDelivery $control.Fabric $control.Token
            $fromFabric = Test-EnvironmentDelivery $control.Bukkit $control.Reply
            if ($case -eq 'tls-trusted') {
                $assertions.Add((New-EnvironmentAssertion 'ENV-TLS-TRUSTED-AUTH' $true 'Both actual game plugins authenticated against the TLS backend'))
                $assertions.Add((New-EnvironmentAssertion 'ENV-TLS-TRUSTED-TO-FABRIC' $toFabric 'Fabric bot recording contains the Bukkit game message' $control.Token))
                $assertions.Add((New-EnvironmentAssertion 'ENV-TLS-TRUSTED-FROM-FABRIC' $fromFabric 'Bukkit bot recording contains the Fabric game reply' $control.Reply))
            } elseif ($case -eq 'tls-untrusted') {
                $assertions.Add((New-EnvironmentAssertion 'ENV-TLS-UNTRUSTED-CONTROL' ($toFabric -and $fromFabric) 'Trusted CA control exchanged both game messages'))
                Stop-EnvironmentProcess $fabric
                if ($fabric.Forced) { throw 'Trusted Fabric did not stop cleanly before the certificate rejection control' }
                $outOffset = (Read-EnvironmentText $backend.Stdout).Length
                $errOffset = (Read-EnvironmentText $backend.Stderr).Length
                $rejected = Start-EnvironmentGame $context 'fabric' 'rejected' 'untrusted'
                $negative = Invoke-EnvironmentBotPair $context 'rejected' -RejectFabric
                $senderRow = Get-EnvironmentMessage $context $jwt $negative.Token
                $failureText = (Read-EnvironmentText $rejected.Stdout) + (Read-EnvironmentText $rejected.Stderr)
                $certificateFailure = $failureText -match 'PKIX path building failed|unable to find valid certification path|certificate_unknown|CERTIFICATE_VERIFY_FAILED|unable to get local issuer certificate'
                $backendOut = Read-EnvironmentText $backend.Stdout
                $backendErr = Read-EnvironmentText $backend.Stderr
                $newBackendText = $backendOut.Substring([Math]::Min($outOffset, $backendOut.Length)) +
                    $backendErr.Substring([Math]::Min($errOffset, $backendErr.Length))
                $newAuth = $newBackendText -match 'Client authenticated:\s*ENV_Fabric\s+from'
                $absent = -not (Test-EnvironmentDelivery $negative.Fabric $negative.Token)
                $observed = $negative.Fabric.handoff.'bukkit-sent' -eq 'spawned' -and $negative.Bukkit.handoff.'fabric-observed' -eq 'spawned'
                $assertions.Add((New-EnvironmentAssertion 'ENV-TLS-UNTRUSTED-CERTIFICATE' $certificateFailure 'Fabric log must contain an explicit certificate-chain validation error'))
                $assertions.Add((New-EnvironmentAssertion 'ENV-TLS-UNTRUSTED-NO-AUTH' (-not $newAuth) 'No new Fabric authentication after restart with unrelated CA'))
                $assertions.Add((New-EnvironmentAssertion 'ENV-TLS-UNTRUSTED-NO-DELIVERY' ($absent -and $observed -and $null -ne $senderRow) 'Receiver observed a full 12-second window after the actual game sender published its send; game row persisted' $negative.Token))
            } else {
                $prefix = if ($case -eq 'mysql') { 'ENV-MYSQL' } else { 'ENV-POSTGRES' }
                $assertions.Add((New-EnvironmentAssertion "$prefix-GAME-DELIVERY" ($toFabric -and $fromFabric) 'Actual Bukkit/Fabric bots exchanged game messages before backend restart'))
                $before = Get-EnvironmentMessage $context $jwt $control.Token
                $assertions.Add((New-EnvironmentAssertion "$prefix-STORED" ($null -ne $before -and [long]$before.id -gt 0) 'Read actual game sender row via authenticated HTTPS message history' $control.Token))
                if ($null -eq $before) { throw 'Actual game message was not stored before restart' }
                Write-EnvironmentText (Join-Path $context.Public 'row-before.json') ($before | ConvertTo-Json -Depth 5)
                $oldBackend = $backend
                $oldStart = $backend.Process.StartTime.ToUniversalTime()
                Stop-EnvironmentProcess $backend
                if ($oldBackend.Forced) { throw 'Backend shutdown required a forced kill before the SQL restart check' }
                $backend = Start-EnvironmentBackend $context 'restarted'
                $jwt = Connect-EnvironmentRest $context
                $newProcess = $oldBackend.Process.HasExited -and $backend.Process.StartTime.ToUniversalTime() -gt $oldStart
                $assertions.Add((New-EnvironmentAssertion "$prefix-RESTARTED" $newProcess 'Old backend exited after graceful shutdown; a new process serves the unchanged SQL database'))
                $after = Get-EnvironmentMessage $context $jwt $control.Token
                $same = $null -ne $after
                foreach ($field in @('id', 'channelId', 'senderId', 'senderName', 'clientId', 'content', 'timestamp')) {
                    $same = $same -and ($null -ne $before.$field) -and ([string]$before.$field -ceq [string]$after.$field)
                }
                $assertions.Add((New-EnvironmentAssertion "$prefix-SAME-ROW" $same 'Matched id, channelId, senderId, senderName, clientId, content and timestamp from the new backend' $control.Token))
                Write-EnvironmentText (Join-Path $context.Public 'row-after.json') ($after | ConvertTo-Json -Depth 5)
                Wait-EnvironmentMarker $backend 'Client authenticated:\s*ENV_Fabric\s+from' 60
                Wait-EnvironmentMarker $backend 'Client authenticated:\s*ENV_Bukkit\s+from' 60
                $post = Invoke-EnvironmentBotPair $context 'restarted'
                $reconnected = (Test-EnvironmentDelivery $post.Fabric $post.Token) -and (Test-EnvironmentDelivery $post.Bukkit $post.Reply)
                $assertions.Add((New-EnvironmentAssertion "$prefix-POST-RESTART-GAME" $reconnected 'Both actual game plugins reauthenticated and exchanged new game messages after restart'))
            }
        } catch {
            $assertions.Add((New-EnvironmentAssertion "ENV-$($case.ToUpperInvariant())-EXECUTION" $false $_.Exception.Message))
        } finally {
            for ($i = $context.Processes.Count - 1; $i -ge 0; $i--) {
                try { Stop-EnvironmentProcess $context.Processes[$i] } catch {
                    $assertions.Add((New-EnvironmentAssertion "ENV-$($case.ToUpperInvariant())-CLEANUP" $false $_.Exception.Message))
                }
            }
            foreach ($container in $context.Containers) {
                try { $null = Invoke-EnvironmentNative $context "docker-stop-$container" 'docker' @('stop', '--time', '10', $container) 30 }
                catch { $assertions.Add((New-EnvironmentAssertion "ENV-$($case.ToUpperInvariant())-DATABASE-CLEANUP" $false $_.Exception.Message)) }
            }
            Publish-EnvironmentEvidence $context
        }
        $completed = @(Complete-EnvironmentAssertions $assertions.ToArray() $spec.RequiredAssertions[$case])
        $summary = @{ schemaVersion = 1; case = $case; runId = $runId; verification = 'real-game'; assertions = $completed
            status = $(if (@($completed | Where-Object { -not $_.pass }).Count -eq 0) { 'PASS' } else { 'FAIL' }) }
        Write-EnvironmentText (Join-Path $context.Public 'environment-summary.json') ($summary | ConvertTo-Json -Depth 10)
        foreach ($assertion in $completed) { $all.Add($assertion) }
    }
    return $all.ToArray()
}
