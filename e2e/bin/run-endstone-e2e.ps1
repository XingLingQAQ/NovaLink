# ============================================================================
# run-endstone-e2e.ps1 -- portable Endstone/BDS L1 E2E orchestrator.
#
# Runs the full Layer-1 flow for the endstone platform:
#   1. Build the NovaLink backend (StarLink:core classpath via init-script, or
#      a fat jar if shadow landed it).
#   2. Install the NovaChat endstone plugin (python package, editable) into a
#      venv that Endstone's BDS host will import.
#   3. Fetch + unpack the Bedrock Dedicated Server (BDS) via fetch-server.ps1
#      -Name bds -Auto, then install Endstone on top of it.
#   4. Generate backend config + start the NovaLink backend.
#   5. Start BDS+Endstone, wait for the "Done" log line + plugin enable.
#   6. Run the bedrock-protocol bot (e2e/bot/run-e2e-bedrock.js).
#   7. Assert results.json shows the L1 events (plugin enable + auth + chat).
#   8. Teardown: stop BDS + backend, dump logs/artifacts on failure.
#
# Endstone is a python plugin host layered on Mojang's Bedrock Dedicated Server.
# Unlike pmmp (a phar+PHP), endstone requires: the BDS zip, the Endstone runtime
# (pip-installed or a release bundle), and the NovaChat python package installed
# where Endstone's plugin loader can find it. This is heavier than pmmp, so the
# script documents each assumed step.
#
# Exit codes:
#   0 = L1 pass
#   1 = L1 fail (assertion mismatch or a process crashed)
#   2 = prereq error (build/download failed)
# ============================================================================
[CmdletBinding()]
param(
    [string]$RepoRoot      = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))),
    [string]$DistDir       = "",     # server cache; defaults to <repo>/.e2e-artifacts/dist
    [string]$RunsDir       = "",     # per-run workspace; defaults to <repo>/.e2e-artifacts/runs/endstone
    [int]$McPort           = 19132,  # Bedrock default UDP port
    [int]$NovaPort         = 27906,
    [int]$NovaWsPort       = 34574,
    [int]$ServerReadySec   = 240,
    [int]$BotTimeoutSec    = 360
)

$ErrorActionPreference = "Stop"
if (-not $DistDir) { $DistDir  = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $RunsDir) { $RunsDir  = Join-Path $RepoRoot ".e2e-artifacts/runs/endstone" }
$binDir   = Join-Path $RepoRoot "e2e/bin"
$botDir   = Join-Path $RepoRoot "e2e/bot"
$confDir  = Join-Path $RepoRoot "e2e/conf"
$endstoneSrc = Join-Path $RepoRoot "NovaChat/Bedrock/endstone"

function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg)
}

# Generate a random hex string of the given byte length. Compatible with both
# Windows PowerShell 5.1 (.NET Framework, where RandomNumberGenerator::GetBytes(int)
# and Convert::ToHexString do not exist) and PowerShell 7+ (.NET, where they do).
function New-RandomHex([int]$byteLen) {
    $buf = New-Object byte[] $byteLen
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buf)
    return [BitConverter]::ToString($buf).Replace('-', '')
}

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

$isWin = $IsWindows -or $env:OS -eq "Windows_NT"
$gradleW = if ($isWin) { ".\gradlew.bat" } else { "./gradlew" }
$javaExe = if ($isWin) { "java.exe" } else { "java" }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/$javaExe" } else { "java" }
$pythonExe = if ($isWin) { "python.exe" } else { "python3" }

New-Item -ItemType Directory -Path $RunsDir -Force | Out-Null
New-Item -ItemType Directory -Path $DistDir -Force | Out-Null

# ---------------------------------------------------------------------------
# 1. Build backend + export classpath.
# ---------------------------------------------------------------------------
Log "step 1: exporting StarLink:core backend classpath"
$buildErr = Join-Path $RunsDir "build.err.log"
$buildOut = Join-Path $RunsDir "build.out.log"
# Use Start-Process (not the & call operator) so gradlew's stderr output is
# captured to the file instead of being wrapped as a NativeCommandError record.
# Under $ErrorActionPreference = "Stop", the & call operator + 2>$buildErr would
# turn each Fabric Loom stderr banner line into a terminating error.
$gradleArgs = @(":StarLink:core:jar", "-x", "test", "--console=plain", `
    "-Porg.gradle.java.installations.paths=", "-Dorg.gradle.java.installations.paths=", `
    "--init-script", (Join-Path $binDir "write-classpath.init.gradle"), `
    ":StarLink:core:writeRuntimeClasspath")
$gradleProc = Start-Process -FilePath $gradleW -ArgumentList $gradleArgs -WorkingDirectory $RepoRoot `
    -RedirectStandardOutput $buildOut -RedirectStandardError $buildErr -PassThru -NoNewWindow -Wait
if ($gradleProc.ExitCode -ne 0) {
    Log "ERROR: gradle build failed (exit $($gradleProc.ExitCode), see $buildErr)"
    Get-Content $buildErr -ErrorAction SilentlyContinue | Select-Object -First 20 | ForEach-Object { Log "  $_" }
    exit 2
}
$classpathFile = Join-Path $RepoRoot ".e2e-artifacts/novalink-core.classpath.txt"
if (-not (Test-Path $classpathFile)) {
    Log "ERROR: classpath file not written: $classpathFile"
    exit 2
}
$fatJar = Get-ChildItem (Join-Path $RepoRoot "StarLink/core/build/libs/*-all.jar") -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $fatJar) {
    $fatJar = Get-ChildItem (Join-Path $RepoRoot "StarLink/core/build/libs/*-fat.jar") -ErrorAction SilentlyContinue | Select-Object -First 1
}
if ($fatJar) { Log "  using fat jar: $($fatJar.FullName)" } else { Log "  using classpath: $classpathFile" }

# ---------------------------------------------------------------------------
# 2. Install the NovaChat endstone plugin (python package, editable).
# ---------------------------------------------------------------------------
Log "step 2: installing NovaChat endstone plugin into a venv"
# Endstone's BDS host imports python plugins from its plugins/ dir, which
# contains a plugin.toml pointing at a python entry point (e.g.
# `main = "novachat_endstone.plugin:NovaChatPlugin"`). For that import to
# resolve, the `novachat_endstone` package must be importable by the python
# interpreter that runs inside Endstone. The cleanest way is to install it
# editable into a venv that Endstone's launcher uses.
# This script assumes Endstone is provisioned separately (pip install endstone
# or a release bundle) -- it only ensures OUR plugin is importable.
$endstoneVenv = Join-Path $RunsDir "endstone-venv"
if (-not (Test-Path (Join-Path $endstoneVenv "Scripts/python.exe"))) {
    & $pythonExe -m venv $endstoneVenv
    if ($LASTEXITCODE -ne 0) { Log "ERROR: venv creation failed"; exit 2 }
}
$venvPy = Join-Path $endstoneVenv "Scripts/python.exe"
# Install endstone (provides the BDS host) + the NovaChat plugin (editable).
& $venvPy -m pip install --upgrade pip 1>$buildOut 2>$buildErr
& $venvPy -m pip install "endstone>=0.5.0" 1>>$buildOut 2>>$buildErr
if ($LASTEXITCODE -ne 0) { Log "ERROR: pip install endstone failed (see $buildErr)"; exit 2 }
& $venvPy -m pip install -e $endstoneSrc 1>>$buildOut 2>>$buildErr
if ($LASTEXITCODE -ne 0) { Log "ERROR: pip install novachat_endstone failed (see $buildErr)"; exit 2 }
Log "  endstone venv: $endstoneVenv"

# Locate the endstone launcher entry point. Endstone ships a console script
# `endstone` in the venv's Scripts/ that launches BDS with the plugin host.
$endstoneExe = Join-Path $endstoneVenv "Scripts/endstone.exe"
if (-not (Test-Path $endstoneExe)) {
    # Fallback: invoke the python module.
    $endstoneExe = $venvPy
    $endstoneModule = "-m endstone"
} else {
    $endstoneModule = $null
}
Log "  endstone launcher: $endstoneExe"

# ---------------------------------------------------------------------------
# 3. Fetch BDS + set up the Endstone run directory.
# ---------------------------------------------------------------------------
Log "step 3: fetching Bedrock Dedicated Server (BDS)"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $binDir "fetch-server.ps1") -Name "bds" -DistDir $DistDir
if ($LASTEXITCODE -ne 0) { Log "ERROR: fetch-server failed for bds"; exit 2 }
# The BDS zip lands in $DistDir. Unpack it into the run directory.
$bdsZip = Get-ChildItem (Join-Path $DistDir "bedrock-server*.zip") -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $bdsZip) {
    Log "ERROR: BDS zip not found under $DistDir after fetch"
    exit 2
}
$serverDir = Join-Path $RunsDir "bds"
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
Expand-Archive -Path $bdsZip.FullName -DestinationPath $serverDir -Force
Log "  BDS unpacked: $serverDir"

# Bedrock server.properties. xbox-auth=off + online-mode=false for offline E2E.
$serverProps = Join-Path $serverDir "server.properties"
@"
server-name=NovaChat-Endstone-E2E
server-port=$McPort
server-ip=127.0.0.1
view-distance=4
white-list=off
max-players=5
gamemode=1
force-gamemode=on
hardcore=off
pvp=off
difficulty=0
level-name=world
level-seed=
level-type=flat
allow-flight=on
spawn-animals=off
spawn-mobs=off
announce-player-achievements=off
spawn-protection=0
auto-save=off
xbox-auth=off
"@ | Set-Content -Path $serverProps

# Install the NovaChat endstone plugin into BDS's plugins/ dir. Endstone's
# plugin loader scans plugins/ for subdirs containing plugin.toml.
$pluginsDir = Join-Path $serverDir "plugins"
New-Item -ItemType Directory -Path $pluginsDir -Force | Out-Null
$pluginDir = Join-Path $pluginsDir "NovaChat"
New-Item -ItemType Directory -Path $pluginDir -Force | Out-Null
Copy-Item (Join-Path $endstoneSrc "plugin.toml") -Destination $pluginDir -Force
# The plugin source is installed editable in the venv, so no src copy is needed
# here -- Endstone imports `novachat_endstone` from the venv's site-packages.

# Generate backend credentials early so the plugin config can reference them.
$secretKey = New-RandomHex 32
$clientPw  = New-RandomHex 16

# Write the plugin's backend config (plugins/NovaChat/config.yml). Endstone's
# ConfigManager reads from the plugin data folder.
# CRITICAL: the username here MUST match clients[].username in novalink.yml
# (the template uses "E2E_Client"), or the backend rejects with NC-401.
$pluginConfig = Join-Path $pluginDir "config.yml"
@"
backend:
  host: "127.0.0.1"
  port: $NovaPort
  username: "E2E_Client"
  password: "$clientPw"
  reconnect-delay: 5
chat:
  replace_vanilla: false
  default_channel: "local"
debug: false
"@ | Set-Content -Path $pluginConfig

# ---------------------------------------------------------------------------
# 4. Generate backend config + start backend.
# ---------------------------------------------------------------------------
Log "step 4: generating backend config + starting NovaLink backend"
$novaDir = Join-Path $RunsDir "novalink"
New-Item -ItemType Directory -Path $novaDir -Force | Out-Null
$novaYml = Join-Path $novaDir "novalink.yml"
$tpl = Get-Content (Join-Path $confDir "novalink.template.yml") -Raw
$tpl = $tpl.Replace('{{NOVA_PORT}}', $NovaPort).Replace('{{NOVA_WS_PORT}}', $NovaWsPort).Replace('{{SECRET_KEY}}', $secretKey).Replace('{{CLIENT_PASSWORD}}', $clientPw)
$tpl | Set-Content -Path $novaYml -NoNewline

$novaStdout = Join-Path $novaDir "stdout.log"
$novaStderr = Join-Path $novaDir "stderr.log"
$novaPidFile = Join-Path $novaDir "backend.pid"
if ($fatJar) {
    $novaArgs = @("-Xmx512m", "-jar", $fatJar.FullName, $novaYml)
} else {
    $cp = (Get-Content $classpathFile -Raw).Trim()
    $novaArgs = @("-Xmx512m", "-cp", $cp, "com.nova.link.NovaLinkMain", $novaYml)
}
$novaProc = Start-Process -FilePath $java -ArgumentList $novaArgs `
    -WorkingDirectory $novaDir -RedirectStandardOutput $novaStdout -RedirectStandardError $novaStderr -PassThru -NoNewWindow
Track-Pid $novaProc.Id
$novaProc.Id | Set-Content $novaPidFile
Log "  backend pid=$($novaProc.Id) on port $NovaPort"

$ready = $false
$readyPattern = 'NovaLink.*started|NovaProtocol.*listening|WebSocket.*listening|Backend.*started'
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
if (-not $ready) { Log "WARN: backend readiness not confirmed in logs, proceeding (Endstone will retry connect)" }

# ---------------------------------------------------------------------------
# 5. Start BDS+Endstone + wait for "Done" / plugin enable.
# ---------------------------------------------------------------------------
Log "step 5: starting BDS+Endstone on port $McPort"
$bdsStdout = Join-Path $serverDir "bds.stdout.log"
$bdsStderr = Join-Path $serverDir "bds.stderr.log"
$bdsPidFile = Join-Path $serverDir "bds.pid"
if ($endstoneModule) {
    $bdsArgs = @($endstoneModule)
} else {
    $bdsArgs = @()
}
$endstoneProc = Start-Process -FilePath $endstoneExe -ArgumentList $bdsArgs `
    -WorkingDirectory $serverDir -RedirectStandardOutput $bdsStdout -RedirectStandardError $bdsStderr -PassThru -NoNewWindow
Track-Pid $endstoneProc.Id
$endstoneProc.Id | Set-Content $bdsPidFile
Log "  endstone pid=$($endstoneProc.Id)"

# BDS readiness: "Done" or "Server started" + plugin enable marker.
$serverReady = $false
for ($i = 0; $i -lt ($ServerReadySec / 5); $i++) {
    Start-Sleep -Seconds 5
    if ($endstoneProc.HasExited) {
        Log "ERROR: BDS/Endstone exited early; stderr:"; Get-Content $bdsStderr -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Log "  $_" }
        Teardown-All; exit 1
    }
    if (Test-Path $bdsStdout) {
        $logContent = Get-Content $bdsStdout -Raw -ErrorAction SilentlyContinue
        # BDS prints "Server started." on completion; Endstone logs the plugin
        # enable line from NovaChatPlugin.on_enable.
        if ($logContent -match 'Server started\.|Done \(') { $serverReady = $true; break }
    }
}
if (-not $serverReady) {
    Log "ERROR: BDS/Endstone did not reach ready within ${ServerReadySec}s"
    Get-Content $bdsStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    Teardown-All; exit 1
}
Log "  BDS/Endstone ready"

# Confirm plugin enable + backend auth (best-effort, not a hard gate -- the bot
# assertions are the real gate).
if (Test-Path $bdsStdout) {
    $logContent = Get-Content $bdsStdout -Raw -ErrorAction SilentlyContinue
    if ($logContent -match 'NovaChat plugin enabled successfully!') { Log "  plugin enabled (confirmed)" }
    if ($logContent -match 'Successfully connected and authenticated!|Connected to NovaLink backend') { Log "  backend auth (confirmed)" }
    if ($logContent -match 'Failed to connect to NovaLink backend') { Log "  WARN: plugin reported backend connect failure" }
}

# ---------------------------------------------------------------------------
# 6. Run the bedrock bot.
# ---------------------------------------------------------------------------
Log "step 6: installing + running the bedrock-protocol bot"
if (-not (Test-Path (Join-Path $botDir "node_modules/bedrock-protocol"))) {
    Push-Location $botDir
    try { npm install --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { Log "ERROR: npm install failed"; Teardown-All; exit 2 } }
    finally { Pop-Location }
}
$resultsFile = Join-Path $RunsDir "bot-results.json"
$env:SERVER_HOST = "127.0.0.1"
$env:SERVER_PORT = "$McPort"
$env:BOT_NAME = "E2E_Bot_Endstone"
$env:PLATFORM = "endstone"
$env:MC_VERSION = "1.26.30"
$env:BACKEND_CHAT_PHRASE = "hello from e2e bot"
$env:RESULTS_FILE = $resultsFile
$env:TIMEOUT_MS = "$($BotTimeoutSec * 1000)"
$botStdout = Join-Path $RunsDir "bot.stdout.log"
$botStderr = Join-Path $RunsDir "bot.stderr.log"
$botProc = Start-Process -FilePath "node" -ArgumentList @("run-e2e-bedrock.js") -WorkingDirectory $botDir `
    -RedirectStandardOutput $botStdout -RedirectStandardError $botStderr -PassThru -NoNewWindow
Track-Pid $botProc.Id
$botProc.WaitForExit($BotTimeoutSec * 1000) | Out-Null
if (-not $botProc.HasExited) { Stop-Pid $botProc.Id }
Log "  bot exit code=$($botProc.ExitCode)"

# ---------------------------------------------------------------------------
# 7. Assert results.json.
# ---------------------------------------------------------------------------
Log "step 7: asserting L1 results"
$pass = $false
if (Test-Path $resultsFile) {
    $results = Get-Content $resultsFile -Raw | ConvertFrom-Json
    $msgs = $results.received | ForEach-Object { $_.text }
    $joinOk   = $msgs -match 'joined|已加入|join'
    $chatOk   = ($msgs | Where-Object { $_ -match 'hello from e2e bot' }).Count -ge 1
    $noFatal  = -not ($msgs -match 'FATAL|Exception.*NovaLink')
    Log ("  join={0} chat={1} noFatal={2}" -f $joinOk, $chatOk, $noFatal)
    $pass = $joinOk -and $chatOk -and $noFatal
} else {
    Log "ERROR: results.json not written: $resultsFile"
}

# ---------------------------------------------------------------------------
# 8. Teardown.
# ---------------------------------------------------------------------------
Teardown-All
if ($pass) {
    Log "RESULT: L1 PASS (endstone/bds)"
    exit 0
} else {
    Log "RESULT: L1 FAIL (endstone/bds) -- dumping bot log tail:"
    Get-Content $botStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    exit 1
}
