# ============================================================================
# run-bukkit-e2e.ps1 -- portable bukkit/Purpur L1 E2E orchestrator.
#
# Runs the full Layer-1 flow for the bukkit platform:
#   1. Build the NovaChat bukkit plugin (fat jar via shadow) + export StarLink:core
#      runtime classpath via an init script.
#   2. Fetch + SHA-256 verify the Purpur server jar (fetch-server.ps1).
#   3. Silently accept the Mojang EULA (per CI policy; see e2e/README.md).
#   4. Lay down a Purpur run directory with the plugin installed + server.properties.
#   5. Start the NovaLink backend (java -cp ... NovaLinkMain).
#   6. Start Purpur (java -jar purpur.jar nogui), wait for the "Done" log line.
#   7. Run the mineflayer bot (e2e/bot/run-e2e.js) which drives /nc + chat.
#   8. Assert results.json shows the expected L1 events (join, list, chat round-trip).
#   9. Teardown: stop Purpur + backend, dump logs/artifacts on failure.
#
# This is the ONLY fully-implemented platform in the committed e2e/ harness.
# Other platforms (folia, velocity, bungee, nukkit, pnx, sponge) are TODO stubs
# in run-e2e-orchestrator.ps1.
#
# Exit codes:
#   0 = L1 pass
#   1 = L1 fail (assertion mismatch or a process crashed)
#   2 = prereq error (build/download failed)
# ============================================================================
[CmdletBinding()]
param(
    [string]$RepoRoot      = (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)),
    [string]$DistDir       = "",     # server jar cache; defaults to <repo>/.e2e-artifacts/dist
    [string]$RunsDir       = "",     # per-run workspace; defaults to <repo>/.e2e-artifacts/runs/bukkit
    [int]$McPort           = 25565,
    [int]$NovaPort         = 27905,
    [int]$NovaWsPort       = 34573,
    [int]$ServerReadySec   = 240,
    [int]$BotTimeoutSec    = 360
)

$ErrorActionPreference = "Stop"
if (-not $DistDir) { $DistDir  = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $RunsDir) { $RunsDir  = Join-Path $RepoRoot ".e2e-artifacts/runs/bukkit" }
$binDir   = Join-Path $RepoRoot "e2e/bin"
$botDir   = Join-Path $RepoRoot "e2e/bot"
$confDir  = Join-Path $RepoRoot "e2e/conf"

function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg)
}

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
& $gradleW ":NovaChat:Plugin:bukkit:build" ":StarLink:core:jar" "-x" "test" "--console=plain" `
    "-Porg.gradle.java.installations.paths=" "-Dorg.gradle.java.installations.paths=" `
    "--init-script" (Join-Path $binDir "write-classpath.init.gradle") `
    ":StarLink:core:writeRuntimeClasspath" `
    1>$buildOut 2>$buildErr
if ($LASTEXITCODE -ne 0) {
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
$purpurJar = Join-Path $DistDir "purpur-1.21.8-2497.jar"

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
# Install the NovaChat bukkit plugin.
Copy-Item $bukkitJar.FullName -Destination (Join-Path $serverDir "plugins") -Force

# ---------------------------------------------------------------------------
# 5. Generate backend config + start backend.
# ---------------------------------------------------------------------------
Log "step 4: generating backend config + starting NovaLink backend"
$novaDir = Join-Path $RunsDir "novalink"
New-Item -ItemType Directory -Path $novaDir -Force | Out-Null
$secretKey = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$clientPw  = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
$novaYml = Join-Path $novaDir "novalink.yml"
$tpl = Get-Content (Join-Path $confDir "novalink.template.yml") -Raw
$tpl = $tpl.Replace('{{NOVA_PORT}}', $NovaPort).Replace('{{NOVA_WS_PORT}}', $NovaWsPort).Replace('{{SECRET_KEY}}', $secretKey).Replace('{{CLIENT_PASSWORD}}', $clientPw)
$tpl | Set-Content -Path $novaYml -NoNewline

$cp = (Get-Content $classpathFile -Raw).Trim()
$novaStdout = Join-Path $novaDir "stdout.log"
$novaStderr = Join-Path $novaDir "stderr.log"
$novaPidFile = Join-Path $novaDir "backend.pid"
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/java" } else { "java" }
$novaProc = Start-Process -FilePath $java -ArgumentList @("-cp", $cp, "com.nova.link.NovaLinkMain", $novaYml) `
    -WorkingDirectory $novaDir -RedirectStandardOutput $novaStdout -RedirectStandardError $novaStderr -PassThru -NoNewWindow
Track-Pid $novaProc.Id
$novaProc.Id | Set-Content $novaPidFile
Log "  backend pid=$($novaProc.Id) on port $NovaPort"

# Wait for backend readiness: poll stdout for a ready line OR the TCP port.
$ready = $false
$readyPattern = 'NovaLink.*started|NovaProtocol.*listening|WebSocket.*listening'
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    if ($novaProc.HasExited) {
        Log "ERROR: backend exited early; stderr:"; Get-Content $novaStderr -ErrorAction SilentlyContinue | Select-Object -First 10 | ForEach-Object { Log "  $_" }
        Teardown-All; exit 1
    }
    if (Test-Path $novaStdout) {
        $logContent = Get-Content $novaStdout -Raw -ErrorAction SilentlyContinue
        if ($logContent -match $readyPattern) { $ready = $true; break }
    }
}
if (-not $ready) { Log "WARN: backend readiness not confirmed in logs, proceeding (Purpur will retry connect)" }

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
        Teardown-All; exit 1
    }
    if (Test-Path $purpStdout) {
        $logContent = Get-Content $purpStdout -Raw -ErrorAction SilentlyContinue
        if ($logContent -match 'Done \(') { $serverReady = $true; break }
    }
}
if (-not $serverReady) {
    Log "ERROR: Purpur did not reach 'Done' within ${ServerReadySec}s"
    Teardown-All; exit 1
}
Log "  Purpur ready"

# ---------------------------------------------------------------------------
# 7. Run the bot.
# ---------------------------------------------------------------------------
Log "step 6: installing + running the mineflayer bot"
if (-not (Test-Path (Join-Path $botDir "node_modules/mineflayer"))) {
    Push-Location $botDir
    try { npm install --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { Log "ERROR: npm install failed"; Teardown-All; exit 2 } }
    finally { Pop-Location }
}
$resultsFile = Join-Path $RunsDir "bot-results.json"
$env:E2E_MC_HOST = "127.0.0.1"
$env:E2E_MC_PORT = "$McPort"
$env:E2E_MC_VERSION = "1.21.8"
$env:E2E_BOT_USERNAME = "E2E_Bot_Alpha"
$env:E2E_RESULTS_FILE = $resultsFile
$env:E2E_TIMEOUT_MS = "$($BotTimeoutSec * 1000)"
$env:E2E_PLATFORM = "bukkit"
$env:E2E_SERVER_ID = "purpur-1.21.8-2497"
$botStdout = Join-Path $RunsDir "bot.stdout.log"
$botStderr = Join-Path $RunsDir "bot.stderr.log"
$botProc = Start-Process -FilePath "node" -ArgumentList @("run-e2e.js") -WorkingDirectory $botDir `
    -RedirectStandardOutput $botStdout -RedirectStandardError $botStderr -PassThru -NoNewWindow
Track-Pid $botProc.Id
$botProc.WaitForExit($BotTimeoutSec * 1000) | Out-Null
if (-not $botProc.HasExited) { Stop-Pid $botProc.Id }
Log "  bot exit code=$($botProc.ExitCode)"

# ---------------------------------------------------------------------------
# 8. Assert results.json.
# ---------------------------------------------------------------------------
Log "step 7: asserting L1 results"
$pass = $false
if (Test-Path $resultsFile) {
    $results = Get-Content $resultsFile -Raw | ConvertFrom-Json
    $msgs = $results.received | ForEach-Object { $_.text }
    # L1 assertions: join succeeded (channel joined message), list shows global,
    # and at least one chat message was captured (round-trip).
    $joinOk   = $msgs -match '已加入频道|Joined channel|joined.*global'
    $listOk   = $msgs -match 'global'
    $chatOk   = ($msgs | Where-Object { $_ -match 'hello from e2e bot' }).Count -ge 1
    $noFatal  = -not ($msgs -match 'FATAL|Could not|Exception.*NovaLink')
    Log ("  join={0} list={1} chat={2} noFatal={3}" -f $joinOk, $listOk, $chatOk, $noFatal)
    $pass = $joinOk -and $listOk -and $chatOk -and $noFatal
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
    exit 1
}
