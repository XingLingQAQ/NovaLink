# ============================================================================
# run-bukkit-e2e.ps1 -- portable bukkit/Purpur L1 E2E orchestrator.
#
# Runs the full Layer-1 flow for the bukkit platform:
#   1. Build the NovaChat bukkit plugin (fat jar via shadow) + export StarLink:core
#      runtime classpath via an init script.
#   2. Fetch + SHA-256 verify the Purpur server jar (fetch-server.ps1).
#   3. Silently accept the Mojang EULA (per CI policy; see test/README.md).
#   4. Lay down a Purpur run directory with the plugin installed + server.properties.
#   5. Start the NovaLink backend (java -cp ... NovaLinkMain).
#   6. Start Purpur (java -jar purpur.jar nogui), wait for the "Done" log line.
#   7. Run the mineflayer bot (test/bot/run-e2e.js) which drives /nc + chat.
#   8. Assert results.json shows the expected L1 events (join, list, chat round-trip).
#   9. Teardown: stop Purpur + backend, dump logs/artifacts on failure.
#
# This is the self-contained bukkit implementation in the committed test/
# harness. Other Java platforms use the shared multiplatform orchestrator.
#
# Exit codes:
#   0 = L1 pass
#   1 = L1 fail (assertion mismatch or a process crashed)
#   2 = prereq error (build/download failed)
# ============================================================================
[CmdletBinding()]
param(
    # Resolved in the body below -- see run-pmmp-e2e.ps1 for why a
    # $MyInvocation default is $null under [CmdletBinding()].
    [string]$RepoRoot      = "",
    [string]$DistDir       = "",     # server jar cache; defaults to <repo>/.e2e-artifacts/dist
    [string]$RunsDir       = "",     # per-run workspace; defaults to <repo>/.e2e-artifacts/runs/bukkit
    [int]$McPort           = 25565,
    [int]$NovaPort         = 27905,
    [int]$NovaWsPort       = 34573,
    # 480, not 240: first-boot world generation measured 236.5s on Purpur
    # 1.21.11, leaving 3.5s of margin against the old budget.
    [int]$ServerReadySec   = 480,
    [int]$BotTimeoutSec    = 360
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)) }
if (-not $DistDir) { $DistDir  = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $RunsDir) { $RunsDir  = Join-Path $RepoRoot ".e2e-artifacts/runs/bukkit" }
$binDir   = Join-Path $RepoRoot "test/bin"
$botDir   = Join-Path $RepoRoot "test/bot"
$confDir  = Join-Path $RepoRoot "test/conf"

function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg)
}

# --- failure diagnostics -----------------------------------------------------
# The backend, server and bot all write to files under $RunsDir, and a failing
# run used to report only a one-line reason while the actual cause sat unread
# on disk. Print the tail of each log, labelled with its path, so the CI job
# log alone is enough to diagnose a failure.
function Get-LogRank([string]$name) {
    switch -Wildcard ($name) {
        '*stderr.log' { return 0 }
        '*stdout.log' { return 1 }
        'build.*.log' { return 2 }
        default       { return 4 }
    }
}

function Show-FailureLogs([string]$reason) {
    Log ""
    Log "=== FAILURE DIAGNOSTICS: $reason ==="
    if (-not (Test-Path -LiteralPath $RunsDir)) { Log "  run directory does not exist: $RunsDir"; return }
    $logs = @(Get-ChildItem -LiteralPath $RunsDir -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -eq '.log' } |
        Sort-Object @{ Expression = { Get-LogRank $_.Name } },
                    @{ Expression = { $_.LastWriteTime }; Descending = $true } |
        Select-Object -First 10)
    if ($logs.Count -eq 0) { Log "  no *.log files under $RunsDir"; return }
    foreach ($f in $logs) {
        Log ("  --- {0} ({1:N0} bytes, last 40 lines) ---" -f $f.FullName, $f.Length)
        Get-Content -LiteralPath $f.FullName -Tail 40 -Encoding UTF8 -ErrorAction SilentlyContinue |
            ForEach-Object { Log "    $_" }
    }
    Log "=== END FAILURE DIAGNOSTICS ==="
}

# The server jar name, the bot's protocol version and the server id all have to
# agree with versions.lock.ps1. Read them from the lock instead of repeating the
# literals here: they drifted once already (the lock said 26.2, the bot could
# only speak 26.1.2) and the mismatch surfaced as an opaque
# "unsupported protocol version" three steps later.
. (Join-Path (Split-Path -Parent $binDir) 'versions.lock.ps1')
. (Join-Path $binDir 'e2e-assertions.ps1')
$purpurLock = Get-LockedServer -Name 'purpur'
if (-not $purpurLock) { Log "ERROR: no 'purpur' entry in test/versions.lock.ps1"; exit 2 }
$purpurFile = $purpurLock.File
$mcVersion  = $purpurLock.MC
$serverId   = "purpur-$($purpurLock.MC)-$($purpurLock.Build)"

# Track every PID we start so teardown is guaranteed.
$global:Pids = [System.Collections.Generic.List[int]]::new()
function Track-Pid([int]$procId) { if ($procId -gt 0) { $global:Pids.Add($procId) | Out-Null } }
function Stop-Pid([int]$procId) {
    if ($procId -le 0) { return }
    try {
        $p = Get-Process -Id $procId -ErrorAction Stop
        try { $p.CloseMainWindow() | Out-Null } catch {}
        Start-Sleep -Milliseconds 400
        if (-not $p.HasExited) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue }
    } catch {}
}
function Teardown-All {
    Log "--- teardown: stopping $($global:Pids.Count) process(es) ---"
    # Close the backend's kept-open stdin pipe first. Closing it delivers EOF,
    # which is the backend's graceful shutdown path (see the launch note in
    # step 4); Stop-Process then handles anything still standing. The drain
    # subscriptions are removed in the same pass so a later Add-Content cannot
    # race the process going away.
    if ($script:novaProcess -and -not $script:novaProcess.HasExited) {
        try { $script:novaProcess.StandardInput.Close() } catch {}
        Start-Sleep -Milliseconds 800
    }
    Get-EventSubscriber -ErrorAction SilentlyContinue |
        Where-Object { $_.SourceIdentifier -like 'NovaLinkBackend*' } |
        ForEach-Object { Unregister-Event -SubscriptionId $_.SubscriptionId -ErrorAction SilentlyContinue }
    for ($i = $global:Pids.Count - 1; $i -ge 0; $i--) { Stop-Pid $global:Pids[$i] }
    $global:Pids.Clear()
}
trap { Log "TRAP: $($_.Exception.Message)"; Teardown-All; break }
$null = Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action { Teardown-All }

# ---------------------------------------------------------------------------
# 1. Build bukkit plugin + export backend classpath.
# ---------------------------------------------------------------------------
Log "step 1: building NovaChat bukkit plugin + exporting StarLink:core classpath"
# Use gradlew.bat on Windows, gradlew on Unix. $IsWindows exists in PowerShell 7;
# fall back to $env:OS for Windows PowerShell 5.1.
$isWin = $IsWindows -or $env:OS -eq "Windows_NT"
$gradleW = if ($isWin) { ".\gradlew.bat" } else { "./gradlew" }
# Build only the bukkit fat jar + StarLink:core jar (skip tests, skip the rest).
$buildErr = Join-Path $RunsDir "build.err.log"
$buildOut = Join-Path $RunsDir "build.out.log"
New-Item -ItemType Directory -Path $RunsDir -Force | Out-Null
# gradlew writes banner lines to stderr ("Picked up JAVA_TOOL_OPTIONS", Fabric
# Loom's "Cannot remap modifiers"), and under $ErrorActionPreference = "Stop"
# the call operator turns each one into a terminating NativeCommandError that
# the trap below catches -- so the build aborted before gradle even ran.
# Drop to Continue for just this invocation; the exit code is read explicitly
# right after, so a real build failure is still detected. Start-Process is not
# an option here: it throws on hosts whose environment holds case-variant
# duplicates (see the note in run-e2e-orchestrator.ps1).
$savedEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $gradleW ":NovaChat:Plugin:bukkit:build" ":StarLink:core:jar" "-x" "test" "--console=plain" `
    "-Porg.gradle.java.installations.paths=" "-Dorg.gradle.java.installations.paths=" `
    "--init-script" (Join-Path $binDir "write-classpath.init.gradle") `
    ":StarLink:core:writeRuntimeClasspath" `
    1>$buildOut 2>$buildErr
$gradleExit = $LASTEXITCODE
$ErrorActionPreference = $savedEap
if ($gradleExit -ne 0) {
    Log "ERROR: gradle build failed (see $buildErr)"
    Get-Content $buildErr -ErrorAction SilentlyContinue | Select-Object -First 20 | ForEach-Object { Log "  $_" }
    exit 2
}

$classpathFile = Join-Path $RepoRoot ".e2e-artifacts/novalink-core.classpath.txt"
if (-not (Test-Path $classpathFile)) {
    Log "ERROR: classpath file not written: $classpathFile"
    exit 2
}
$bukkitJar = Get-ChildItem (Join-Path $RepoRoot "NovaChat/Plugin/bukkit/build/libs/*.jar") -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $bukkitJar) {
    Log "ERROR: bukkit fat jar not found under NovaChat/Plugin/bukkit/build/libs/"
    exit 2
}
Log "  bukkit jar: $($bukkitJar.FullName)"

# ---------------------------------------------------------------------------
# 2. Fetch + verify Purpur.
# ---------------------------------------------------------------------------
Log "step 2: fetching Purpur server jar (SHA-256 verified)"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $binDir "fetch-server.ps1") -Name "purpur" -DistDir $DistDir
if ($LASTEXITCODE -ne 0) { Log "ERROR: fetch-server failed"; exit 2 }
$purpurJar = Join-Path $DistDir $purpurFile

# ---------------------------------------------------------------------------
# 3 + 4. Lay down Purpur run directory + silently accept EULA.
# ---------------------------------------------------------------------------
Log "step 3: laying down Purpur run directory + EULA"
$serverDir = Join-Path $RunsDir "paper"
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $serverDir "plugins") -Force | Out-Null
# CI policy: silently accept the Mojang EULA. The harness does NOT commit a
# eula.txt; it writes one into the throwaway run directory at start time.
"eula=true" | Set-Content -Path (Join-Path $serverDir "eula.txt") -NoNewline
# server.properties
@"
server-port=$McPort
online-mode=false
motd=NovaChat E2E
level-name=world
gamemode=survival
"@ | Set-Content -Path (Join-Path $serverDir "server.properties")
# Random hex helper. Windows PowerShell 5.1 runs on .NET Framework, where the
# static RandomNumberGenerator::GetBytes(int) overload and Convert::ToHexString
# do not exist -- calling them throws "MethodNotFound". The instance
# Create().GetBytes(byte[]) + BitConverter path works on both 5.1 and 7+.
# Mirrors New-RandomHex in run-endstone/levilamina/pmmp-e2e.ps1.
function New-RandomHex([int]$byteLen) {
    $buf = New-Object byte[] $byteLen
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buf)
    return [BitConverter]::ToString($buf).Replace('-', '')
}
# Generated before the plugin config is written: the same password has to land
# in novalink.yml (clients[].password) and plugins/NovaChat/config.yml.
$secretKey = New-RandomHex 32
$clientPw  = New-RandomHex 16

# Install the NovaChat bukkit plugin.
Copy-Item $bukkitJar.FullName -Destination (Join-Path $serverDir "plugins") -Force

# The plugin's bundled resources/config.yml is a user-facing template: backend
# port 8888 and username "Survival_Server". With no config.yml in the data
# folder, YamlConfigUpdater copies that template verbatim, so the plugin dials
# 8888 while the backend listens on $NovaPort and the handshake never leaves the
# box (every /nc join then answers NC-503 "not connected"). Write the real
# credentials BEFORE first start, exactly as run-pmmp/endstone/levilamina do.
# backend.username MUST match clients[].username in novalink.yml ("E2E_Client")
# and backend.password MUST match {{CLIENT_PASSWORD}}, or the backend answers
# the challenge with NC-401.
$pluginDataDir = Join-Path $serverDir "plugins/NovaChat"
New-Item -ItemType Directory -Path $pluginDataDir -Force | Out-Null
$pluginConfig = Join-Path $pluginDataDir "config.yml"
@"
backend:
  host: "127.0.0.1"
  port: $NovaPort
  username: "E2E_Client"
  password: "$clientPw"
  reconnect-delay: 5
chat:
  replace_vanilla: false
  default_channel: "global"
  locale: "en_US"
debug: true
"@ | Set-Content -Path $pluginConfig

# ---------------------------------------------------------------------------
# 5. Generate backend config + start backend.
# ---------------------------------------------------------------------------
Log "step 4: generating backend config + starting NovaLink backend"
$novaDir = Join-Path $RunsDir "novalink"
New-Item -ItemType Directory -Path $novaDir -Force | Out-Null
$novaYml = Join-Path $novaDir "novalink.yml"
$tpl = Get-Content (Join-Path $confDir "novalink.template.yml") -Raw
$tpl = $tpl.Replace('{{NOVA_PORT}}', $NovaPort).Replace('{{NOVA_WS_PORT}}', $NovaWsPort).Replace('{{SECRET_KEY}}', $secretKey).Replace('{{CLIENT_PASSWORD}}', $clientPw)
$tpl | Set-Content -Path $novaYml -NoNewline

$cp = (Get-Content $classpathFile -Raw).Trim()
$novaStdout = Join-Path $novaDir "stdout.log"
$novaStderr = Join-Path $novaDir "stderr.log"
$novaPidFile = Join-Path $novaDir "backend.pid"
# Resolve the java executable. On Windows it is java.exe; on Unix just java.
$javaExe = if ($isWin) { "java.exe" } else { "java" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/$javaExe" } else { "java" }

# Start the backend with stdin REDIRECTED to a pipe that we keep open.
#
# Start-Process gives the child no usable stdin: the JVM sees EOF immediately.
# NovaLinkMain runs BackendConsole on the main thread, and readLine() throwing
# EndOfFileException is treated as Ctrl+D -- an intentional shutdown. So the
# backend logs "started successfully", prints "EOF received, shutting down...",
# and exits ~1s later. The readiness loop below then sees a live port in its
# first poll and reports success, while every plugin connection is refused
# moments later. That is the NC-503 this harness used to produce.
#
# System.Diagnostics.Process with RedirectStandardInput = $true hands the JVM a
# pipe; because we never write to and never close it, readLine() blocks instead
# of returning EOF and the console stays alive for the whole run. The handle is
# kept in $novaProcess (not the old $novaProc) so teardown can close it.
# Start-Process is still used for Purpur and the bot below -- neither exits on
# stdin EOF.
$novaPsi = New-Object System.Diagnostics.ProcessStartInfo
$novaPsi.FileName = $java
# No Join-String here: that cmdlet is PowerShell 7+, and 5.1 is still a supported
# runner. Each argument is quoted individually, which is what the JVM needs for
# paths containing spaces.
$novaPsi.Arguments = (@("-cp", $cp, "com.nova.link.NovaLinkMain", $novaYml) |
    ForEach-Object { '"' + $_ + '"' }) -join ' '
$novaPsi.WorkingDirectory = $novaDir
$novaPsi.UseShellExecute = $false
$novaPsi.RedirectStandardInput = $true
$novaPsi.RedirectStandardOutput = $true
$novaPsi.RedirectStandardError = $true
$novaPsi.CreateNoWindow = $true
$novaProcess = [System.Diagnostics.Process]::Start($novaPsi)
Track-Pid $novaProcess.Id
$novaProcess.Id | Set-Content $novaPidFile
Log "  backend pid=$($novaProcess.Id) on port $NovaPort"
# Drain the redirected pipes into the log files on background events. Doing this
# synchronously (ReadToEnd) would block until the backend exited and starve the
# readiness loop below; not draining at all would eventually block the JVM once
# the OS pipe buffer filled. The handlers append as data arrives, so the file is
# already complete when the readiness loop or an error dump reads it.
$null = Register-ObjectEvent -InputObject $novaProcess -EventName OutputDataReceived -SourceIdentifier "NovaLinkBackendStdout" -Action {
    if ($EventArgs.Data -ne $null) { Add-Content -LiteralPath $Event.MessageData -Value $EventArgs.Data }
} -MessageData $novaStdout
$null = Register-ObjectEvent -InputObject $novaProcess -EventName ErrorDataReceived -SourceIdentifier "NovaLinkBackendStderr" -Action {
    if ($EventArgs.Data -ne $null) { Add-Content -LiteralPath $Event.MessageData -Value $EventArgs.Data }
} -MessageData $novaStderr
$novaProcess.BeginOutputReadLine()
$novaProcess.BeginErrorReadLine()

# Wait for backend readiness: a "started" log line AND an accepting TCP port.
# Both are required. The log line alone used to be enough, and that is exactly
# how the stdin-EOF shutdown slipped through: the line appears before the
# console loop notices EOF, so a one-poll match declared a backend ready that
# was already exiting. Requiring the port to accept pins it to a listener that
# is still serving.
$ready = $false
$readyPattern = 'NovaLink.*started|NovaProtocol.*listening|WebSocket.*listening'
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    if ($novaProcess.HasExited) {
        # The backend logs fatal startup errors to stdout (its logger writes
        # there), so dumping only stderr showed just the JAVA_TOOL_OPTIONS
        # banner and hid the actual cause. Show both.
        Log "ERROR: backend exited early; stdout (tail):"
        Get-Content $novaStdout -ErrorAction SilentlyContinue | Select-Object -Last 20 | ForEach-Object { Log "  $_" }
        Log "ERROR: backend exited early; stderr:"
        Get-Content $novaStderr -ErrorAction SilentlyContinue | Select-Object -First 10 | ForEach-Object { Log "  $_" }
        Teardown-All; Show-FailureLogs "backend exited early"; exit 1
    }
    $logReady = $false
    if (Test-Path $novaStdout) {
        $logContent = Get-Content $novaStdout -Raw -ErrorAction SilentlyContinue
        if ($logContent -match $readyPattern) { $logReady = $true }
    }
    if ($logReady) {
        $portOpen = $false
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $portOpen = $tcp.ConnectAsync("127.0.0.1", $NovaPort).Wait(2000) -and $tcp.Connected
            $tcp.Close()
        } catch { $portOpen = $false }
        if ($portOpen) { $ready = $true; break }
    }
}
if (-not $ready) {
    # Fatal, not a warning: a backend that never accepted a connection cannot
    # serve a single /nc command, so continuing only produces the downstream
    # NC-503 with none of the context that explains it.
    Log "ERROR: backend did not become ready on 127.0.0.1:$NovaPort within 60s; stdout (tail):"
    Get-Content $novaStdout -ErrorAction SilentlyContinue | Select-Object -Last 20 | ForEach-Object { Log "  $_" }
    Teardown-All; exit 1
}
Log "  backend ready on 127.0.0.1:$NovaPort"

# ---------------------------------------------------------------------------
# 6. Start Purpur + wait for "Done".
# ---------------------------------------------------------------------------
Log "step 5: starting Purpur on port $McPort"
$purpStdout = Join-Path $serverDir "purpur.stdout.log"
$purpStderr = Join-Path $serverDir "purpur.stderr.log"
$purpPidFile = Join-Path $serverDir "purpur.pid"
# Paper/Purpur need a bigger heap; -Xmx1G matches the local harness.
$purpProc = Start-Process -FilePath $java -ArgumentList @("-Xmx1536M", "-Xms512M", "-jar", $purpurJar, "nogui") `
    -WorkingDirectory $serverDir -RedirectStandardOutput $purpStdout -RedirectStandardError $purpStderr -PassThru -NoNewWindow
Track-Pid $purpProc.Id
$purpProc.Id | Set-Content $purpPidFile
Log "  purpur pid=$($purpProc.Id)"

$serverReady = $false
for ($i = 0; $i -lt ($ServerReadySec / 5); $i++) {
    Start-Sleep -Seconds 5
    if ($purpProc.HasExited) {
        Log "ERROR: Purpur exited early; stderr:"; Get-Content $purpStderr -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Log "  $_" }
        Teardown-All; Show-FailureLogs "Purpur exited early"; exit 1
    }
    if (Test-Path $purpStdout) {
        $logContent = Get-Content $purpStdout -Raw -ErrorAction SilentlyContinue
        if ($logContent -match 'Done \(') { $serverReady = $true; break }
    }
}
if (-not $serverReady) {
    Log "ERROR: Purpur did not reach 'Done' within ${ServerReadySec}s"
    Teardown-All; Show-FailureLogs "Purpur did not reach 'Done' within ${ServerReadySec}s"; exit 1
}
Log "  Purpur ready"

# ---------------------------------------------------------------------------
# 7. Run the bot.
# ---------------------------------------------------------------------------
Log "step 6: installing + running the mineflayer bot"
if (-not (Test-Path (Join-Path $botDir "node_modules/mineflayer"))) {
    Push-Location $botDir
    try { npm ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { Log "ERROR: npm ci failed"; Teardown-All; Show-FailureLogs "npm ci failed"; exit 2 } }
    finally { Pop-Location }
}
$resultsFile = Join-Path $RunsDir "bot-results.json"
$env:E2E_MC_HOST = "127.0.0.1"
$env:E2E_MC_PORT = "$McPort"
$env:E2E_MC_VERSION = $mcVersion
$env:E2E_BOT_USERNAME = "E2E_Bot_Alpha"
$env:E2E_RESULTS_FILE = $resultsFile
$env:E2E_TIMEOUT_MS = "$($BotTimeoutSec * 1000)"
$env:E2E_PLATFORM = "bukkit"
$env:E2E_SERVER_ID = $serverId
$botStdout = Join-Path $RunsDir "bot.stdout.log"
$botStderr = Join-Path $RunsDir "bot.stderr.log"
$botProc = Start-Process -FilePath "node" -ArgumentList @("run-e2e.js") -WorkingDirectory $botDir `
    -RedirectStandardOutput $botStdout -RedirectStandardError $botStderr -PassThru -NoNewWindow
Track-Pid $botProc.Id
$botProc.WaitForExit($BotTimeoutSec * 1000) | Out-Null
if (-not $botProc.HasExited) { Stop-Pid $botProc.Id }
# WaitForExit(timeout) returning false leaves the process object stale, so
# ExitCode throws and the log line came out as "bot exit code=" with nothing
# after it -- which hid the real reason the bot died (e.g. a missing module).
$botProc.WaitForExit()
Log "  bot exit code=$($botProc.ExitCode)"
if ($botProc.ExitCode -ne 0) {
    Log "  bot stderr:"
    Get-Content $botStderr -ErrorAction SilentlyContinue | Select-Object -Last 15 | ForEach-Object { Log "    $_" }
}

# ---------------------------------------------------------------------------
# 8. Assert results.json.
# ---------------------------------------------------------------------------
Log "step 7: asserting L1 results"
$pass = $false
if (Test-Path $resultsFile) {
    # ReadAllText, not Get-Content -Raw: on Windows PowerShell 5.1 the latter
    # decodes with the ANSI code page, so the Chinese "已加入频道" line the bot
    # received came back as mojibake and ConvertFrom-Json rejected the whole
    # file. Node always writes UTF-8; ReadAllText defaults to UTF-8 on both 5.1
    # and 7+, matching the producer.
    $resultsJson = [System.IO.File]::ReadAllText($resultsFile, [System.Text.Encoding]::UTF8)
    $results = $resultsJson | ConvertFrom-Json
    # Get-BotReceivedRows drops the bot's own outgoing lines. run-e2e.js records
    # every sendCmd() into the SAME received[] list with kind='sent', so the old
    # $msgs contained "/nc join global" and "hello from e2e bot" -- the exact
    # strings the join/chat assertions looked for. Both matched the bot's own
    # input, so neither could fail on any plugin state (see e2e-assertions.ps1).
    $rows = Get-BotReceivedRows $results
    $msgs = Get-BotReceivedTexts $rows
    # -match on a collection returns the MATCHING ELEMENTS, not a boolean, so the
    # old code logged "join=System.Object[]" and only worked by accident (a
    # non-empty array is truthy). Cast to bool: the log then says what failed.
    #
    # joinOk: the backend-accepted confirmation. Bukkit prints "Joining channel"
    # optimistically from JoinCommand, so that line proves nothing; "Joined
    # channel" is only sent by ChannelResponseDispatcher.sendJoinSuccess, i.e.
    # after a successful ChannelActionResponse from the backend.
    $joinOk   = [bool]($msgs -match '已加入频道|Joined channel')
    $listOk   = [bool]($msgs -match 'global')
    # chatOk: the round-trip -- the phrase must come back inside NovaChat's
    # channel-format envelope, "[<channel>] <player>: <text>".
    #
    # Two weaker versions were vacuous here. (a) The plain 'chat' kind is
    # vanilla's own re-broadcast of the sender's line, so it appears on a
    # server with no plugin. (b) Matching the phrase on ANY messagestr row is
    # also insufficient on this platform: mineflayer emits messagestr for
    # vanilla chat too (verified in .e2e-artifacts/joint payloads, which carry
    # both "E2EJointJava joined the game" and the formatted "[Global] ..."
    # line as messagestr). The envelope is what vanilla never prints.
    #
    # The channel id is matched loosely (any bracket) because this harness
    # rewrites config.yml with only backend/chat/debug, so the plugin falls
    # back to its bundled format.default -- "&7[{channel_color}{channel_name}]
    # {player}&f: {message}" -- which renders as "[global] E2E_Bot_Alpha: ..."
    # rather than the template's "[全服] ..." global override.
    #
    # The bot now sends this phrase in REPLACE mode (one toggle, where it used
    # to send two -- see run-e2e.js). That matters here: in HYBRID
    # ChatInterceptor.onPlayerChat returns early ("allowing vanilla chat") and
    # forwards nothing, so the old toggle->toggle sequence meant the phrase was
    # only ever vanilla's echo.
    $chatOk   = (@(Select-BotRowsByPlainText $rows '\[[^\]]+\]\s*E2E_Bot_Alpha\s*:\s*.*hello from e2e bot')).Count -ge 1
    # pluginOk: a line only NovaChat can emit (its "[NovaChat]" prefix / help
    # header) reached the bot. Without this, a server that ran the bot against
    # no plugin at all would still satisfy join/list/chat through vanilla chat.
    $pluginOk = Test-PluginEvidence $rows
    # backendOk: the /nc who progress prompt, which bukkit only prints after
    # AbstractSubCommand::checkConnection sees isConnected AND isAuthenticated.
    # It is the one line here that a plugin loaded without a working backend
    # cannot produce (that path prints "Not connected to the chat server").
    $backendOk = Test-BackendEvidence $rows
    # Fatal check reads the SERVER's log, not the bot's chat transcript: plugin
    # exceptions are written to the console. The old $msgs scan looked at the
    # wrong file entirely and was a constant true.
    $fatal = Test-ServerLogFatal @($purpStdout, $purpStderr)
    $noFatal = ($null -eq $fatal)
    Log ("  join={0} list={1} chat={2} plugin={3} backend={4} noFatal={5}" -f `
        $joinOk, $listOk, $chatOk, $pluginOk, $backendOk, $noFatal)
    if (-not $noFatal) { Log ("  FATAL in server log: {0}:{1}: {2}" -f $fatal.Path, $fatal.LineNumber, $fatal.Line.Trim()) }
    $pass = $joinOk -and $listOk -and $chatOk -and $pluginOk -and $backendOk -and $noFatal
} else {
    Log "ERROR: results.json not written: $resultsFile"
}

# ---------------------------------------------------------------------------
# 9. Teardown.
# ---------------------------------------------------------------------------
Teardown-All
if ($pass) {
    Log "RESULT: L1 PASS (bukkit/purpur)"
    exit 0
} else {
    Log "RESULT: L1 FAIL (bukkit/purpur) -- dumping bot log tail:"
    Get-Content $botStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    Show-FailureLogs "L1 assertion failed"
    exit 1
}
