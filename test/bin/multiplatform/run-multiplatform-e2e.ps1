# ============================================================================
# run-multiplatform-e2e.ps1 -- portable NovaChat real-server E2E orchestrator.
#
# CI-friendly port of .e2e/bin/run-e2e-orchestrator.ps1 (gitignored, machine-
# specific). Drives the per-platform L1 flow (backend -> server -> bot) for each
# requested Java platform, enforces a per-platform timeout, guarantees teardown
# of every spawned PID, then reads each platform's results.json and emits a
# combined summary JSON to stdout + a summary file.
#
# Uses the parameterized primitives in this directory:
#   start-backend.ps1  -- starts ONE NovaLink backend JVM (parameterized by
#                         -RepoRoot / -RunsDir / -ClassPathFile / -JdkHome).
#   start-server.ps1   -- starts ONE Minecraft server process (parameterized
#                         by -Platform, -RepoRoot / -RunsDir / -JdkHome /
#                         -McPort / -RconPort). Switches on -Platform to pick
#                         the right lock entry, EULA policy, server.properties
#                         format, and ready pattern.
#
# It does NOT build the backend jars itself -- the per-platform orchestrator
# (run-<platform>-e2e.ps1) or the Gradle realE2E task must run
# `:StarLink:core:jar :StarLink:core:writeRuntimeClasspath --init-script
# test/bin/write-classpath.init.gradle` first, producing the classpath file at
# <RepoRoot>/.e2e-artifacts/novalink-core.classpath.txt.
#
# Exit codes:
#   0 = all REQUESTED platforms passed (or were gracefully skipped w/ reason)
#   1 = one or more requested platforms failed or were missing prereqs
#   2 = orchestrator itself failed (bad args, harness missing)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File run-multiplatform-e2e.ps1 `
#     -Platforms "bukkit,velocity" -RepoRoot $RepoRoot
# ============================================================================
[CmdletBinding()]
param(
  [string]$Platforms = "bukkit,bungee,velocity,nukkit,folia,pnx,sponge",
  [string]$Jdk21 = "$env:USERPROFILE\scoop\apps\temurin21-jdk\current",
  [string]$Jdk17 = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot",
  [string]$Jdk25 = "$env:USERPROFILE\scoop\apps\temurin25-jdk\current",
  [int]$TimeoutSec = 420,
  [int]$BotWaitSec = 150,
  [int]$ServerReadySec = 240,
  [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)))),
  [string]$BinDir = "",     # defaults to <RepoRoot>/test/bin/multiplatform
  [string]$BotDir = "",     # defaults to <RepoRoot>/test/bot
  [string]$DistDir = "",    # server jar cache; defaults to <RepoRoot>/.e2e-artifacts/dist
  [string]$SummaryFile = "" # defaults to <RepoRoot>/.e2e-artifacts/runs/e2e-summary.json
)

$ErrorActionPreference = "Stop"

# --- resolve derived paths ---------------------------------------------------
if (-not $BinDir)      { $BinDir      = Join-Path $RepoRoot "test/bin/multiplatform" }
if (-not $BotDir)      { $BotDir      = Join-Path $RepoRoot "test/bot" }
if (-not $DistDir)     { $DistDir     = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $SummaryFile) { $SummaryFile = Join-Path $RepoRoot ".e2e-artifacts/runs/e2e-summary.json" }
$ConfDir = Join-Path $RepoRoot "test/conf"
$ClassPathFile = Join-Path $RepoRoot ".e2e-artifacts/novalink-core.classpath.txt"

$RequestedPlatforms = $Platforms -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }

# Track every PID we start so we can guarantee teardown.
$global:Pids = [System.Collections.Generic.List[int]]::new()
$global:PidLabels = @{}

function Log([string]$msg) {
  $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg
  Write-Host $line
}

function Track-Pid([int]$procId, [string]$label) {
  if ($procId -le 0) { return }
  $global:Pids.Add($procId) | Out-Null
  $global:PidLabels[$procId] = $label
}

function Stop-TrackedPid([int]$procId, [string]$label) {
  if ($procId -le 0) { return }
  try {
    $p = Get-Process -Id $procId -ErrorAction Stop
    Log "  stopping $label (pid=$procId)"
    try { $p.CloseMainWindow() | Out-Null } catch {}
    Start-Sleep -Milliseconds 500
    if (-not $p.HasExited) {
      Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
  } catch {
    # already exited -- fine
  }
}

function Teardown-All {
  Log "--- teardown: stopping $($global:Pids.Count) tracked process(es) ---"
  # stop in reverse order (bots/servers first, backends last)
  for ($i = $global:Pids.Count - 1; $i -ge 0; $i--) {
    $pid = $global:Pids[$i]
    $label = $global:PidLabels[$pid]
    Stop-TrackedPid $pid $label
  }
  $global:Pids.Clear()
  $global:PidLabels.Clear()
}

# Ensure teardown runs even on Ctrl+C / script error.
$null = Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action { Teardown-All }
trap {
  Log "TRAP caught: $($_.Exception.Message)"
  Teardown-All
  break
}

# --- start a script and parse the PID= line from its stdout -------------------
function Invoke-StartScript([string]$scriptPath, [string]$label, [string[]]$extraArgs) {
  if (-not (Test-Path $scriptPath)) {
    Log "  ERROR: start script not found: $scriptPath"
    return -1
  }
  $outFile = [System.IO.Path]::GetTempFileName()
  $errFile = [System.IO.Path]::GetTempFileName()
  try {
    $argList = @("-ExecutionPolicy","Bypass","-NoProfile","-File",$scriptPath) + $extraArgs
    $proc = Start-Process -FilePath "powershell.exe" `
      -ArgumentList $argList `
      -RedirectStandardOutput $outFile -RedirectStandardError $errFile `
      -PassThru -NoNewWindow -Wait
    $out = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
    if ($proc.ExitCode -ne 0) {
      Log "  $label start script exit=$($proc.ExitCode)"
      if ($out) { $out -split "`n" | Select-Object -First 5 | ForEach-Object { Log "    OUT: $_" } }
      $err = Get-Content $errFile -Raw -ErrorAction SilentlyContinue
      if ($err) { $err -split "`n" | Select-Object -First 5 | ForEach-Object { Log "    ERR: $_" } }
      return -1
    }
    # parse PID=NNNN or <PLATFORM>_PID=NNNN from output
    $pidMatch = [regex]::Match($out, '(?im)^(?:[A-Z_]+_)?PID=(\d+)')
    if ($pidMatch.Success) {
      return [int]$pidMatch.Groups[1].Value
    }
    Log "  WARN: no PID= line in $label output: $($out.Trim())"
    return -1
  } finally {
    Remove-Item $outFile, $errFile -ErrorAction SilentlyContinue
  }
}

function Wait-LogMarker([string]$file, [string]$pattern, [int]$timeoutSec, [string]$label) {
  if (-not (Test-Path $file)) { Log "  ERROR: log file missing for $label ($file)"; return $false }
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $content = Get-Content $file -Raw -ErrorAction SilentlyContinue
    if ($content -and ($content -match $pattern)) { return $true }
    Start-Sleep -Seconds 3
  }
  Log "  TIMEOUT waiting for $label (pattern: $pattern)"
  return $false
}

# --- per-platform definitions ------------------------------------------------
# Each entry pins the ports + ready-pattern + chat phrase for that platform.
# The shared start-server.ps1 + start-backend.ps1 primitives switch on -Platform
# to resolve the lock entry, server.properties format, and EULA policy.
$PlatformDefs = @{
  bukkit = @{
    LockName = 'purpur';    BotScript = 'run-e2e.js';    BotKind = 'java'
    ReadyPattern = "(?i)Done \("; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
  }
  bungee = @{
    LockName = 'waterfall'; BotScript = 'run-e2e.js';    BotKind = 'java'
    ReadyPattern = "(?i)Listening"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    Note = "bungee waterfall needs an extra downstream purpur; this orchestrator runs the waterfall proxy only (see start-server.ps1 -Platform bungee)"
    # BungeeCord proxy plugin (thin jar, no shadow). Installed into the proxy
    # server's plugins/ dir (not a downstream purpur). bungee.yml is at the jar
    # root so Waterfall loads it as a proxy plugin.
    GradleTask  = ":NovaChat:Proxy:bungee:build"
    JarGlob     = "NovaChat/Proxy/bungee/build/libs/novachat-bungee-*.jar"
    BuildJdk    = "Jdk21"
    PluginsSubdir = "bungee/plugins"
  }
  velocity = @{
    LockName = 'velocity'; BotScript = 'run-e2e.js';     BotKind = 'java'
    ReadyPattern = "(?i)(Listening|Booting|Started|Done)"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    Note = "velocity needs an extra downstream purpur; this orchestrator runs the velocity proxy only (see start-server.ps1 -Platform velocity)"
    # Velocity proxy plugin (thin jar, no shadow). Velocity API requires JDK 25
    # (toolchain.languageVersion=25 + sourceCompatibility=VERSION_25 in build.gradle).
    # velocity-plugin.json is at the jar root so Velocity loads it as a proxy plugin.
    GradleTask  = ":NovaChat:Proxy:velocity:build"
    JarGlob     = "NovaChat/Proxy/velocity/build/libs/novachat-velocity-*.jar"
    BuildJdk    = "Jdk25"
    PluginsSubdir = "velocity/plugins"
  }
  nukkit = @{
    LockName = 'nukkit';   BotScript = 'run-e2e-bedrock.js'; BotKind = 'bedrock'
    ReadyPattern = "(?i)(Done|server.*started|Nukkit.*started|Loading.*complete|Listening)"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    # Nukkit Bedrock plugin (thin jar, no shadow). nukkit.yml is at the jar root
    # so the Nukkit server loads it. Installed into the Nukkit server's plugins/ dir.
    GradleTask  = ":NovaChat:Bedrock:nukkit:build"
    JarGlob     = "NovaChat/Bedrock/nukkit/build/libs/novachat-nukkit-*.jar"
    BuildJdk    = "Jdk21"
    PluginsSubdir = "nukkit/plugins"
  }
  folia = @{
    LockName = 'folia';    BotScript = 'run-e2e.js';     BotKind = 'java'
    ReadyPattern = "(?i)Done \("; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    # Gradle task + jar glob for the per-platform plugin build step.
    # Folia API 26.x declares org.gradle.jvm.version=25, so the plugin must be
    # compiled with JDK 25 (build.gradle sourceCompatibility=VERSION_25; no
    # toolchain block, so the Gradle launcher JVM must be JDK 25).
    GradleTask  = ":NovaChat:Plugin:folia:build"
    JarGlob     = "NovaChat/Plugin/folia/build/libs/NovaChat-Folia-*.jar"
    BuildJdk    = "Jdk25"
    PluginsSubdir = "folia/plugins"
  }
  pnx = @{
    LockName = 'nukkit';   BotScript = 'run-e2e-bedrock.js'; BotKind = 'bedrock'
    ReadyPattern = "(?i)(Done|server.*started|Nukkit.*started|Loading.*complete|Listening)"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    # PowerNukkitX Bedrock plugin (fat jar via shadow; archiveBaseName=NovaChat-PNX).
    # plugin.yml is at the jar root. Installed into the PNX server's plugins/ dir.
    GradleTask  = ":NovaChat:Bedrock:pnx:build"
    JarGlob     = "NovaChat/Bedrock/pnx/build/libs/NovaChat-PNX-*.jar"
    BuildJdk    = "Jdk21"
    PluginsSubdir = "pnx/plugins"
  }
  sponge = @{
    LockName = 'sponge';   BotScript = 'run-e2e.js';     BotKind = 'java'
    ReadyPattern = "(?i)(Done|Sponge.*ready|Loading.*complete|Listening)"; Jdk = "Jdk17"; ChatPhrase = "hello from e2e bot"
    # Sponge plugin (fat jar via shadow; archiveBaseName=NovaChat-Sponge).
    # META-INF/sponge_plugins.json is at the jar root. Installed into the
    # SpongeVanilla server's mods/ dir (Sponge plugins load from mods/).
    GradleTask  = ":NovaChat:Sponge:sponge:build"
    JarGlob     = "NovaChat/Sponge/sponge/build/libs/NovaChat-Sponge-*.jar"
    BuildJdk    = "Jdk17"
    PluginsSubdir = "sponge/mods"
  }
}

# --- prereq checks -----------------------------------------------------------
function Test-Prereqs([string[]]$platforms) {
  $issues = @()
  $harnessFiles = @(
    (Join-Path $RepoRoot "test/versions.lock.ps1"),
    (Join-Path $ConfDir "novalink.template.yml"),
    (Join-Path $BotDir "package.json"),
    (Join-Path $BotDir "package-lock.json"),
    (Join-Path $BotDir "run-e2e.js"),
    (Join-Path $BotDir "run-e2e-bedrock.js"),
    (Join-Path $BinDir "start-backend.ps1"),
    (Join-Path $BinDir "start-server.ps1"),
    (Join-Path (Split-Path -Parent $BinDir) "fetch-server.ps1")
  )
  foreach ($harnessFile in $harnessFiles) {
    if (-not (Test-Path -LiteralPath $harnessFile -PathType Leaf)) {
      $issues += "required harness file missing: $harnessFile"
    }
  }
  # JDK checks
  $java21 = Join-Path $Jdk21 "bin\java.exe"
  if (-not (Test-Path $java21)) {
    $issues += "JDK 21 java.exe not found at $java21 (set -Jdk21 or JAVA_HOME)"
  }
  $java17 = Join-Path $Jdk17 "bin\java.exe"
  if (-not (Test-Path $java17)) {
    $issues += "JDK 17 java.exe not found at $java17 (set -Jdk17; required for sponge)"
  }
  $java25 = Join-Path $Jdk25 "bin\java.exe"
  if (-not (Test-Path $java25)) {
    $issues += "JDK 25 java.exe not found at $java25 (set -Jdk25; required to build folia + velocity plugins)"
  }
  # Node.js
  $nodeExe = (Get-Command node -ErrorAction SilentlyContinue)
  if (-not $nodeExe) {
    $issues += "Node.js (node) not found on PATH (required for mineflayer/bedrock-protocol bots)"
  }
  # npm
  $npmExe = (Get-Command npm -ErrorAction SilentlyContinue)
  if (-not $npmExe) {
    $issues += "npm not found on PATH (required to install bot deps)"
  }
  # classpath file
  if (-not (Test-Path $ClassPathFile)) {
    $issues += "classpath file missing: $ClassPathFile"
    $issues += "  Run: .\gradlew :StarLink:core:jar :StarLink:core:writeRuntimeClasspath --init-script $(Join-Path $RepoRoot 'test/bin/write-classpath.init.gradle')"
  }
  # per-platform: bot script + results dir
  foreach ($pf in $platforms) {
    if (-not $PlatformDefs.ContainsKey($pf)) {
      $issues += "unknown platform '$pf' (valid: $($PlatformDefs.Keys -join ', '))"
      continue
    }
  }
  return $issues
}

# --- run one platform --------------------------------------------------------
function Invoke-Platform([string]$pf) {
  $def = $PlatformDefs[$pf]
  Log "=== [$pf] START ==="
  $result = [PSCustomObject]@{
    platform = $pf
    status = "UNKNOWN"
    l1Pass = $false
    chatRoundTrip = $false
    chatPhrase = $def.ChatPhrase
    notes = ""
    pids = @()
  }

  # set JDK env for this platform's scripts
  $jdkVar = $def.Jdk
  $jdkPath = if ($jdkVar -eq "Jdk17") { $Jdk17 } else { $Jdk21 }
  $env:JAVA_HOME = $jdkPath
  $env:PATH = "$jdkPath\bin;$env:PATH"

  # platforms with a Note are intentionally skipped by this orchestrator
  if ($def.Note) {
    Log "  SKIP: $($def.Note)"
    $result.status = "SKIPPED"
    $result.notes = $def.Note
    return $result
  }

  # per-platform run workspace; everything lands under .e2e-artifacts/runs/<pf>/
  $runsDir   = Join-Path $RepoRoot ".e2e-artifacts/runs/$pf"
  $serverDir = Join-Path $runsDir $pf
  $novaDir   = Join-Path $runsDir "novalink"
  $botDir    = $BotDir  # shared bot dir (run-e2e.js + run-e2e-bedrock.js + package.json)
  $resultsFile = Join-Path $runsDir "bot-results.json"
  $stdout    = Join-Path $serverDir "$pf.stdout.log"

  New-Item -ItemType Directory -Force -Path $runsDir, $serverDir, $novaDir | Out-Null

  try {
    # 0. build + install the per-platform plugin (if GradleTask is defined).
    #    bukkit has no GradleTask here because its self-contained run-bukkit-e2e.ps1
    #    already builds + installs the plugin; the other 6 Java platforms build
    #    their plugin fat/thin jar here and drop it into the server's plugins/ dir
    #    BEFORE start-server.ps1 lays down the run directory (so the jar is in
    #    place when the server boots). The plugins dir lives under <runsDir>/<SubDir>/plugins
    #    (or /mods for Sponge). start-server.ps1 creates <runsDir>/<SubDir> but NOT
    #    its plugins/ subdir, so we create it here.
    if ($def.GradleTask) {
      $buildJdkVar = $def.BuildJdk
      $buildJdkPath = switch ($buildJdkVar) {
        "Jdk25" { $Jdk25 }
        "Jdk17" { $Jdk17 }
        default { $Jdk21 }
      }
      $buildJdkExe = Join-Path $buildJdkPath "bin\java.exe"
      if (-not (Test-Path $buildJdkExe)) {
        $result.status = "FAIL"; $result.notes = "build JDK $($def.BuildJdk) missing at $buildJdkPath"
        return $result
      }
      Log "  building plugin: gradlew $($def.GradleTask) (JDK=$buildJdkVar)..."
      $isWin = $IsWindows -or $env:OS -eq "Windows_NT"
      $gradleW = if ($isWin) { ".\gradlew.bat" } else { "./gradlew" }
      $buildLog = Join-Path $runsDir "plugin-build.out.log"
      $buildErr = Join-Path $runsDir "plugin-build.err.log"
      # Save + restore JAVA_HOME so the plugin build uses the right JDK without
      # permanently clobbering the runtime JDK for the server process below.
      $savedJavaHome = $env:JAVA_HOME
      $savedPath = $env:PATH
      $env:JAVA_HOME = $buildJdkPath
      $env:PATH = "$buildJdkPath\bin;$env:PATH"
      try {
        Push-Location $RepoRoot
        & $gradleW $def.GradleTask "-x" "test" "--console=plain" 1>$buildLog 2>$buildErr
        $gradleExit = $LASTEXITCODE
        Pop-Location
      } finally {
        $env:JAVA_HOME = $savedJavaHome
        $env:PATH = $savedPath
      }
      if ($gradleExit -ne 0) {
        $result.status = "FAIL"; $result.notes = "plugin build failed (gradlew exit=$gradleExit; see $buildErr)"
        Log "  ERROR: plugin build failed (gradlew exit=$gradleExit)"
        if (Test-Path $buildErr) { Get-Content $buildErr -Tail 15 | ForEach-Object { Log "    $_" } }
        return $result
      }
      # Locate the built jar and copy it into the server's plugins/ dir.
      $jarGlobPath = Join-Path $RepoRoot $def.JarGlob
      $pluginJar = Get-ChildItem $jarGlobPath -ErrorAction SilentlyContinue | Select-Object -First 1
      if (-not $pluginJar) {
        $result.status = "FAIL"; $result.notes = "plugin jar not found at $jarGlobPath"
        return $result
      }
      # The plugins dir is <runsDir>/<PluginsSubdir> (e.g. folia/plugins, sponge/mods).
      $pluginsDir = Join-Path $runsDir $def.PluginsSubdir
      New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
      Copy-Item $pluginJar.FullName -Destination $pluginsDir -Force
      Log "  plugin installed: $($pluginJar.Name) -> $pluginsDir"
    }

    # 1. start backend (shared start-backend.ps1 primitive)
    Log "  starting backend..."
    $bkPid = Invoke-StartScript (Join-Path $BinDir "start-backend.ps1") "$pf-backend" `
      @("-RepoRoot",$RepoRoot, "-RunsDir",$runsDir, "-ClassPathFile",$ClassPathFile, "-JdkHome",$jdkPath)
    if ($bkPid -le 0) {
      $result.status = "FAIL"; $result.notes = "backend start failed"
      return $result
    }
    Track-Pid $bkPid "$pf-backend"
    $result.pids += $bkPid
    Start-Sleep -Seconds 5

    # 2. start server (shared start-server.ps1 primitive)
    Log "  starting $pf server..."
    $sPid = Invoke-StartScript (Join-Path $BinDir "start-server.ps1") "$pf-server" `
      @("-Platform",$pf, "-RepoRoot",$RepoRoot, "-RunsDir",$runsDir, "-DistDir",$DistDir, "-JdkHome",$jdkPath)
    if ($sPid -le 0) {
      $result.status = "FAIL"; $result.notes = "server start failed"
      return $result
    }
    Track-Pid $sPid "$pf-server"
    $result.pids += $sPid

    # 3. wait for server ready
    Log "  waiting for server ready (up to ${ServerReadySec}s)..."
    $ready = Wait-LogMarker $stdout $def.ReadyPattern $ServerReadySec "$pf-server"
    if (-not $ready) {
      $result.status = "FAIL"; $result.notes = "server did not signal ready within ${ServerReadySec}s"
      return $result
    }
    Start-Sleep -Seconds 3

    # 4. run bot (foreground, with timeout)
    Log "  starting bot (foreground, timeout ${BotWaitSec}s)..."
    if (-not (Test-Path (Join-Path $botDir "node_modules"))) {
      Push-Location $botDir
      try {
        $npmLog = Join-Path $runsDir "npm-install.log"
        $npmErr = Join-Path $runsDir "npm-install.err.log"
        $npmProc = Start-Process -FilePath "npm" -ArgumentList @("ci","--no-audit","--no-fund") `
          -WorkingDirectory $botDir -RedirectStandardOutput $npmLog -RedirectStandardError $npmErr -PassThru -NoNewWindow -Wait
        if ($npmProc.ExitCode -ne 0) {
          $result.status = "FAIL"; $result.notes = "npm ci failed (exit=$($npmProc.ExitCode); see $npmErr)"
          return $result
        }
      } finally { Pop-Location }
    }
    $env:E2E_MC_HOST = "127.0.0.1"
    $env:SERVER_HOST = "127.0.0.1"
    # Java platforms (mineflayer run-e2e.js) read E2E_* env vars; Bedrock platforms
    # (bedrock-protocol run-e2e-bedrock.js) read SERVER_*/BOT_NAME/etc. BotKind
    # selects the matching env-var set so the bot connects to the right server
    # (Java port 25565 vs Bedrock UDP 19132) and uses the right protocol version.
    if ($def.BotKind -eq 'bedrock') {
      $env:E2E_MC_PORT = "19132"
      $env:SERVER_PORT = "19132"
      $env:BOT_NAME = "E2E_Bot_Bedrock"
      $env:PLATFORM = $pf
      $env:MC_VERSION = "1.26.30"
      $env:BACKEND_CHAT_PHRASE = $def.ChatPhrase
      $env:RESULTS_FILE = $resultsFile
      $env:TIMEOUT_MS = "$($BotWaitSec * 1000)"
    } else {
      $env:E2E_MC_PORT = "25565"
      $env:E2E_BOT_USERNAME = "E2E_Bot_Alpha"
      $env:E2E_RESULTS_FILE = $resultsFile
      $env:E2E_TIMEOUT_MS = "$($BotWaitSec * 1000)"
      $env:E2E_PLATFORM = $pf
      $env:E2E_MC_VERSION = "1.21.8"
    }

    $botOut = Join-Path $runsDir "bot.stdout.log"
    $botErr = Join-Path $runsDir "bot.stderr.log"
    $botProc = Start-Process -FilePath "node" -ArgumentList @($def.BotScript) -WorkingDirectory $botDir `
      -RedirectStandardOutput $botOut -RedirectStandardError $botErr -PassThru -NoNewWindow
    Track-Pid $botProc.Id "$pf-bot"
    $result.pids += $botProc.Id

    $botDeadline = (Get-Date).AddSeconds($BotWaitSec)
    while (-not $botProc.HasExited -and (Get-Date) -lt $botDeadline) {
      Start-Sleep -Seconds 3
    }
    if (-not $botProc.HasExited) {
      Log "  bot timed out after ${BotWaitSec}s -- killing"
      Stop-Process -Id $botProc.Id -Force -ErrorAction SilentlyContinue
      $result.status = "FAIL"; $result.notes = "bot timed out"
    } else {
      Log "  bot exited (code=$($botProc.ExitCode))"
    }
    if (Test-Path $botOut) {
      $botLines = Get-Content $botOut -Tail 8 -ErrorAction SilentlyContinue
      if ($botLines) { $botLines | ForEach-Object { Log "    BOT: $_" } }
    }

    # 5. read results.json
    if (Test-Path $resultsFile) {
      $results = Get-Content $resultsFile -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json -ErrorAction SilentlyContinue
      if ($results) {
        # L1 pass = bot received a help/join/leave response (basic connectivity)
        $l1Evidence = $false
        $chatEvidence = $false
        foreach ($r in $results.received) {
          if ($r.kind -eq "messagestr" -and $r.raw -match "(?i)(NovaChat|nc help|join|leave|妫版垿浜?") { $l1Evidence = $true }
          if ($r.kind -eq "messagestr" -and $r.raw -match [regex]::Escape($def.ChatPhrase)) { $chatEvidence = $true }
          if ($r.kind -eq "chat" -and $r.raw -match [regex]::Escape($def.ChatPhrase)) { $chatEvidence = $true }
        }
        $result.l1Pass = $l1Evidence
        $result.chatRoundTrip = $chatEvidence
        if ($l1Evidence -and $chatEvidence) {
          $result.status = "PASS"
        } elseif ($l1Evidence) {
          $result.status = "PARTIAL"; $result.notes = "L1 ok but chat round-trip phrase not echoed"
        } else {
          $result.status = "FAIL"; $result.notes = "no L1 evidence in results.json"
        }
      } else {
        $result.status = "FAIL"; $result.notes = "results.json could not be parsed"
      }
    } else {
      $result.status = "FAIL"; $result.notes = "results.json not written: $resultsFile"
    }
  } finally {
    # teardown this platform's processes (backend + servers + bot)
    Log "  [$pf] teardown..."
    foreach ($p in $result.pids) { Stop-TrackedPid $p "$pf" }
    # remove from global tracker
    $global:Pids.Clear()
    $global:PidLabels.Clear()
  }
  return $result
}

# --- main --------------------------------------------------------------------
Log "=== NovaChat Multi-Platform E2E Orchestrator ==="
Log "Platforms: $($RequestedPlatforms -join ', ')"
Log "RepoRoot: $RepoRoot"
Log "JDK21: $Jdk21"
Log "JDK17: $Jdk17"
Log "JDK25: $Jdk25"
Log "Timeout per platform: ${TimeoutSec}s (server ready ${ServerReadySec}s, bot ${BotWaitSec}s)"
Log "BinDir: $BinDir"
Log "BotDir: $BotDir"
Log "ClassPathFile: $ClassPathFile"

$prereqIssues = Test-Prereqs $RequestedPlatforms
if ($prereqIssues.Count -gt 0) {
  Log "PREREQ FAILURES:"
  foreach ($i in $prereqIssues) { Log "  - $i" }
  Log "Run the backend build first to produce the classpath file, then re-run."
  exit 2
}

$allResults = @()
$overallPass = $true
foreach ($pf in $RequestedPlatforms) {
  $pfResult = Invoke-Platform $pf
  $allResults += $pfResult
  if ($pfResult.status -eq "FAIL") { $overallPass = $false }
  Log "=== [$pf] RESULT: $($pfResult.status) (L1=$($pfResult.l1Pass), chat=$($pfResult.chatRoundTrip)) ==="
  Start-Sleep -Seconds 3
}

# final teardown safety net
Teardown-All

# --- summary -----------------------------------------------------------------
Log ""
Log "=== E2E SUMMARY ==="
$summaryTable = @()
foreach ($r in $allResults) {
  $l1 = if ($r.l1Pass) { "PASS" } else { "FAIL" }
  $chat = if ($r.chatRoundTrip) { "MATCH" } else { "NO-MATCH" }
  Log ("  {0,-10} | L1: {1,-4} | chat: {2,-9} | {3}" -f $r.platform, $l1, $chat, $r.status)
  if ($r.notes) { Log "             | notes: $($r.notes)" }
  $summaryTable += [PSCustomObject]@{
    platform = $r.platform
    status = $r.status
    l1Pass = $r.l1Pass
    chatRoundTrip = $r.chatRoundTrip
    chatPhrase = $r.chatPhrase
    notes = $r.notes
  }
}

# write summary JSON
$summaryDir = Split-Path $SummaryFile -Parent
if (-not (Test-Path $summaryDir)) { New-Item -ItemType Directory -Force $summaryDir | Out-Null }
$summaryTable | ConvertTo-Json -Depth 5 | Set-Content $SummaryFile -Encoding utf8
Log "Summary written to: $SummaryFile"

if ($overallPass) {
  Log "=== ALL REQUESTED PLATFORMS PASSED ==="
  exit 0
} else {
  $failed = ($allResults | Where-Object { $_.status -eq "FAIL" } | ForEach-Object { $_.platform }) -join ", "
  Log "=== E2E FAILED for: $failed ==="
  exit 1
}
