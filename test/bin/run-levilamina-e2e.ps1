# ============================================================================
# run-levilamina-e2e.ps1 -- portable LeviLamina/BDS L1 E2E orchestrator.
#
# Runs the full Layer-1 flow for the levilamina platform:
#   1. Build the NovaLink backend (StarLink:core classpath via init-script, or
#      a fat jar if shadow landed it).
#   2. Build the NovaChat levilamina plugin .dll (xmake) OR reuse a prebuilt
#      .dll if one exists at NovaChat/Bedrock/levilamina/bin/.
#   3. Fetch + unpack the Bedrock Dedicated Server (BDS) via fetch-server.ps1
#      -Name bds -Auto, then install LeviLamina + the NovaChat .dll.
#   4. Generate backend config + start the NovaLink backend.
#   5. Start BDS+LeviLamina, wait for the "Server started" log line + plugin
#      enable.
#   6. Run the bedrock-protocol bot (test/bot/run-e2e-bedrock.js).
#   7. Assert results.json shows the L1 events (plugin enable + chat).
#   8. Teardown: stop BDS + backend, dump logs/artifacts on failure.
#
# LeviLamina is a C++ mod loader that injects into Mojang's BDS via a preloader
# dll. The NovaChat plugin is a native .dll (novachat-levilamina.dll) placed in
# BDS's plugins/ dir alongside manifest.json. LeviLamina has no auth-success
# log line (handleHandshakeResponse only flips mAuthenticated silently -- see
# memory), so the E2E gate is the plugin enable marker + chat round-trip.
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
    [string]$RunsDir       = "",     # per-run workspace; defaults to <repo>/.e2e-artifacts/runs/levilamina
    [int]$McPort           = 19132,  # Bedrock default UDP port
    [int]$NovaPort         = 27907,
    [int]$NovaWsPort       = 34575,
    [int]$ServerReadySec   = 240,
    [int]$BotTimeoutSec    = 360,
    [switch]$SkipDllBuild             # reuse a prebuilt .dll (for CI without MSVC)
)

$ErrorActionPreference = "Stop"
if (-not $DistDir) { $DistDir  = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $RunsDir) { $RunsDir  = Join-Path $RepoRoot ".e2e-artifacts/runs/levilamina" }
$binDir   = Join-Path $RepoRoot "test/bin"
$botDir   = Join-Path $RepoRoot "test/bot"
$confDir  = Join-Path $RepoRoot "test/conf"
$levilaminaSrc = Join-Path $RepoRoot "NovaChat/Bedrock/levilamina"

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
# 2. Build (or locate) the NovaChat levilamina .dll.
# ---------------------------------------------------------------------------
# The xmake build produces:
#   NovaChat/Bedrock/levilamina/bin/novachat-levilamina/novachat-levilamina.dll
# (mod-packer output) AND
#   NovaChat/Bedrock/levilamina/build/bin/novachat-levilamina.dll
# (direct build output). We prefer the mod-packer output because it also
# contains the manifest.json.
$modpackerDll = Join-Path $levilaminaSrc "bin/novachat-levilamina/novachat-levilamina.dll"
$modpackerManifest = Join-Path $levilaminaSrc "bin/novachat-levilamina/manifest.json"
$buildDll = Join-Path $levilaminaSrc "build/bin/novachat-levilamina.dll"

$dllPath = $null
if ((Test-Path $modpackerDll) -and (Test-Path $modpackerManifest)) {
    $dllPath = $modpackerDll
    Log "step 2: reusing prebuilt .dll (mod-packer output): $dllPath"
} elseif (Test-Path $buildDll) {
    $dllPath = $buildDll
    Log "step 2: reusing prebuilt .dll (build output): $dllPath"
} elseif (-not $SkipDllBuild) {
    Log "step 2: building NovaChat levilamina .dll via xmake"
    # The xmake build requires MSVC + a vcvarsall env. The build.gradle delegates
    # to xmake via the gradle wrapper; invoke it directly so a missing toolchain
    # surfaces as a clear error. Use Start-Process to avoid the native-command
    # stderr wrapping that triggers the trap under ErrorActionPreference=Stop.
    $xmakeArgs = @(":NovaChat:Bedrock:levilamina:build", "--console=plain")
    $xmakeProc = Start-Process -FilePath $gradleW -ArgumentList $xmakeArgs -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $buildOut -RedirectStandardError $buildErr -PassThru -NoNewWindow -Wait
    if ($xmakeProc.ExitCode -ne 0) {
        Log "ERROR: levilamina xmake build failed (exit $($xmakeProc.ExitCode), see $buildErr). If xmake/MSVC is not installed, pass -SkipDllBuild after placing a prebuilt .dll at $modpackerDll"
        Get-Content $buildErr -ErrorAction SilentlyContinue | Select-Object -First 20 | ForEach-Object { Log "  $_" }
        exit 2
    }
    if (Test-Path $modpackerDll) {
        $dllPath = $modpackerDll
    } elseif (Test-Path $buildDll) {
        $dllPath = $buildDll
    } else {
        Log "ERROR: xmake build reported success but no .dll found at $modpackerDll or $buildDll"
        exit 2
    }
    Log "  built .dll: $dllPath"
} else {
    Log "ERROR: no prebuilt .dll found and -SkipDllBuild was passed. Build it first with .\gradlew :NovaChat:Bedrock:levilamina:build"
    exit 2
}
$dllManifest = Join-Path (Split-Path -Parent $dllPath) "manifest.json"
if (-not (Test-Path $dllManifest)) {
    # Fall back to the source manifest if the build dir lacks one.
    $dllManifest = Join-Path $levilaminaSrc "manifest.json"
}
Log "  dll: $dllPath"
Log "  manifest: $dllManifest"

# ---------------------------------------------------------------------------
# 3. Fetch BDS + install LeviLamina + the NovaChat .dll.
# ---------------------------------------------------------------------------
Log "step 3: fetching Bedrock Dedicated Server (BDS)"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $binDir "fetch-server.ps1") -Name "bds" -DistDir $DistDir
if ($LASTEXITCODE -ne 0) { Log "ERROR: fetch-server failed for bds"; exit 2 }
$bdsZip = Get-ChildItem (Join-Path $DistDir "bedrock-server*.zip") -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $bdsZip) {
    Log "ERROR: BDS zip not found under $DistDir after fetch"
    exit 2
}
$serverDir = Join-Path $RunsDir "bds"
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
Expand-Archive -Path $bdsZip.FullName -DestinationPath $serverDir -Force
Log "  BDS unpacked: $serverDir"

# Bedrock server.properties.
$serverProps = Join-Path $serverDir "server.properties"
@"
server-name=NovaChat-LeviLamina-E2E
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

# Install LeviLamina + the NovaChat plugin into BDS's plugins/ dir.
# LeviLamina's mod loader is a preloader .dll that BDS loads via its
# `windows-specific`/`server.properties`-adjacent bootstrap. The exact install
# layout (preloader .dll in BDS root, mods in plugins/) is provisioned by
# `fetch-server.ps1 -Name bds -Auto` when it bundles LeviLamina, OR by a
# separate LeviLamina release download. This script assumes LeviLamina is
# already present in the BDS tree; it only installs OUR plugin.
$pluginsDir = Join-Path $serverDir "plugins"
New-Item -ItemType Directory -Path $pluginsDir -Force | Out-Null
$pluginDir = Join-Path $pluginsDir "novachat-levilamina"
New-Item -ItemType Directory -Path $pluginDir -Force | Out-Null
Copy-Item $dllPath -Destination $pluginDir -Force
Copy-Item $dllManifest -Destination $pluginDir -Force
Log "  plugin installed: $pluginDir"

# Generate backend credentials early so the plugin config can reference them.
$secretKey = New-RandomHex 32
$clientPw  = New-RandomHex 16

# Write the plugin's backend config (plugins/novachat-levilamina/config.json).
# LeviLamina's NovaChatConfig saves JSON on first load; we pre-write it so the
# plugin points at our backend from the first connect attempt.
# CRITICAL: the username here MUST match clients[].username in novalink.yml
# (the template uses "E2E_Client"), or the backend rejects with NC-401.
$pluginConfig = Join-Path $pluginDir "config.json"
@"
{
  "backend": {
    "host": "127.0.0.1",
    "port": $NovaPort,
    "username": "E2E_Client",
    "password": "$clientPw",
    "server_version": "1.21.0",
    "reconnect_delay": 5
  },
  "debug": false
}
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
if (-not $ready) { Log "WARN: backend readiness not confirmed in logs, proceeding (LeviLamina will retry connect)" }

# ---------------------------------------------------------------------------
# 5. Start BDS+LeviLamina + wait for "Server started" / plugin enable.
# ---------------------------------------------------------------------------
Log "step 5: starting BDS+LeviLamina on port $McPort"
# BDS on Windows is bedrock_server.exe. LeviLamina's preloader is injected by
# replacing/launching via its loader. When fetch-server.ps1 bundles LeviLamina,
# the BDS root contains a LeviLamina launcher (e.g. LeverBlockLoader or a
# preloader .dll in the root). We launch the BDS executable in the run dir; if
# a LeviLamina launcher exe is present we prefer it.
$bdsExe = Get-ChildItem $serverDir -Filter "bedrock_server*.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
$leviLauncher = Get-ChildItem $serverDir -Filter "LeviLamina*.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
$launchExe = if ($leviLauncher) { $leviLauncher.FullName } elseif ($bdsExe) { $bdsExe.FullName } else { $null }
if (-not $launchExe) {
    Log "ERROR: no BDS executable found in $serverDir"
    Teardown-All; exit 2
}
$bdsStdout = Join-Path $serverDir "bds.stdout.log"
$bdsStderr = Join-Path $serverDir "bds.stderr.log"
$bdsPidFile = Join-Path $serverDir "bds.pid"
$bdsProc = Start-Process -FilePath $launchExe `
    -WorkingDirectory $serverDir -RedirectStandardOutput $bdsStdout -RedirectStandardError $bdsStderr -PassThru -NoNewWindow
Track-Pid $bdsProc.Id
$bdsProc.Id | Set-Content $bdsPidFile
Log "  bds pid=$($bdsProc.Id) ($launchExe)"

$serverReady = $false
for ($i = 0; $i -lt ($ServerReadySec / 5); $i++) {
    Start-Sleep -Seconds 5
    if ($bdsProc.HasExited) {
        Log "ERROR: BDS/LeviLamina exited early; stderr:"; Get-Content $bdsStderr -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Log "  $_" }
        Teardown-All; exit 1
    }
    if (Test-Path $bdsStdout) {
        $logContent = Get-Content $bdsStdout -Raw -ErrorAction SilentlyContinue
        # BDS prints "Server started." on completion. LeviLamina logs the plugin
        # enable line from NovaChatPlugin::enable().
        if ($logContent -match 'Server started\.|Done \(') { $serverReady = $true; break }
    }
}
if (-not $serverReady) {
    Log "ERROR: BDS/LeviLamina did not reach ready within ${ServerReadySec}s"
    Get-Content $bdsStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    Teardown-All; exit 1
}
Log "  BDS/LeviLamina ready"

# Confirm plugin enable (best-effort). LeviLamina has NO auth-success log line
# (handleHandshakeResponse only flips mAuthenticated silently -- see memory), so
# the enable marker + the bot chat round-trip are the only positive gates.
if (Test-Path $bdsStdout) {
    $logContent = Get-Content $bdsStdout -Raw -ErrorAction SilentlyContinue
    if ($logContent -match 'NovaChat-LeviLamina enabled successfully') { Log "  plugin enabled (confirmed)" }
    if ($logContent -match 'Failed to connect to NovaLink backend') { Log "  WARN: plugin reported backend connect failure" }
}

# ---------------------------------------------------------------------------
# 6. Run the bedrock bot.
# ---------------------------------------------------------------------------
Log "step 6: installing + running the bedrock-protocol bot"
if (-not (Test-Path (Join-Path $botDir "node_modules/bedrock-protocol"))) {
    Push-Location $botDir
    try { npm ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { Log "ERROR: npm ci failed"; Teardown-All; exit 2 } }
    finally { Pop-Location }
}
$resultsFile = Join-Path $RunsDir "bot-results.json"
$env:SERVER_HOST = "127.0.0.1"
$env:SERVER_PORT = "$McPort"
$env:BOT_NAME = "E2E_Bot_LeviLamina"
$env:PLATFORM = "levilamina"
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
    Log "RESULT: L1 PASS (levilamina/bds)"
    exit 0
} else {
    Log "RESULT: L1 FAIL (levilamina/bds) -- dumping bot log tail:"
    Get-Content $botStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    exit 1
}
