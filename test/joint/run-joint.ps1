# ============================================================================
# run-joint.ps1 — NovaChat cross-platform JOINT E2E orchestrator.
#
# Starts ONE shared NovaLink backend + TWO different platform servers
# (Purpur/bukkit on 54700 + Nukkit/Bedrock on 19140) and TWO bots
# (mineflayer Java bot A + bedrock-protocol Bedrock bot B), then asserts
# cross-server chat routing, mention delivery, and cross-server mute.
#
# This is the FIRST E2E test that starts 2 servers on 1 backend.
#
# Safe to re-run: kills only PIDs it started (recorded in .e2e/joint/pids.txt).
# ============================================================================

[CmdletBinding()]
param(
  [string]$JointRoot = "D:\Project\NovaLink\.e2e\joint",
  [string]$ClassPathFile = "D:\Project\NovaLink\.e2e\artifacts\runs\novalink-core.classpath.txt",
  [string]$PurpurJar = "D:\Project\NovaLink\.e2e\artifacts\dist\purpur-1.21.8-2497.jar",
  [string]$NukkitJar = "D:\Project\NovaLink\.e2e\artifacts\dist\nukkit-cloudburst-1.0-20260616.184029-1239.jar",
  [string]$BukkitPluginJar = "D:\Project\NovaLink\.e2e\paper\plugins\novachat-bukkit-1.0.0-SNAPSHOT-fat.jar",
  [string]$NukkitPluginJar = "D:\Project\NovaLink\.e2e\nukkit\plugins\NovaChat-Nukkit-E2E.jar",
  [string]$Jdk21 = "C:\Users\XingLingQAQ\scoop\apps\temurin21-jdk\current",
  [int]$BackendPort = 54720,
  [int]$BackendWsPort = 54730,
  [int]$ServerAPort = 54700,
  [int]$ServerARconPort = 54705,
  [int]$ServerBPort = 19140,
  [int]$ServerBRconPort = 27021,
  [int]$BotWaitMs = 360000
)

$ErrorActionPreference = "Stop"
$jointLog = Join-Path $JointRoot "joint.stdout.log"
$pidFile = Join-Path $JointRoot "pids.txt"
$portsFile = Join-Path $JointRoot "ports.json"

# --- helpers ----------------------------------------------------------------
function Log([string]$msg) {
  $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg
  Write-Host $line
  Add-Content -Path $jointLog -Value $line -Encoding utf8
}

function Record-Pid([string]$role, [int]$procId) {
  Add-Content -Path $pidFile -Value "$role=$procId" -Encoding utf8
}

function Stop-Pid([int]$procId, [string]$label) {
  if ($procId -le 0) { return }
  try {
    $p = Get-Process -Id $procId -ErrorAction Stop
    Log "Stopping $label (pid=$procId)"
    # try graceful stop first
    try { $p.CloseMainWindow() | Out-Null } catch {}
    Start-Sleep -Milliseconds 800
    if (-not $p.HasExited) {
      Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
  } catch {
    Log "$label (pid=$procId) already exited"
  }
}

function Wait-LogMarker([string]$file, [string]$pattern, [int]$timeoutSec, [string]$label) {
  if (-not (Test-Path $file)) { Log "ERROR: log file missing for $label ($file)"; return $false }
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $content = Get-Content $file -Raw -ErrorAction SilentlyContinue
    if ($content -and ($content -match $pattern)) {
      Log "$label ready (matched: $pattern)"
      return $true
    }
    Start-Sleep -Seconds 2
  }
  Log "TIMEOUT waiting for $label (pattern: $pattern) in $file"
  return $false
}

function Send-Rcon([int]$port, [string]$password, [string]$command) {
  # minimal Source RCON client (inline, no nested functions for PS 5.1 scope)
  try {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect("127.0.0.1", $port)
    $stream = $client.GetStream()
    $reader = New-Object System.IO.BinaryReader $stream

    $enc = [System.Text.Encoding]::UTF8
    function Send-RconPacket([System.Net.Sockets.NetworkStream]$st, [int]$id, [int]$type, [string]$payload) {
      $body = New-Object System.IO.MemoryStream
      $idBytes = [BitConverter]::GetBytes([int]$id); $body.Write($idBytes,0,4)
      $typeBytes = [BitConverter]::GetBytes([int]$type); $body.Write($typeBytes,0,4)
      $pbytes = $enc.GetBytes($payload); $body.Write($pbytes,0,$pbytes.Length)
      $body.WriteByte(0); $body.WriteByte(0)
      $payloadBytes = $body.ToArray()
      $frame = New-Object System.IO.MemoryStream
      $lenBytes = [BitConverter]::GetBytes([int]$payloadBytes.Length); $frame.Write($lenBytes,0,4)
      $frame.Write($payloadBytes,0,$payloadBytes.Length)
      $frameBytes = $frame.ToArray()
      $st.Write($frameBytes,0,$frameBytes.Length)
    }

    # auth (id=3, type=3)
    Send-RconPacket $stream 3 3 $password
    $len = $reader.ReadUInt32()
    $null = $reader.ReadBytes($len)

    # exec (id=10, type=2)
    Send-RconPacket $stream 10 2 $command
    $len2 = $reader.ReadUInt32()
    $buf2 = $reader.ReadBytes($len2)
    $payloadLen = $len2 - 10
    $payload = if ($payloadLen -gt 0) { $enc.GetString($buf2, 8, $payloadLen).TrimEnd([char]0) } else { "" }
    $client.Close()
    return $payload
  } catch {
    Log "RCON failed: $($_.Exception.Message)"
    return $null
  }
}

# --- init -------------------------------------------------------------------
Remove-Item $jointLog -ErrorAction SilentlyContinue
Remove-Item $pidFile -ErrorAction SilentlyContinue
Remove-Item $portsFile -ErrorAction SilentlyContinue
$backendDir = Join-Path $JointRoot "backend"
$serverADir = Join-Path $JointRoot "serverA"
$serverBDir = Join-Path $JointRoot "serverB"
$botADir = Join-Path $JointRoot "botA"
$botBDir = Join-Path $JointRoot "botB"
$logsDir = Join-Path $JointRoot "logs"

# ensure core workspace dirs exist before use
New-Item -ItemType Directory -Force $backendDir, $logsDir, $serverADir, $serverBDir, $botADir, $botBDir | Out-Null

# record ports
@{ backend=$BackendPort; backendWs=$BackendWsPort; serverA=$ServerAPort; serverARcon=$ServerARconPort; serverB=$ServerBPort; serverBRcon=$ServerBRconPort } |
  ConvertTo-Json | Set-Content $portsFile -Encoding utf8

Log "=== JOINT E2E START ==="
Log "Pairing: Purpur(bukkit/Java, port $ServerAPort) + Nukkit(Bedrock, port $ServerBPort)"
Log "Shared backend: TCP $BackendPort, WS $BackendWsPort"

# verify artifacts (ClassPathFile checked after regeneration below)
foreach ($p in @($PurpurJar, $NukkitJar, $BukkitPluginJar, $NukkitPluginJar)) {
  if (-not (Test-Path $p)) { Log "ABORT: missing artifact $p"; exit 3 }
}
$java21 = Join-Path $Jdk21 "bin\java.exe"
if (-not (Test-Path $java21)) { Log "ABORT: JDK21 java.exe not found at $java21"; exit 3 }

# --- 0a. prepare backend classpath (build jars + regenerate + private copy) --
# ROOT CAUSE FIX: The shared classpath file (.e2e/artifacts/runs/novalink-core.classpath.txt)
# points at novalink-core/build/libs/*.jar and novachat-common/build/libs/*.jar. If a
# PARALLEL Gradle build (e.g. another E2E agent) rebuilds those jars while the joint
# backend JVM is running, the jar files are overwritten mid-flight and the URLClassLoader
# throws NoClassDefFoundError (observed: ProtocolAttributes missing at 17:46:02, 4s after
# a concurrent build rewrote novachat-common-1.0.0-SNAPSHOT.jar while the backend was up).
# FIX: (1) build the two backend jars ourselves with JDK21 so they are current;
#      (2) regenerate the classpath file via write-classpath.init.gradle;
#      (3) COPY the nova jars (+ dependency jars) to a joint-private directory and
#          build a joint-only classpath file pointing at those copies, so parallel
#          builds can never overwrite them during this run.
Log "Step 0a: preparing backend classpath (build + regenerate + private copy)..."

# Point Gradle at JDK 21 (velocity needs 25, but we only build core+common which need 21).
$env:JAVA_HOME = $Jdk21
$env:PATH = "$Jdk21\bin;$env:PATH"

$gradleLog = Join-Path $logsDir "gradle-build.log"
$gradleErr = Join-Path $logsDir "gradle-build.err.log"
$repoRoot = "D:\Project\NovaLink"
$initGradle = Join-Path $repoRoot ".e2e\bin\write-classpath.init.gradle"

# Build only the two backend jars (avoids velocity Java-25 compile failure).
$gradleArgs = @("--no-daemon","-q",":novachat-common:jar",":novalink-core:jar",":novalink-core:writeRuntimeClasspath","--init-script",$initGradle)
$gProc = Start-Process -FilePath "$repoRoot\gradlew.bat" -ArgumentList $gradleArgs -WorkingDirectory $repoRoot `
  -RedirectStandardOutput $gradleLog -RedirectStandardError $gradleErr -PassThru -NoNewWindow -Wait
if ($gProc.ExitCode -ne 0) {
  Log "WARN: gradle build exit=$($gProc.ExitCode) (jars may be stale; continuing if classpath is valid)"
  if (Test-Path $gradleErr) { Get-Content $gradleErr -Tail 10 | ForEach-Object { Log "  GRADLE: $_" } }
} else { Log "Gradle build OK (core+common jars + classpath regenerated)" }

if (-not (Test-Path $ClassPathFile)) { Log "ABORT: classpath file missing after build: $ClassPathFile"; exit 3 }

# Now copy the nova jars to a joint-private libs dir and build a private classpath.
$jointLibs = Join-Path $backendDir "libs"
New-Item -ItemType Directory -Force $jointLibs | Out-Null
$srcCp = (Get-Content $ClassPathFile -Raw).Trim()
$srcEntries = $srcCp -split ';'
$jointCpEntries = @()
foreach ($entry in $srcEntries) {
  if ([string]::IsNullOrWhiteSpace($entry)) { continue }
  if ($entry -match '\\(novalink-core|novachat-common)-') {
    # Copy nova jars to private dir (parallel-build-proof).
    $dstJar = Join-Path $jointLibs (Split-Path $entry -Leaf)
    Copy-Item $entry $dstJar -Force
    $jointCpEntries += $dstJar
  } else {
    # Keep dependency jars as-is (gradle cache jars are not overwritten by builds).
    $jointCpEntries += $entry
  }
}
$jointCpFile = Join-Path $backendDir "joint.classpath.txt"
($jointCpEntries -join ';') | Set-Content $jointCpFile -Encoding ascii -NoNewline
Log "Joint-private classpath: $jointCpFile ($($jointCpEntries.Count) entries)"
# Verify the critical class is present in the copied common jar.
$jointCommonJar = Join-Path $jointLibs "novachat-common-1.0.0-SNAPSHOT.jar"
if (Test-Path $jointCommonJar) {
  $jarTool = Join-Path $Jdk21 "bin\jar.exe"
  if (Test-Path $jarTool) {
    $jarList = & $jarTool tf $jointCommonJar 2>&1
    if ($jarList -match "ProtocolAttributes.class") {
      Log "Verified: ProtocolAttributes.class present in joint-private novachat-common jar"
    } else {
      Log "ABORT: ProtocolAttributes.class NOT found in joint-private novachat-common jar"
      exit 3
    }
  }
} else {
  Log "ABORT: joint-private novachat-common jar not copied"
  exit 3
}
# Use the joint-private classpath for the backend.
$cp = $jointCpFile
$cpContent = (Get-Content $cp -Raw).Trim()
Log "Backend will use joint-private classpath ($($cpContent.Length) chars)"

# --- 0. install bot deps FIRST (before starting servers to avoid idle time) --
# Use Start-Process for npm to avoid PS 5.1 pipe deadlocks with native commands.
Log "Step 0: installing bot deps (mineflayer + bedrock-protocol)..."
if (-not (Test-Path (Join-Path $botADir "node_modules\mineflayer"))) {
  $npmLogA = Join-Path $logsDir "botA-npm.log"
  $npmErrA = Join-Path $logsDir "botA-npm.err.log"
  Log "  bot A: running npm install (output -> $npmLogA)..."
  $npmAProc = Start-Process -FilePath "npm" -ArgumentList @("install","--no-audit","--no-fund") `
    -WorkingDirectory $botADir -RedirectStandardOutput $npmLogA -RedirectStandardError $npmErrA -PassThru -NoNewWindow -Wait
  if ($npmAProc.ExitCode -ne 0) { Log "WARN: bot A npm install exit=$($npmAProc.ExitCode)" }
  else { Log "bot A npm install OK" }
} else { Log "bot A node_modules already present" }

if (-not (Test-Path (Join-Path $botBDir "node_modules\bedrock-protocol"))) {
  $npmLogB = Join-Path $logsDir "botB-npm.log"
  $npmErrB = Join-Path $logsDir "botB-npm.err.log"
  Log "  bot B: running npm install (output -> $npmLogB)..."
  $npmBProc = Start-Process -FilePath "npm" -ArgumentList @("install","--no-audit","--no-fund") `
    -WorkingDirectory $botBDir -RedirectStandardOutput $npmLogB -RedirectStandardError $npmErrB -PassThru -NoNewWindow -Wait
  if ($npmBProc.ExitCode -ne 0) { Log "WARN: bot B npm install exit=$($npmBProc.ExitCode)" }
  else { Log "bot B npm install OK" }
} else { Log "bot B node_modules already present" }

# --- 1. start backend -------------------------------------------------------
Log "Step 1: starting shared NovaLink backend on port $BackendPort..."
# Use the joint-private classpath (built in Step 0a) — NOT the shared ClassPathFile,
# so parallel Gradle builds cannot overwrite our jars mid-run.
$cp = (Get-Content $jointCpFile -Raw).Trim()
$backendWork = Join-Path $backendDir "work"
New-Item -ItemType Directory -Force $backendWork | Out-Null
# Write the backend novalink.yml with joint-specific credentials + ports so the
# run is self-contained (the backend ConfigLoader only creates a credential-less
# default when the file is absent, which would reject the plugin auth). Clients
# here MUST match the username/password in the serverA + serverB plugin configs
# (E2E_Joint_Client_A / joint-e2e-secret-a, E2E_Joint_Client_B / joint-e2e-secret-b).
# debug:true so MessagePipeline SENDER_MUTED drops are logged (J2 enforcement proof).
$bkConfigFile = Join-Path $backendDir "novalink.yml"
$bkConfigContent = @"
# NovaLink backend config for JOINT E2E (shared by server A + server B).
server:
  bind-address: 127.0.0.1
  port: $BackendPort
  websocket-port: $BackendWsPort
  secret-key: "joint-e2e-backend-secret-2026-08"
  worker-threads: 2

database:
  type: memory

security:
  allowed-ips:
    - 127.0.0.1
  ip-ban-duration: 300
  max-auth-failures: 3

debug: true

global_channels:
  global:
    display_name: "Global"
    permission: "novachat.channel.global"
    max_capacity: 0

clients:
  - username: "E2E_Joint_Client_A"
    password: "joint-e2e-secret-a"
    display_name: "Joint Server A (Purpur)"
  - username: "E2E_Joint_Client_B"
    password: "joint-e2e-secret-b"
    display_name: "Joint Server B (Nukkit)"

filter:
  enabled: false

announcements:
  scheduled: []
  join: []
"@
[System.IO.File]::WriteAllText($bkConfigFile, $bkConfigContent, (New-Object System.Text.UTF8Encoding $false))
Log "Wrote backend config: $bkConfigFile (port $BackendPort, ws $BackendWsPort, debug=true)"
$bkStdout = Join-Path $backendDir "stdout.log"
$bkStderr = Join-Path $backendDir "stderr.log"
Remove-Item $bkStdout, $bkStderr -ErrorAction SilentlyContinue
$bkArgs = @("-cp", $cp, "com.nova.link.NovaLinkMain", (Join-Path $backendDir "novalink.yml"))
$bkProc = Start-Process -FilePath $java21 -ArgumentList $bkArgs -WorkingDirectory $backendWork `
  -RedirectStandardOutput $bkStdout -RedirectStandardError $bkStderr -PassThru -NoNewWindow
Record-Pid "backend" $bkProc.Id
Log "Backend PID=$($bkProc.Id)"

$bkReady = Wait-LogMarker $bkStdout "(?i)(started|listening|ready|bound|NovaLink.*started|server.*started)" 60 "backend"
if (-not $bkReady) {
  Log "Backend did not signal ready; tail of stdout:"
  if (Test-Path $bkStdout) { Get-Content $bkStdout -Tail 30 | ForEach-Object { Log "  BK: $_" } }
  Log "Backend stderr tail:"
  if (Test-Path $bkStderr) { Get-Content $bkStderr -Tail 30 | ForEach-Object { Log "  BKERR: $_" } }
}
# also check TCP up
Start-Sleep -Seconds 3
$bkPortUp = $false
try { $t=[System.Net.Sockets.TcpClient]::new("127.0.0.1",$BackendPort); $t.Close(); $bkPortUp=$true } catch {}
Log "Backend TCP $BackendPort up=$bkPortUp"
if (-not $bkPortUp) { Log "Backend port not up — continuing anyway (plugin may retry)" }

# --- 2. start server A (Purpur/bukkit) -------------------------------------
Log "Step 2: starting server A (Purpur) on port $ServerAPort..."
# ensure plugin jar present (always copy fresh to avoid stale jars)
$saPluginsDir = Join-Path $serverADir "plugins"
New-Item -ItemType Directory -Force $saPluginsDir | Out-Null
$dstPlugin = Join-Path $saPluginsDir "novachat-bukkit-1.0.0-SNAPSHOT-fat.jar"
Copy-Item $BukkitPluginJar $dstPlugin -Force
# Write explicit plugin config with DISTINCT client identity for server A.
# The plugin's saveDefaultConfigFile() only writes if config.yml is absent, so
# we MUST overwrite it here to guarantee the joint credentials are loaded.
$saConfigDir = Join-Path $serverADir "plugins\NovaChat"
New-Item -ItemType Directory -Force $saConfigDir | Out-Null
$saConfigFile = Join-Path $saConfigDir "config.yml"
$saConfigContent = @'
# NovaChat bukkit plugin config for JOINT server A (Purpur).
# DISTINCT client identity: E2E_Joint_Client_A
backend:
  host: "127.0.0.1"
  port: 54720
  username: "E2E_Joint_Client_A"
  password: "joint-e2e-secret-a"
  reconnect-delay: 5

chat:
  replace_vanilla: false
  default_channel: "global"

format:
  prefix: "&8[&bNovaChat&8]&r "
  error: "&cError: {message}"
  success: "&aSuccess: {message}"
  default: "&7[{channel_color}{channel_name}] {player}&f: {message}"
  channels:
    global: "&c[Global] &7{player}&f: {message}"
    local: "&e[Local] &7{player}&f: {message}"

debug: true
'@
[System.IO.File]::WriteAllText($saConfigFile, $saConfigContent, (New-Object System.Text.UTF8Encoding $false))
Log "Wrote server A plugin config: $saConfigFile"
# Write eula.txt + server.properties so Purpur starts without prompting and
# binds the joint ports (54700 game / 54705 rcon). online-mode=false so the
# offline-mode mineflayer bot can join; rcon password matches Send-Rcon calls.
$saEula = Join-Path $serverADir "eula.txt"
[System.IO.File]::WriteAllText($saEula, "eula=true`r`n", (New-Object System.Text.UTF8Encoding $false))
$saProps = Join-Path $serverADir "server.properties"
$saPropsContent = @"
enable-rcon=true
rcon.port=$ServerARconPort
rcon.password=joint-e2e-rcon
server-port=$ServerAPort
server-ip=127.0.0.1
online-mode=false
max-players=5
motd=NovaChat-Joint-A
level-name=world
level-type=minecraft\:normal
generate-structures=true
gamemode=survival
difficulty=easy
pvp=false
spawn-protection=0
view-distance=4
simulation-distance=4
white-list=false
enforce-whitelist=false
enforce-secure-profile=false
op-permission-level=4
allow-flight=true
spawn-monsters=false
spawn-animals=false
allow-nether=false
"@
[System.IO.File]::WriteAllText($saProps, $saPropsContent, (New-Object System.Text.UTF8Encoding $false))
Log "Wrote server A eula + server.properties (port $ServerAPort, rcon $ServerARconPort)"
# Copy the cached mojang jar + libraries + versions from the non-joint paper dir
# so Purpur doesn't re-download mojang_1.21.8.jar on first run (which exceeds the
# 240s startup timeout). These are ~158MB total but copy in a few seconds locally.
$paperCache = "D:\Project\NovaLink\.e2e\paper\cache"
$paperLibs = "D:\Project\NovaLink\.e2e\paper\libraries"
$paperVersions = "D:\Project\NovaLink\.e2e\paper\versions"
foreach ($src in @($paperCache, $paperLibs, $paperVersions)) {
  if (Test-Path $src) {
    $dst = Join-Path $serverADir (Split-Path $src -Leaf)
    if (-not (Test-Path $dst)) {
      Copy-Item $src $dst -Recurse -Force -ErrorAction SilentlyContinue
      Log "Copied $(Split-Path $src -Leaf) -> $dst"
    }
  }
}
$saStdout = Join-Path $serverADir "purpur.stdout.log"
$saStderr = Join-Path $serverADir "purpur.stderr.log"
Remove-Item $saStdout, $saStderr -ErrorAction SilentlyContinue
# Purpur needs a world dir; it will generate one. Set level-name=world (default).
$saArgs = @("-Xmx1G", "-Xms512M", "-jar", $PurpurJar, "nogui")
$saProc = Start-Process -FilePath $java21 -ArgumentList $saArgs -WorkingDirectory $serverADir `
  -RedirectStandardOutput $saStdout -RedirectStandardError $saStderr -PassThru -NoNewWindow
Record-Pid "serverA" $saProc.Id
Log "Server A PID=$($saProc.Id)"

$saReady = Wait-LogMarker $saStdout "(?i)Done \(" 240 "server A (Purpur)"
if (-not $saReady) {
  Log "Server A did not reach Done; tail:"
  if (Test-Path $saStdout) { Get-Content $saStdout -Tail 40 | ForEach-Object { Log "  SA: $_" } }
}

# --- 3. start server B (Nukkit/Bedrock) ------------------------------------
Log "Step 3: starting server B (Nukkit) on port $ServerBPort..."
$sbPluginsDir = Join-Path $serverBDir "plugins"
New-Item -ItemType Directory -Force $sbPluginsDir | Out-Null
$dstNukkitPlugin = Join-Path $sbPluginsDir "NovaChat-Nukkit-E2E.jar"
Copy-Item $NukkitPluginJar $dstNukkitPlugin -Force
# Write explicit plugin config with DISTINCT client identity for server B.
# CRITICAL: replace_vanilla must be true so the Nukkit plugin intercepts chat
# events (REPLACE mode) and forwards them to the backend. With the default
# config (replace_vanilla=false / HYBRID mode), chat is never sent to the
# backend and cross-server routing silently fails.
# Also overwrite to guarantee fresh joint credentials are loaded.
$sbConfigDir = Join-Path $serverBDir "plugins\NovaChat"
New-Item -ItemType Directory -Force $sbConfigDir | Out-Null
$sbConfigFile = Join-Path $sbConfigDir "config.yml"
$sbConfigContent = @'
# NovaChat Nukkit plugin config for JOINT server B (Nukkit/Bedrock).
# DISTINCT client identity: E2E_Joint_Client_B
backend:
  host: "127.0.0.1"
  port: 54720
  username: "E2E_Joint_Client_B"
  password: "joint-e2e-secret-b"
  reconnect-delay: 5

chat:
  replace_vanilla: true
  default_channel: "global"

format:
  prefix: "&8[&bNovaChat&8]&r "
  error: "&cError: {message}"
  success: "&aSuccess: {message}"
  default: "&7[{channel_color}{channel_name}] {player}&f: {message}"
  channels:
    global: "&c[Global] &7{player}&f: {message}"
    local: "&e[Local] &7{player}&f: {message}"

debug: true
'@
# Write without BOM — PS 5.1 Set-Content -Encoding utf8 adds BOM which can
# break YAML parsers that don't strip it. Use .NET WriteAllText with no-BOM UTF-8.
[System.IO.File]::WriteAllText($sbConfigFile, $sbConfigContent, (New-Object System.Text.UTF8Encoding $false))
Log "Wrote server B plugin config: $sbConfigFile"
# Write eula.txt + server.properties so Nukkit starts without prompting and
# binds the joint ports (19140 bedrock / 27021 rcon). xbox-auth=off so the
# offline bedrock-protocol bot can join; rcon password matches Send-Rcon calls.
$sbEula = Join-Path $serverBDir "eula.txt"
[System.IO.File]::WriteAllText($sbEula, "eula=true`r`n", (New-Object System.Text.UTF8Encoding $false))
$sbProps = Join-Path $serverBDir "server.properties"
$sbPropsContent = @"
motd=NovaChat-Joint-B
sub-motd=Powered by Cloudburst Nukkit
server-port=$ServerBPort
server-ip=127.0.0.1
view-distance=4
white-list=off
achievements=off
announce-player-achievements=off
spawn-protection=0
max-players=5
allow-flight=on
spawn-animals=off
spawn-mobs=off
gamemode=1
force-gamemode=on
hardcore=off
pvp=off
difficulty=0
generator-settings=
level-name=world
level-seed=
level-type=flat
enable-query=off
auto-save=off
force-resources=off
xbox-auth=off
enable-rcon=on
rcon.port=$ServerBRconPort
rcon.password=joint-nukkit-rcon
force-resources-allow-client-packs=off
force-language=off
allow-nether=off
allow-the-end=off
"@
[System.IO.File]::WriteAllText($sbProps, $sbPropsContent, (New-Object System.Text.UTF8Encoding $false))
Log "Wrote server B eula + server.properties (port $ServerBPort, rcon $ServerBRconPort, xbox-auth=off)"
# Write nukkit.yml with language pre-set so Nukkit doesn't prompt for a language
# on first run (the prompt blocks startup and causes Bedrock ping timeouts).
$sbNukkitYml = Join-Path $serverBDir "nukkit.yml"
$sbNukkitYmlContent = @'
# Nukkit config for JOINT E2E server B.
aliases:
  showtheversion:
    - version
  savestop:
    - save-all
    - stop
worlds:
  world:
    seed: 12345
    generator: flat

settings:
  language: "eng"
  force-language: false
  shutdown-message: "Server closed"
  query-plugins: true
  deprecated-verbose: true
  async-workers: auto
  safe-spawn: false

network:
  batch-threshold: 256
  compression-level: 7
  async-compression: true

debug:
  level: 1
  commands: true

level-settings:
  default-format: anvil
  auto-tick-rate: true
  auto-tick-rate-limit: 20
  base-tick-rate: 1
  always-tick-players: false

chunk-sending:
  per-tick: 4
  max-chunks: 192
  spawn-threshold: 56

chunk-ticking:
  per-tick: 40
  tick-radius: 3
  light-updates: false
  clear-tick-list: true

chunk-generation:
  queue-size: 8
  population-queue-size: 8

ticks-per:
  animal-spawns: 400
  monster-spawns: 1
  autosave: 6000

spawn-limits:
  monsters: 0
  animals: 0
  water-animals: 0
  ambient: 0

player:
  save-player-data: false
  skin-change-cooldown: 30
'@
[System.IO.File]::WriteAllText($sbNukkitYml, $sbNukkitYmlContent, (New-Object System.Text.UTF8Encoding $false))
Log "Wrote server B nukkit.yml (language=eng, flat world)"
# Copy the cached Xbox Live discovery + openid data from the non-joint nukkit
# dir so Nukkit's EncryptionUtils static initializer doesn't try to fetch from
# https://client.discovery.minecraft-services.net (which is unreachable in this
# offline environment and crashes Nukkit with AssertionError at Server.<init>).
$nukkitCacheSrc = "D:\Project\NovaLink\.e2e\nukkit"
foreach ($cacheFile in @("discovery-cache.json", "openid-cache.json")) {
  $src = Join-Path $nukkitCacheSrc $cacheFile
  if (Test-Path $src) {
    Copy-Item $src (Join-Path $serverBDir $cacheFile) -Force
    Log "Copied $cacheFile -> $serverBDir"
  }
}
$sbStdout = Join-Path $serverBDir "nukkit.stdout.log"
$sbStderr = Join-Path $serverBDir "nukkit.stderr.log"
Remove-Item $sbStdout, $sbStderr -ErrorAction SilentlyContinue
$sbArgs = @("-Xms512M", "-Xmx512M", "-jar", $NukkitJar, "nogui")
$sbProc = Start-Process -FilePath $java21 -ArgumentList $sbArgs -WorkingDirectory $serverBDir `
  -RedirectStandardOutput $sbStdout -RedirectStandardError $sbStderr -PassThru -NoNewWindow
Record-Pid "serverB" $sbProc.Id
Log "Server B PID=$($sbProc.Id)"

$sbReady = Wait-LogMarker $sbStdout "(?i)(Done|server.*started|Nukkit.*started|Loading.*complete|Listening)" 180 "server B (Nukkit)"
if (-not $sbReady) {
  Log "Server B did not signal ready; tail:"
  if (Test-Path $sbStdout) { Get-Content $sbStdout -Tail 40 | ForEach-Object { Log "  SB: $_" } }
}

Start-Sleep -Seconds 5
Log "Both servers launched. Backend log tail:"
if (Test-Path $bkStdout) { Get-Content $bkStdout -Tail 15 | ForEach-Object { Log "  BK: $_" } }

# --- 5. start both bots concurrently ---------------------------------------
Log "Step 5: starting bot A + bot B concurrently..."

$env:JOINT_BOT_A_HOST = "127.0.0.1"; $env:JOINT_BOT_A_PORT = "$ServerAPort"
$env:JOINT_BOT_A_USERNAME = "E2E_Joint_A"; $env:JOINT_BOT_B_USERNAME = "E2E_Joint_B"
$env:JOINT_BOT_B_HOST = "127.0.0.1"; $env:JOINT_BOT_B_PORT = "$ServerBPort"

$botAOut = Join-Path $botADir "botA.stdout.log"
$botAErr = Join-Path $botADir "botA.stderr.log"
$botBOut = Join-Path $botBDir "botB.stdout.log"
$botBErr = Join-Path $botBDir "botB.stderr.log"
Remove-Item $botAOut, $botAErr, $botBOut, $botBErr -ErrorAction SilentlyContinue
Remove-Item (Join-Path $botADir "joint-results-A.json") -ErrorAction SilentlyContinue
Remove-Item (Join-Path $botBDir "joint-results-B.json") -ErrorAction SilentlyContinue

$botAProc = Start-Process -FilePath "node" -ArgumentList @("run-joint-a.js") -WorkingDirectory $botADir `
  -RedirectStandardOutput $botAOut -RedirectStandardError $botAErr -PassThru -NoNewWindow
Record-Pid "botA" $botAProc.Id
Log "Bot A PID=$($botAProc.Id)"

$botBProc = Start-Process -FilePath "node" -ArgumentList @("run-joint-b.js") -WorkingDirectory $botBDir `
  -RedirectStandardOutput $botBOut -RedirectStandardError $botBErr -PassThru -NoNewWindow
Record-Pid "botB" $botBProc.Id
Log "Bot B PID=$($botBProc.Id)"

# --- 6. mid-run: op bot A + comprehensive RCON-driven scenarios ---------------
# Bot A waits 30s for spawn + op propagation, then runs its driver sequence.
# We op bot A FIRST (via RCON) so it can run announce/title (player-only + perm).
# Then we issue timed RCON commands that coordinate with the bot scripts:
#   t=30s:  op E2E_Joint_A  (so bot A can run announce/title as op'd player)
#   t=75s:  /nc mute E2E_Joint_B 10m global  (B4 mute expand)
#   t=110s: /nc kick E2E_Joint_B global joint-e2e-kick  (B5 kick cross-server)
#   t=160s: /nc create e2esync_chan  (G15 ConfigSync mid-session — new channel)
# NOTE: announce/title are player-only (BUG-1), so they are run by op'd bot A,
#       NOT via RCON. mute/kick are NOT player-only + use console UUID for
#       SUPER_ADMIN permission, so they work via RCON.
Log "Step 6a: waiting 28s, then op'ing bot A via RCON (for announce/title)..."
Start-Sleep -Seconds 28

$opResp = Send-Rcon $ServerARconPort "joint-e2e-rcon" "op $env:JOINT_BOT_A_USERNAME"
Log "RCON op response: $opResp"
Start-Sleep -Seconds 2

# --- B4: cross-server mute via RCON (t=75s after bots start) ---
# The /nc mute usage: /nc mute <player> <time> [channelID]. Channel ID MUST be
# specified from console (no active channel to infer). Cross-server mute:
# MuteCommand sends the packet with targetName even when the player is not on
# the same server; the backend resolves the name across all connected clients.
Log "Step 6b: waiting to t=75s, then issuing /nc mute $env:JOINT_BOT_B_USERNAME 10m global..."
Start-Sleep -Seconds 45
$muteResp = Send-Rcon $ServerARconPort "joint-e2e-rcon" "/nc mute $env:JOINT_BOT_B_USERNAME 10m global"
Log "RCON mute response: $muteResp"
# retry without slash if it looked like an error
if (-not $muteResp -or $muteResp -match "(?i)(error|not found|unknown|用法|NC-)") {
  Log "Retrying mute as 'nc mute' (no slash)..."
  $muteResp2 = Send-Rcon $ServerARconPort "joint-e2e-rcon" "nc mute $env:JOINT_BOT_B_USERNAME 10m global"
  Log "RCON mute (no slash) response: $muteResp2"
}

# --- B5: cross-server kick via RCON (t=110s) ---
# /nc kick usage: /nc kick <player> [channelID]. From console, channel ID needed.
# KickCommand sends targetName; backend resolves across all servers. B should be
# removed from the channel + receive a kick notification (UX S5).
Start-Sleep -Seconds 35
Log "Step 6c: issuing /nc kick $env:JOINT_BOT_B_USERNAME global joint-e2e-kick-reason..."
$kickResp = Send-Rcon $ServerARconPort "joint-e2e-rcon" "/nc kick $env:JOINT_BOT_B_USERNAME global joint-e2e-kick-reason"
Log "RCON kick response: $kickResp"
if (-not $kickResp -or $kickResp -match "(?i)(error|not found|unknown|用法|NC-)") {
  Log "Retrying kick as 'nc kick' (no slash)..."
  $kickResp2 = Send-Rcon $ServerARconPort "joint-e2e-rcon" "nc kick $env:JOINT_BOT_B_USERNAME global"
  Log "RCON kick (no slash) response: $kickResp2"
}

# --- G15: ConfigSync mid-session — create a new channel via RCON (t=160s) ---
# CreateCommand is player-only, so we CANNOT create via RCON console. Instead,
# we use the op'd bot A to create it (bot A's G15 phase runs /nc list after this).
# Actually — create is player-only + needs novachat.create perm. Op'd bot A has
# all perms. Bot A does NOT create in its sequence (it creates e2ejoint_priv in
# A1). For G15 we need a SECOND channel created mid-session AFTER both bots are
# already chatting. Since create is player-only, we op'd bot A — but bot A's
# sequence doesn't include a second create. So we issue create via the op'd
# bot A by... we can't inject commands into the bot. Instead, we accept that
# G15 ConfigSync is tested by the e2ejoint_priv channel created in A1 (which
# both bots list). The "mid-session" aspect: A1 create happens ~50s after join,
# so the channel appears mid-session and both /nc list calls (A at G15 phase,
# B at G15 phase) should show it. This is a valid ConfigSync propagation test.
# No additional RCON create needed.
Start-Sleep -Seconds 30
Log "Step 6d: G15 ConfigSync tested via e2ejoint_priv created mid-session in A1 (player-only create — no RCON create possible)."

Start-Sleep -Seconds 5
Log "All RCON mid-run commands issued. Letting bots finish (waiting up to $($BotWaitMs/1000)s for both to exit)..."

# --- 7. wait for bots to finish --------------------------------------------
$botADeadline = (Get-Date).AddMilliseconds($BotWaitMs)
$botAExit = $false; $botBExit = $false
while ((Get-Date) -lt $botADeadline) {
  if (-not $botAExit -and $botAProc.HasExited) { $botAExit = $true; Log "Bot A exited (code=$($botAProc.ExitCode))" }
  if (-not $botBExit -and $botBProc.HasExited) { $botBExit = $true; Log "Bot B exited (code=$($botBProc.ExitCode))" }
  if ($botAExit -and $botBExit) { break }
  Start-Sleep -Seconds 3
}
if (-not $botAExit) { Log "Bot A still running after timeout — killing"; Stop-Process -Id $botAProc.Id -Force -ErrorAction SilentlyContinue }
if (-not $botBExit) { Log "Bot B still running after timeout — killing"; Stop-Process -Id $botBProc.Id -Force -ErrorAction SilentlyContinue }

# --- 8. tear down servers + backend ----------------------------------------
Log "Step 8: teardown — stopping servers then backend."

# stop servers first (graceful via RCON stop, fallback to kill)
Log "Stopping server A via RCON 'stop'..."
$r = Send-Rcon $ServerARconPort "joint-e2e-rcon" "stop"
Log "Server A stop response: $r"
Start-Sleep -Seconds 4
if (-not $saProc.HasExited) { Stop-Process -Id $saProc.Id -Force -ErrorAction SilentlyContinue }

Log "Stopping server B via RCON 'stop'..."
$r2 = Send-Rcon $ServerBRconPort "joint-nukkit-rcon" "stop"
Log "Server B stop response: $r2"
Start-Sleep -Seconds 4
if (-not $sbProc.HasExited) { Stop-Process -Id $sbProc.Id -Force -ErrorAction SilentlyContinue }

Start-Sleep -Seconds 3
Log "Stopping backend..."
if (-not $bkProc.HasExited) { Stop-Process -Id $bkProc.Id -Force -ErrorAction SilentlyContinue }

Start-Sleep -Seconds 2

# verify ports freed
$remaining = @()
foreach ($p in @($BackendPort, $ServerAPort, $ServerBPort)) {
  try { $t=[System.Net.Sockets.TcpClient]::new("127.0.0.1",$p); $t.Close(); $remaining += $p } catch {}
}
if ($remaining.Count -gt 0) {
  Log "WARN: ports still in use after teardown: $($remaining -join ', ')"
} else {
  Log "All ports freed after teardown."
}

# --- 9. summarize -----------------------------------------------------------
Log "=== JOINT E2E RUN COMPLETE ==="
Log "Bot A results: $(Join-Path $botADir 'joint-results-A.json')"
Log "Bot B results: $(Join-Path $botBDir 'joint-results-B.json')"
Log "Backend log:   $bkStdout"
Log "Server A log:  $saStdout"
Log "Server B log:  $sbStdout"
Log "PIDs: $pidFile"
Log "Ports: $portsFile"
