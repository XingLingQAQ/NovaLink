[CmdletBinding()]
param(
    [string]$Jdk21 = $env:JAVA_HOME_21_X64,
    [string]$BuildJdk = $env:JAVA_HOME,
    [string]$RunRoot = '',
    [string]$ArtifactsFile = '',
    [switch]$SkipBuild,
    [ValidateRange(1024, 65535)][int]$PortA = 19362,
    [ValidateRange(1024, 65535)][int]$PortB = 19363,
    [ValidateRange(1024, 65535)][int]$RconA = 26362,
    [ValidateRange(1024, 65535)][int]$RconB = 26363,
    [ValidateRange(1024, 65535)][int]$BackendPort = 28962,
    [ValidateRange(1024, 65535)][int]$BackendWsPort = 28963,
    [ValidateRange(15, 180)][int]$BotTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repo 'test/versions.lock.ps1')
. (Join-Path $repo 'test/bin/e2e-assertions.ps1')
. (Join-Path $PSScriptRoot 'native-runtime.ps1')
$pin = Get-LockedServer -Name 'powernukkitx'
if ($pin.Build -ne '3.0.6' -or $pin.BotMcVersion -ne '1.26.60') { throw 'This native transfer entry requires the PNX 3.0.6 pin.' }
if (-not $Jdk21) { throw 'Pass -Jdk21 <JDK 21 home>.' }
if (-not $BuildJdk) { $BuildJdk = $Jdk21 }
$java = Join-Path $Jdk21 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw "JDK 21 java is missing: $java" }
$runId = [guid]::NewGuid().ToString('N')
if (-not $RunRoot) { $RunRoot = Join-Path $repo ".e2e-artifacts/bedrock-transfer/$runId" }
$RunRoot = [IO.Path]::GetFullPath($RunRoot)
if (Test-Path -LiteralPath $RunRoot) { throw "Use a new run directory: $RunRoot" }
$null = New-Item -ItemType Directory -Path $RunRoot
$dist = Join-Path $repo '.e2e-artifacts/dist'
$serverJar = Join-Path $dist $pin.File
$utf8 = New-Object System.Text.UTF8Encoding $false
$children = New-Object System.Collections.Generic.List[System.Diagnostics.Process]
$pnxRuntimes = @{}
$handledCommands = New-Object 'System.Collections.Generic.HashSet[string]'
$savedJava = $env:JAVA_HOME
$savedSecret = $env:BT_BACKEND_SECRET
$savedRcon = $env:BT_RCON_PASSWORD
$publicSecrets = @($savedSecret, $savedRcon)
$mutex = New-Object System.Threading.Mutex($false, 'Local\NovaLink.NativeBedrockTransfer')
$ownsMutex = $false
$failure = $null

function Write-Utf8([string]$Path, [string]$Body) {
    $null = New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force
    [IO.File]::WriteAllText($Path, $Body, $utf8)
}

function Wait-Ready([System.Diagnostics.Process]$Process, [string]$Log, [string]$Pattern) {
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        if ($Process.HasExited) { throw "Process $($Process.Id) exited before ready; inspect $Log" }
        if ((Test-Path -LiteralPath $Log) -and (Select-String -LiteralPath $Log -Pattern $Pattern -Quiet)) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "Ready timeout: $Log"
}

function Assert-PortsFree {
    $ports = @($PortA, $PortB, $RconA, $RconB, $BackendPort, $BackendWsPort)
    if (@($ports | Select-Object -Unique).Count -ne 6) { throw 'All six game/RCON/backend ports must differ.' }
    $sockets = New-Object System.Collections.Generic.List[System.IDisposable]
    try {
        foreach ($port in $ports) {
            $tcp = New-Object System.Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $port)
            $tcp.Start()
            $sockets.Add($tcp.Server)
            $udp = New-Object System.Net.Sockets.UdpClient($port)
            $sockets.Add($udp)
        }
    } finally {
        foreach ($socket in $sockets) { $socket.Dispose() }
    }
}

try {
    try { $ownsMutex = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { throw 'Another native Bedrock transfer run owns this runner lock.' }
    Assert-PortsFree
    if (-not (Test-BotDepsCurrent -BotDir (Join-Path $repo 'test/bot'))) { throw 'Install locked dependencies first: npm ci --prefix test/bot --no-audit --no-fund' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'test/bin/fetch-server.ps1') -Name powernukkitx -DistDir $dist
    if ($LASTEXITCODE -ne 0) { throw 'Pinned PNX download/hash verification failed.' }
    if ((Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash -ine $pin.Sha256) { throw 'PNX server SHA-256 differs from versions.lock.ps1.' }
    if (-not $SkipBuild) {
        $env:JAVA_HOME = $BuildJdk
        $buildRoot = Join-Path $RunRoot 'build'
        Push-Location $repo
        try {
            & (Join-Path $repo 'gradlew.bat') ':StarLink:core:writeBedrockTransferArtifacts' `
                '--init-script' (Join-Path $repo 'test/bin/bedrock-transfer.init.gradle') `
                "-PbedrockTransferBuildRoot=$buildRoot" '--configure-on-demand' '--no-daemon' '--max-workers=2' `
                '-x' 'test' '--console=plain' *> (Join-Path $RunRoot 'build.log')
            if ($LASTEXITCODE -ne 0) { throw "Isolated transfer build failed; inspect $RunRoot/build.log" }
        } finally { Pop-Location }
        $ArtifactsFile = Join-Path $buildRoot 'artifacts.json'
    }
    if (-not $ArtifactsFile) { throw '-SkipBuild requires -ArtifactsFile <artifacts.json>.' }
    $artifacts = Get-Content -LiteralPath $ArtifactsFile -Raw | ConvertFrom-Json
    foreach ($file in @($artifacts.pluginJar, $artifacts.fixtureJar) + ([string]$artifacts.classpath).Split([IO.Path]::PathSeparator)) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Build artifact is missing: $file" }
    }
    $secret = [guid]::NewGuid().ToString('N')
    $password = [guid]::NewGuid().ToString('N')
    $env:BT_BACKEND_SECRET = $secret
    $env:BT_RCON_PASSWORD = $password
    $publicSecrets += @($secret, $password)
    $nodes = [ordered]@{
        a = @{ id = 'a'; host = '127.0.0.1'; port = $PortA; rconPort = $RconA; rconEnabled = $false; clientId = "BT_A_$runId" }
        b = @{ id = 'b'; host = '127.0.0.1'; port = $PortB; rconPort = $RconB; rconEnabled = $false; clientId = "BT_B_$runId" }
    }
    $controlDirectory = Join-Path $RunRoot 'console'
    $null = New-Item -ItemType Directory -Path $controlDirectory
    $names = @{ traveler = 'BTTraveler'; observerA = 'BTWitnessA'; observerB = 'BTWitnessB' }
    $plan = [ordered]@{
        runId = $runId; kind = 'pnx-native-transfer'; serverVersion = $pin.Build
        minecraftVersion = $pin.BotMcVersion; serverJar = $serverJar; serverSha256 = $pin.Sha256
        transport = 'native-pnx-nethernet-http'; backendUrl = "http://127.0.0.1:$BackendWsPort"
        controlTransport = 'held-stdin'; controlDirectory = $controlDirectory
        nodes = $nodes; names = $names; timeoutMs = $BotTimeoutSeconds * 1000
        resourcePlan = @{ pnxServers = 2; novaLinkBackends = 1; proxies = 0; javaHeapMbPerPnx = 512 }
        steps = @('initial spawn/probe/backend membership/cross-server send+receive',
            'console Player.transfer A -> B; exact raw TransferPacket; new start_game+spawn',
            'B-only probe; same UUID now owned by client B; A no longer has player; cross-server send+receive',
            'console Player.transfer B -> A; exact raw TransferPacket; new start_game+spawn',
            'A-only probe; same UUID now owned by client A; B no longer has player; cross-server send+receive')
    }
    Write-Utf8 (Join-Path $RunRoot 'plan.json') ($plan | ConvertTo-Json -Depth 12)
    $backendDir = Join-Path $RunRoot 'backend'
    $template = Get-Content -LiteralPath (Join-Path $repo 'test/conf/novalink.template.yml') -Raw
    $template = $template.Replace('debug: true', 'debug: false')
    $template = "features:`n  filter-enabled: false`n`n" + $template
    $template = $template.Replace('{{NOVA_PORT}}', [string]$BackendPort).Replace('{{NOVA_WS_PORT}}', [string]$BackendWsPort).Replace('{{SECRET_KEY}}', $secret).Replace('{{CLIENT_PASSWORD}}', $password)
    $template = $template.Replace('E2E_Client', $nodes.a.clientId)
    $template += "`n  - username: '$($nodes.b.clientId)'`n    password: '$password'`n    display_name: 'BT_B'`n"
    $backendConfig = Join-Path $backendDir 'novalink.yml'
    Write-Utf8 $backendConfig $template
    $backendOut = Join-Path $backendDir 'stdout.log'
    $backend = Start-Process -FilePath $java -ArgumentList @('-Xms128M', '-Xmx512M', '-cp', ('"' + $artifacts.classpath + '"'),
        'com.nova.link.NovaLinkMain', ('"' + $backendConfig + '"'), '--daemon') -WorkingDirectory $backendDir `
        -RedirectStandardOutput $backendOut -RedirectStandardError (Join-Path $backendDir 'stderr.log') -WindowStyle Hidden -PassThru
    $children.Add($backend)
    Wait-Ready $backend $backendOut 'started successfully'
    foreach ($id in @('a', 'b')) {
        $node = $nodes[$id]
        $peer = if ($id -eq 'a') { $nodes.b } else { $nodes.a }
        $runs = Join-Path $RunRoot $id
        $pnxDir = Join-Path $runs 'pnx'
        $plugins = Join-Path $pnxDir 'plugins'
        $null = New-Item -ItemType Directory -Path $plugins -Force
        Copy-Item -LiteralPath $artifacts.pluginJar -Destination $plugins
        Copy-Item -LiteralPath $artifacts.fixtureJar -Destination $plugins
        Write-Utf8 (Join-Path $plugins 'BedrockTransferFixture/transfer.properties') "node=$id`npeer-host=127.0.0.1`npeer-port=$($peer.port)`n"
        Write-Utf8 (Join-Path $plugins 'NovaChat/config.yml') @"
config-version: 1
backend:
  host: '127.0.0.1'
  port: $BackendPort
  username: '$($node.clientId)'
  password: '$password'
  reconnect-delay: 3
chat:
  replace_vanilla: true
  default_channel: 'global'
  locale: 'en_US'
  channel-prefixes: {}
format:
  prefix: '[NovaChat] '
  error: 'Error: {message}'
  success: '{message}'
  default: '[{channel_name}] {player}: {message}'
  channels: {}
world-routing:
  enabled: false
  mappings: {}
debug: false
"@
        $launchSpecPath = Join-Path $runs 'launch-spec.json'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'test/bin/multiplatform/start-server.ps1') `
            -Platform pnx -RepoRoot $repo -RunsDir $runs -DistDir $dist -JdkHome $Jdk21 `
            -McPort $node.port -MemoryMb 512 -PrepareOnly -LaunchSpec $launchSpecPath `
            *> (Join-Path $runs 'launcher.log')
        if ($LASTEXITCODE -ne 0) { throw "PNX $id preparation failed; inspect $runs/launcher.log" }
        $launch = Get-Content -LiteralPath $launchSpecPath -Raw | ConvertFrom-Json
        if ($launch.schemaVersion -ne 1 -or $launch.platform -cne 'pnx' -or
            $launch.serverSha256 -ine $pin.Sha256 -or $launch.workingDirectory -ine $pnxDir) {
            throw "PNX $id launch spec does not match this run."
        }
        Write-Utf8 (Join-Path $launch.workingDirectory 'pnx.yml') (Get-PnxLoopbackConfig -Port $node.port -Node $id)
        $arguments = (@($launch.arguments) | ForEach-Object { '"' + [string]$_ + '"' }) -join ' '
        $runtime = Start-HeldConsoleProcess -Executable $launch.java -Arguments $arguments -Directory $launch.workingDirectory `
            -Stdout $launch.stdout -Stderr $launch.stderr
        $pnxRuntimes[$id] = $runtime
        $children.Add($runtime.Process)
        Write-Utf8 $launch.pidFile ([string]$runtime.Process.Id)
        $ready = Wait-PnxReady -Runtime $runtime -LogPath $launch.stdout -Port $node.port
        Write-Utf8 (Join-Path $runs 'readiness.json') ($ready | ConvertTo-Json)
    }
    $bot = Start-Process -FilePath (Get-Command node.exe -ErrorAction Stop).Source -ArgumentList @(
        ('"' + (Join-Path $PSScriptRoot 'run-bot.js') + '"'), ('"' + (Join-Path $RunRoot 'plan.json') + '"')) `
        -WorkingDirectory $repo -RedirectStandardOutput (Join-Path $RunRoot 'bot.stdout.log') `
        -RedirectStandardError (Join-Path $RunRoot 'bot.stderr.log') -WindowStyle Hidden -PassThru
    $children.Add($bot)
    $null = $bot.Handle
    $deadline = (Get-Date).AddMinutes(15)
    while (-not $bot.WaitForExit(100)) {
        if ((Get-Date) -ge $deadline) { throw 'Native transfer bot exceeded the 15 minute deadline.' }
        foreach ($process in $children) {
            if ($process.Id -ne $bot.Id -and $process.HasExited) { throw "Server $($process.Id) exited during the run." }
        }
        Send-PnxConsoleRequests -Directory $controlDirectory -RunId $runId -Runtimes $pnxRuntimes `
            -Handled $handledCommands -AllowedNames @($names.Values)
    }
    $bot.WaitForExit()
    $bot.Refresh()
    if ($bot.ExitCode -ne 0) { throw "Native transfer bot exited $($bot.ExitCode); inspect bot.stderr.log." }
    foreach ($process in $children) {
        if ($process.Id -ne $bot.Id -and $process.HasExited) { throw "Server $($process.Id) exited during the run." }
    }
    $logs = @(Join-Path $backendDir 'stdout.log'; Join-Path $backendDir 'stderr.log')
    foreach ($id in @('a', 'b')) {
        $logs += Join-Path $RunRoot "$id/pnx/pnx.stdout.log"
        $logs += Join-Path $RunRoot "$id/pnx/pnx.stderr.log"
    }
    $fatal = Test-ServerLogFatal -Paths $logs
    if ($fatal) { throw "Server fatal: $fatal" }
    & node (Join-Path $PSScriptRoot 'assert-evidence.js') $RunRoot
    if ($LASTEXITCODE -ne 0) { throw 'Native transfer evidence assertions failed.' }
} catch {
    $failure = Protect-BedrockErrorText -Text $_.Exception.ToString() -Secrets $publicSecrets
    Write-Utf8 (Join-Path $RunRoot 'failure.json') (@{ runId = $runId; error = $failure; pass = $false } | ConvertTo-Json)
} finally {
    for ($index = $children.Count - 1; $index -ge 0; $index--) {
        $process = $children[$index]
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    foreach ($runtime in $pnxRuntimes.Values) { Close-HeldConsoleStreams $runtime }
    Protect-BedrockPublicLogs -RunRoot $RunRoot -Secrets $publicSecrets
    $env:JAVA_HOME = $savedJava
    $env:BT_BACKEND_SECRET = $savedSecret
    $env:BT_RCON_PASSWORD = $savedRcon
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
    Write-Host "Native Bedrock transfer artifacts: $RunRoot"
}
if ($failure) { throw $failure }
