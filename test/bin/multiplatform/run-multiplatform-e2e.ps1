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
# It does NOT build the backend jars itself -- run-e2e-orchestrator.ps1 performs
# `:StarLink:core:jar :StarLink:core:writeRuntimeClasspath --init-script
# test/bin/write-classpath.init.gradle` before dispatching here, producing the
# classpath file at <RepoRoot>/.e2e-artifacts/novalink-core.classpath.txt. When
# this script is run STANDALONE (not via the orchestrator), run that Gradle
# command first or Test-Prereqs will report the classpath file as missing.
#
# Exit codes:
#   0 = all REQUESTED platforms passed (some may have been SKIPPED)
#   1 = one or more requested platforms failed or were missing prereqs, OR a
#       platform was SKIPPED while -RequireAllPlatforms is set
#   2 = orchestrator itself failed (bad args, harness missing)
#   3 = every requested platform was SKIPPED (no test ran anywhere)
#
# A platform is SKIPPED when it did not execute its L1 flow (velocity/bungee are
# proxy-only by design). SKIPPED is a distinct terminal state, never PASS: the
# summary JSON records status="SKIPPED" + skipped=true + a human-readable note.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File run-multiplatform-e2e.ps1 `
#     -Platforms "bukkit,velocity" -RepoRoot $RepoRoot
# ============================================================================
[CmdletBinding()]
param(
  [string]$Platforms = "bukkit,bungee,velocity,nukkit,folia,pnx,sponge",
  # JDK homes. Left empty so the body can resolve them from the environment
  # first -- see Resolve-Jdk below. A default here cannot work on CI: the
  # previous defaults were absolute dev-machine scoop paths
  # ($env:USERPROFILE\scoop\apps\temurin21-jdk\current and an Adoptium
  # jdk-17.0.19.10 path) that exist only on the author's machine, so every E2E
  # platform failed prereq with "JDK 21 java.exe not found at
  # C:\Users\runneradmin\scoop\...".
  [string]$Jdk21 = "",
  [string]$Jdk17 = "",
  [string]$Jdk25 = "",
  [int]$TimeoutSec = 420,
  [int]$BotWaitSec = 150,
  # 480, not 240: a Java server with no pre-generated world spends most of its
  # first boot generating spawn chunks. Measured on Purpur 1.21.11: 236.5s to
  # "Done (" -- inside the old 240s budget by 3.5s, so any CI runner slower
  # than the dev box timed out and reported "server did not signal ready".
  # That was the folia failure in run 39049975656.
  [int]$ServerReadySec = 480,
  # Resolved in the body below -- see test/bin/run-pmmp-e2e.ps1 for why a
  # $MyInvocation default is $null under [CmdletBinding()].
  [string]$RepoRoot = "",
  [string]$BinDir = "",     # defaults to <RepoRoot>/test/bin/multiplatform
  [string]$BotDir = "",     # defaults to <RepoRoot>/test/bot
  [string]$DistDir = "",    # server jar cache; defaults to <RepoRoot>/.e2e-artifacts/dist
  [string]$SummaryFile = "", # defaults to <RepoRoot>/.e2e-artifacts/runs/e2e-summary.json
  # Strict CI mode: SKIPPED counts as failure. Off by default -- SKIPPED is a
  # normal local outcome (proxy-only platforms, blocked downloads), and CI opts
  # in explicitly via run-e2e-orchestrator.ps1 -RequireAllPlatforms.
  [switch]$RequireAllPlatforms
)

$ErrorActionPreference = "Stop"

# --- resolve derived paths ---------------------------------------------------
if (-not $RepoRoot)    { $RepoRoot    = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))) }
if (-not $BinDir)      { $BinDir      = Join-Path $RepoRoot "test/bin/multiplatform" }
if (-not $BotDir)      { $BotDir      = Join-Path $RepoRoot "test/bot" }
if (-not $DistDir)     { $DistDir     = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $SummaryFile) { $SummaryFile = Join-Path $RepoRoot ".e2e-artifacts/runs/e2e-summary.json" }
$ConfDir = Join-Path $RepoRoot "test/conf"
$ClassPathFile = Join-Path $RepoRoot ".e2e-artifacts/novalink-core.classpath.txt"
. (Join-Path (Split-Path -Parent $BinDir) 'e2e-assertions.ps1')
# versions.lock.ps1 supplies Get-LockedServer, which the bot-version selection
# below calls to pick E2E_MC_VERSION. That file is dot-sourced here rather than
# left to the caller: run-e2e-orchestrator.ps1 invokes this script as a separate
# `powershell.exe -File` process (see its dispatch section), so nothing it
# dot-sourced would cross the process boundary anyway. Without this line every
# Java platform aborted at "starting bot" with "Get-LockedServer is not
# recognized" and the failure surfaced only as a bare "UNKNOWN" diagnostic.
$lockScript = Join-Path $RepoRoot "test/versions.lock.ps1"
if (-not (Test-Path -LiteralPath $lockScript)) {
  # Log is defined further down; this runs during initialisation, so use the
  # plain host writer to avoid "Log is not recognized" masking the real error.
  Write-Host "ERROR: versions.lock.ps1 not found: $lockScript"
  exit 2
}
. $lockScript

# --- resolve the three JDKs --------------------------------------------------
# Order of preference, per JDK:
#   1. an explicit -JdkNN argument
#   2. the CI env var actions/setup-java exports for a multi-version request
#      (JAVA_HOME_21_X64 / _17_X64 / _25_X64)
#   3. a JAVA_HOME whose release file matches the requested major version
#   4. the author's local scoop / Adoptium layout (dev convenience)
# This keeps the harness runnable both on a GitHub runner and on a dev box
# without either hardcoding the other's paths.
function Resolve-Jdk {
  param(
    [string]$Explicit,
    [string]$EnvName,
    [int]$Major,
    [string[]]$LocalCandidates
  )
  $candidates = @()
  if ($Explicit)        { $candidates += $Explicit }
  $envValue = [Environment]::GetEnvironmentVariable($EnvName)
  if ($envValue)        { $candidates += $envValue }
  if ($env:JAVA_HOME)   { $candidates += $env:JAVA_HOME }
  $candidates += $LocalCandidates
  foreach ($c in $candidates) {
    if (-not $c) { continue }
    $exe = Join-Path $c "bin/java.exe"
    if (-not (Test-Path $exe)) { $exe = Join-Path $c "bin/java" }
    if (-not (Test-Path $exe)) { continue }
    # A JAVA_HOME may point at any version; only accept it when its release
    # file (or the path) names the major version we need.
    $release = Join-Path $c "release"
    if (Test-Path $release) {
      $content = Get-Content $release -Raw -ErrorAction SilentlyContinue
      if ($content -match 'JAVA_VERSION="(\d+)') {
        if ([int]$Matches[1] -ne $Major) { continue }
        return $c
      }
    }
    if ($c -match "(?:jdk|jdk-|temurin)?[-]?$Major(?:[.\-_]|$)") { return $c }
    if ($c -eq $Explicit) { return $c }   # caller vouched for it
  }
  return $null
}

$Jdk21 = Resolve-Jdk -Explicit $Jdk21 -EnvName 'JAVA_HOME_21_X64' -Major 21 `
  -LocalCandidates @("$env:USERPROFILE\scoop\apps\temurin21-jdk\current")
$Jdk17 = Resolve-Jdk -Explicit $Jdk17 -EnvName 'JAVA_HOME_17_X64' -Major 17 `
  -LocalCandidates @(
    "$env:USERPROFILE\scoop\apps\temurin17-jdk\current",
    "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot")
$Jdk25 = Resolve-Jdk -Explicit $Jdk25 -EnvName 'JAVA_HOME_25_X64' -Major 25 `
  -LocalCandidates @("$env:USERPROFILE\scoop\apps\temurin25-jdk\current")


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
  #
  # The loop variable must NOT be named $pid: $pid is PowerShell's automatic
  # read-only "current process id" variable, so `$pid = ...` throws
  # SessionStateUnauthorizedAccessException on the FIRST iteration. That aborted
  # this loop before Stop-TrackedPid ran even once, which is why every run
  # reported "stopping 0 tracked process(es)" and left its backend JVM and
  # server alive after the orchestrator exited. Verified on this host:
  #   try { $pid = 1 } catch { -> SessionStateUnauthorizedAccessException }
  foreach ($trackedId in @($global:Pids)) {
    $label = $global:PidLabels[$trackedId]
    Stop-TrackedPid $trackedId $label
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
  # Start-Process with FILE redirection (not pipes) is required here. The child
  # is start-backend.ps1 / start-server.ps1, which launch a long-lived JVM and
  # exit immediately; that JVM inherits the child's stdout/stderr handles. With
  # pipe redirection the write end stays open in the JVM, so reading to EOF
  # would block forever and this function would hang instead of returning a PID.
  # Files close cleanly because the JVM writes to the file directly.
  #
  # DO NOT use -Wait here. -Wait does not wait for the child: it waits for the
  # child AND every process the child left behind, i.e. it blocks until the
  # grandchild's stdout/stderr handles close. The grandchild here is the backend
  # JVM that start-backend.ps1 launches with --daemon -- it lives until teardown,
  # so -Wait made this function sit silent for the whole run while the backend
  # was already up (the "29 minutes of silence" symptom on folia). Measured on
  # this host with a launcher that spawns a 20s grandchild and exits:
  # -Wait returned after 22.1s, -PassThru + WaitForExit() after 0.8s. The PID
  # must be parsed as soon as the launcher exits, so use WaitForExit().
  #
  # ExitCode is NULL with this form on Windows PowerShell 5.1 (measured here:
  # a launcher exiting 0 and one exiting 7 BOTH read back as $null). PowerShell
  # only populates ExitCode when -Wait keeps the child handle open. `$null -ne 0`
  # is TRUE in PowerShell, so the old `if ($proc.ExitCode -ne 0)` treated every
  # SUCCESSFUL launch as a failure -- the orchestrator reported "backend start
  # failed" for a backend that was up and listening on 27905, and because the
  # PID was never returned the JVM was never tracked and never torn down. Only
  # an OBSERVED non-zero code is a failure; $null means "unknown", and the PID=
  # marker decides success instead.
  $outFile = [System.IO.Path]::GetTempFileName()
  $errFile = [System.IO.Path]::GetTempFileName()
  try {
    $argList = @("-ExecutionPolicy","Bypass","-NoProfile","-File",$scriptPath) + $extraArgs
    $proc = Start-Process -FilePath "powershell.exe" `
      -ArgumentList $argList `
      -RedirectStandardOutput $outFile -RedirectStandardError $errFile `
      -PassThru -NoNewWindow
    # Wait for the launcher script only. The backend JVM it spawned is tracked
    # by its own PID (printed as BACKEND_PID= by start-backend.ps1) and is torn
    # down by Stop-TrackedPid, not by this function.
    $proc.WaitForExit()
    $exitCode = $proc.ExitCode
    $out = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
    if ($null -ne $exitCode -and $exitCode -ne 0) {
      Log "  $label start script exit=$exitCode"
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
    # No PID marker: this is the only reliable failure signal when ExitCode is
    # unavailable (see the note above), so surface both streams -- a start
    # script that dies on a missing jar prints its reason to stderr, and a bare
    # "no PID= line" message sends the reader looking in the wrong place.
    Log "  WARN: no PID= line in $label output (exitCode=$(if ($null -eq $exitCode) { 'unknown' } else { $exitCode }))"
    if ($out) { $out -split "`n" | Select-Object -First 8 | ForEach-Object { Log "    OUT: $_" } }
    $err = Get-Content $errFile -Raw -ErrorAction SilentlyContinue
    if ($err) { $err -split "`n" | Select-Object -First 8 | ForEach-Object { Log "    ERR: $_" } }
    return -1
  } finally {
    Remove-Item $outFile, $errFile -ErrorAction SilentlyContinue
  }
}

# start-server.ps1 does NOT always put the server's working dir under the
# platform name: it switches on -Platform and uses the PlatformMap's SubDir.
# bukkit runs Purpur (a Paper fork), so its SubDir is "paper" -- the log lands in
# <runsDir>/paper/, never in <runsDir>/bukkit/. Reading <runsDir>/<pf> for
# bukkit therefore polls a file that is never created; Wait-LogMarker used to
# return false immediately, and the platform then failed after the whole
# ServerReadySec timeout with "no *.log files" diagnostics, while the server's
# real output sat unread in runs/bukkit/paper/. Every other platform's SubDir
# equals its name today (folia/folia, nukkit/nukkit, ...), which is why the
# mismatch was invisible outside bukkit. Keep this in sync with PlatformMap in
# start-server.ps1; the candidates below also accept the plain <runsDir>/<pf>
# layout so a future divergence degrades into "found at the other path" rather
# than a 480s false timeout.
$PlatformServerSubDir = @{ bukkit = 'paper' }

function Get-ServerLogCandidates([string]$runsDir, [string]$platform) {
  $subDir = if ($PlatformServerSubDir.ContainsKey($platform)) { $PlatformServerSubDir[$platform] } else { $platform }
  # Order matters only when both exist; the first is start-server.ps1's rule.
  return @(
    (Join-Path (Join-Path $runsDir $subDir)   "$platform.stdout.log"),
    (Join-Path (Join-Path $runsDir $platform) "$platform.stdout.log"),
    (Join-Path $runsDir "$platform.stdout.log")
  )
}

function Wait-LogMarker([string[]]$files, [string]$pattern, [int]$timeoutSec, [string]$label) {
  # Servers create their log a moment after the launcher returns, so "missing"
  # is only fatal after a grace period -- but it must stay fatal (not degrade
  # into the full ServerReadySec timeout), because a wrong path is a harness bug,
  # not a slow server. 20s is well inside every platform's boot time.
  $graceDeadline = (Get-Date).AddSeconds([Math]::Min(20, [Math]::Max(5, $timeoutSec / 4)))
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  $missingReported = $false
  while ((Get-Date) -lt $deadline) {
    foreach ($file in $files) {
      if (-not (Test-Path -LiteralPath $file)) { continue }
      $content = Get-Content -LiteralPath $file -Raw -ErrorAction SilentlyContinue
      if ($content -and ($content -match $pattern)) { return $true }
    }
    $anyExists = @($files | Where-Object { Test-Path -LiteralPath $_ }).Count -gt 0
    if (-not $anyExists -and -not $missingReported -and (Get-Date) -gt $graceDeadline) {
      Log "  ERROR: log file missing for $label; tried: $($files -join ', ')"
      $missingReported = $true
      return $false
    }
    Start-Sleep -Seconds 3
  }
  Log "  TIMEOUT waiting for $label (pattern: $pattern; files: $($files -join ', '))"
  return $false
}

# --- failure diagnostics -----------------------------------------------------
# Every process this script starts writes its stdout/stderr to a file under
# $runsDir, and none of those files used to reach the console or the CI job
# log. A platform that timed out reported one line ("server did not signal
# ready within 480s") while the server's own output sat unread on disk --
# exactly the folia/sponge failures in run 39056124289. Print the tail of every
# log the run produced, labelled with its path, so the job log alone is enough
# to diagnose a failure even when the artifact upload is unavailable.
function Get-LogRank([string]$name) {
  # Lower rank first: stderr before stdout, build logs before the rest.
  switch -Wildcard ($name) {
    '*stderr.log'       { return 0 }
    '*stdout.log'       { return 1 }
    'plugin-build*.log' { return 2 }
    'build.*.log'       { return 2 }
    'npm-install*.log'  { return 3 }
    default             { return 4 }
  }
}

function Write-LogTail([string]$path, [int]$lines) {
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
  $size = (Get-Item -LiteralPath $path).Length
  Log ("  --- {0} ({1:N0} bytes, last {2} lines) ---" -f $path, $size, $lines)
  Get-Content -LiteralPath $path -Tail $lines -Encoding UTF8 -ErrorAction SilentlyContinue |
    ForEach-Object { Log "    $_" }
}

function Write-FailureDiagnostics([string]$runDir, [string]$platform, [string]$reason) {
  Log ""
  Log "=== [$platform] FAILURE DIAGNOSTICS: $reason ==="
  if (-not (Test-Path -LiteralPath $runDir)) {
    Log "  run directory does not exist: $runDir"
    return
  }
  # Recursive on purpose: besides the files this harness redirects by name,
  # servers write their own (Purpur logs/latest.log, PocketMine server.log,
  # a JVM hs_err_pid*.log). dist/ holds the downloaded binaries, not logs.
  $logs = @(Get-ChildItem -LiteralPath $runDir -Recurse -File -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -eq '.log' -and $_.FullName -notmatch '[\\/]dist[\\/]' } |
    Sort-Object @{ Expression = { Get-LogRank $_.Name } },
                @{ Expression = { $_.LastWriteTime }; Descending = $true } |
    Select-Object -First 10)
  if ($logs.Count -eq 0) {
    Log "  no *.log files under $runDir"
    return
  }
  foreach ($f in $logs) { Write-LogTail $f.FullName 40 }
  Log "=== [$platform] END FAILURE DIAGNOSTICS ==="
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
    # archiveBaseName is the module name, so the jar is "bungee-<version>.jar"
    # -- there is no "novachat-" prefix. A wrong glob does not fail the build;
    # the platform silently reports "plugin jar not found" and FAILs.
    JarGlob     = "NovaChat/Proxy/bungee/build/libs/bungee-*.jar"
    BuildJdk    = "Jdk21"
    PluginsSubdir = "bungee/plugins"
  }
  velocity = @{
    LockName = 'velocity'; BotScript = 'run-e2e.js';     BotKind = 'java'
    # Velocity 4.x requires Java 25 at RUNTIME as well (docs.papermc.io: "at
    # least Java 25"); the 4.1.0 jar's classes are class-file 69.0, so 21 dies
    # with UnsupportedClassVersionError (verified 2026-10-18).
    ReadyPattern = "(?i)(Listening|Booting|Started|Done)"; Jdk = "Jdk25"; ChatPhrase = "hello from e2e bot"
    Note = "velocity needs an extra downstream purpur; this orchestrator runs the velocity proxy only (see start-server.ps1 -Platform velocity)"
    # Velocity proxy plugin (thin jar, no shadow). Velocity API requires JDK 25
    # (toolchain.languageVersion=25 + sourceCompatibility=VERSION_25 in build.gradle).
    # velocity-plugin.json is at the jar root so Velocity loads it as a proxy plugin.
    GradleTask  = ":NovaChat:Proxy:velocity:build"
    JarGlob     = "NovaChat/Proxy/velocity/build/libs/velocity-*.jar"
    BuildJdk    = "Jdk25"
    PluginsSubdir = "velocity/plugins"
  }
  nukkit = @{
    LockName = 'nukkit';   BotScript = 'run-e2e-bedrock.js'; BotKind = 'bedrock'
    ReadyPattern = "(?i)(Done|server.*started|Nukkit.*started|Loading.*complete|Listening)"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    # Nukkit Bedrock plugin (thin jar, no shadow). nukkit.yml is at the jar root
    # so the Nukkit server loads it. Installed into the Nukkit server's plugins/ dir.
    GradleTask  = ":NovaChat:Bedrock:nukkit:build"
    JarGlob     = "NovaChat/Bedrock/nukkit/build/libs/nukkit-*.jar"
    BuildJdk    = "Jdk21"
    PluginsSubdir = "nukkit/plugins"
  }
  folia = @{
    LockName = 'folia';    BotScript = 'run-e2e.js';     BotKind = 'java'
    # JDK 25 for the SERVER too, not just the plugin build. Folia 26.1.2's
    # version.json declares java_version 25 and booting on 21 exits with
    # "Minecraft 26.1 and newer requires running the server with Java 25 or
    # above" (verified 2026-10-18); on 25 it reaches "Done (".
    ReadyPattern = "(?i)Done \("; Jdk = "Jdk25"; ChatPhrase = "hello from e2e bot"
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
    # JDK 21, not 17: the SpongeVanilla universal jar's installer/bootstrap is
    # class-file 65.0, so 17 dies at launch with UnsupportedClassVersionError;
    # on 21 it reaches "Done (" (verified 2026-10-18).
    ReadyPattern = "(?i)(Done|Sponge.*ready|Loading.*complete|Listening)"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    # Sponge plugin (fat jar via shadow; archiveBaseName=NovaChat-Sponge).
    # META-INF/sponge_plugins.json is at the jar root. Installed into the
    # SpongeVanilla server's mods/ dir (Sponge plugins load from mods/).
    GradleTask  = ":NovaChat:Sponge:sponge:build"
    JarGlob     = "NovaChat/Sponge/sponge/build/libs/NovaChat-Sponge-*.jar"
    # Build and run on the same JDK 21. Gradle configures every subproject before
    # running a task, and :NovaChat:MOD:fabric applies fabric-loom 1.17.17, which
    # refuses to load on a JVM below 21 -- so 17 was never a viable build JVM
    # either.
    BuildJdk    = "Jdk21"
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
  # JDK checks. JDK 21 is the fallback for every platform that does not pin a
  # different runtime, so it is required unconditionally; 17 and 25 are only
  # required when a requested platform actually asks for them (the plugin build
  # JDKs count too -- folia/velocity compile on 25).
  $neededJdks = @{ 'Jdk21' = $true }
  foreach ($pf in $platforms) {
    $pdef = $PlatformDefs[$pf]
    if (-not $pdef) { continue }
    if ($pdef.Jdk)      { $neededJdks[$pdef.Jdk] = $true }
    if ($pdef.BuildJdk) { $neededJdks[$pdef.BuildJdk] = $true }
  }
  $jdkHomes = @{ 'Jdk17' = $Jdk17; 'Jdk21' = $Jdk21; 'Jdk25' = $Jdk25 }
  foreach ($jdkVar in @('Jdk17','Jdk21','Jdk25')) {
    if (-not $neededJdks.ContainsKey($jdkVar)) { continue }
    $jdkHome = $jdkHomes[$jdkVar]
    $jdkNum = $jdkVar -replace '^Jdk',''
    $jdkExe = if ($jdkHome) { Join-Path $jdkHome "bin\java.exe" } else { "" }
    if ($jdkExe -and (Test-Path $jdkExe)) { continue }
    $users = @($platforms | Where-Object { $PlatformDefs[$_] -and ($PlatformDefs[$_].Jdk -eq $jdkVar -or $PlatformDefs[$_].BuildJdk -eq $jdkVar) })
    $issues += "JDK $jdkNum java.exe not found at '$jdkExe' (set -$jdkVar or JAVA_HOME_${jdkNum}_X64; needed by $($users -join ', '))"
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

  # set JDK env for this platform's scripts. Resolve through a switch rather
  # than "Jdk17 or else Jdk21": the old ternary silently mapped Jdk25 to Jdk21,
  # so folia booted on 21 and died instantly with "Minecraft 26.1 and newer
  # requires running the server with Java 25 or above" while the harness kept
  # waiting 480s for a ready marker that could never appear.
  $jdkVar = $def.Jdk
  $jdkPath = switch ($jdkVar) {
    "Jdk25" { $Jdk25 }
    "Jdk17" { $Jdk17 }
    default { $Jdk21 }
  }
  if (-not $jdkPath) {
    $result.status = "FAIL"; $result.notes = "runtime JDK $jdkVar could not be resolved"
    Log "  ERROR: runtime JDK $jdkVar could not be resolved (check -Jdk17/-Jdk21/-Jdk25 or the JAVA_HOME_NN_X64 env vars)"
    return $result
  }
  $env:JAVA_HOME = $jdkPath
  $env:PATH = "$jdkPath\bin;$env:PATH"
  # Print the resolved runtime JDK: a wrong one now costs a 480s ready-timeout
  # with no hint as to why, which is exactly how the folia JDK21 bug hid.
  Log "  runtime JDK: $jdkVar ($jdkPath)"

  # platforms with a Note are intentionally skipped by this orchestrator
  if ($def.Note) {
    Log "  SKIP: $($def.Note)"
    $result.status = "SKIPPED"
    $result.notes = $def.Note
    return $result
  }

  # per-platform run workspace; everything lands under .e2e-artifacts/runs/<pf>/
  $runsDir   = Join-Path $RepoRoot ".e2e-artifacts/runs/$pf"
  # Server working dir / log dir. start-server.ps1 picks <runsDir>/<SubDir> from
  # its PlatformMap (bukkit -> "paper"), so the reader must use the same rule --
  # see Get-ServerLogCandidates. Creating only the resolved dir here keeps the
  # plugin install target consistent with where the server will look.
  $serverSubDir = if ($PlatformServerSubDir.ContainsKey($pf)) { $PlatformServerSubDir[$pf] } else { $pf }
  $serverDir = Join-Path $runsDir $serverSubDir
  $novaDir   = Join-Path $runsDir "novalink"
  $botDir    = $BotDir  # shared bot dir (run-e2e.js + run-e2e-bedrock.js + package.json)
  $resultsFile = Join-Path $runsDir "bot-results.json"
  # Candidate log paths in preference order; the first that exists wins. bukkit
  # resolves to runs/<pf>/paper/bukkit.stdout.log (start-server.ps1 names the
  # file "<Platform>.stdout.log", i.e. the platform NAME, not the SubDir).
  $serverLogCandidates = Get-ServerLogCandidates $runsDir $pf

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
        # Gradle writes progress (e.g. "Enabling dist annotations on PMJ") to
        # stderr. With $ErrorActionPreference = "Stop" (set at the top of this
        # script), PowerShell turns a native command's stderr into a TERMINATING
        # error, so `& gradlew ... 2>$buildErr` aborted this function on the
        # first stderr line instead of running the build -- the platform then
        # reported FAIL with a bogus note and no jar. Drop to Continue for just
        # this invocation (the exit code is captured explicitly right after, so
        # a real build failure is still detected) and restore it immediately.
        # run-bukkit-e2e.ps1 solves the same problem by using Start-Process,
        # which is not an option here because Start-Process throws on hosts
        # whose environment holds case-variant duplicates (see the note in
        # run-e2e-orchestrator.ps1).
        $savedEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & $gradleW $def.GradleTask "-x" "test" "--console=plain" 1>$buildLog 2>$buildErr
        $gradleExit = $LASTEXITCODE
        $ErrorActionPreference = $savedEap
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
    $ready = Wait-LogMarker $serverLogCandidates $def.ReadyPattern $ServerReadySec "$pf-server"
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
      # Ask for the protocol version the lock declares for the bot, not the
      # server's own version: folia ships no build mineflayer can speak, and the
      # hardcoded 1.21.8 here disagreed with every server the harness started.
      $botLock = Get-LockedServer -Name $def.LockName
      $env:E2E_MC_VERSION = if ($botLock -and $botLock.BotMcVersion) { $botLock.BotMcVersion }
                            elseif ($botLock -and $botLock.MC) { $botLock.MC }
                            else { "1.21.11" }
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
      # -Encoding UTF8 is REQUIRED. The bot writes results.json as UTF-8 and the
      # plugin echoes non-ASCII text (Chinese "[NC-500] server internal error"
      # strings) into it. Windows PowerShell 5.1's Get-Content defaults to the
      # ANSI/GBK code page, so a -Raw read without -Encoding UTF8 decodes those
      # bytes wrongly and ConvertFrom-Json throws ArgumentException. That
      # exception unwinds out of this try block, past every status assignment,
      # so the platform reports "UNKNOWN:" with empty notes even though the bot
      # ran, wrote a valid file, and the server reached "Done (...)". Do not
      # drop this encoding, and keep the catch: an unparseable file must produce
      # a FAIL with a real message, not an UNKNOWN with no message.
      try {
        $results = Get-Content $resultsFile -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
      } catch {
        $results = $null
        $result.notes = "results.json unreadable/unparseable: $($_.Exception.Message)"
        Log "  WARN: $($result.notes)"
      }
      if ($results) {
        # Get-BotReceivedRows drops the bot's own outgoing traffic: both bot
        # scripts record every command they send into the SAME received[] list
        # with kind='sent'. The previous loop below walked results.received
        # unfiltered, so the bot's own "/nc help" and "/nc join global" rows
        # satisfied the L1 regex through their own text.
        $rows = Get-BotReceivedRows $results

        # L1: a line only NovaChat can emit reached the bot. The regex this
        # replaces was '(?i)(NovaChat|nc help|join|leave|<mojibake>)' with no
        # anchor at all -- the bare `join` matched vanilla's
        # "E2E_Bot_Alpha joined the game", which a server with NO plugin
        # installed prints anyway, and the trailing mojibake made the pattern
        # depend on the ANSI decoding of this file rather than on the payload.
        # Anchoring to the "NovaChat" brand token (every platform's message
        # prefix / help header -- see Test-PluginEvidence) means the line can
        # only come from the plugin.
        $l1Evidence = Test-PluginEvidence $rows

        # Chat round-trip: the phrase must come back as a system message
        # ('messagestr'), never as the plain 'chat' kind. The bot's own sent
        # rows are already gone (Get-BotReceivedRows).
        #
        # Vanilla re-broadcasts a player's own chat line to the sender: a bot
        # that types "hello from e2e bot" into an empty server receives
        # "<E2E_Bot_Alpha> hello from e2e bot" back with no plugin involved.
        # The original loop accepted exactly that row, so the round-trip flag
        # was true on a bare server too. Verified against the recorded payload
        # in .e2e-artifacts/joint/20261021-113425: a NovaChat-routed line lands
        # as 'messagestr' in the configured channel format
        # ("[Global] <name>: <text>"), while the vanilla echo is the only row of
        # kind 'chat'. Requiring the non-'chat' kind is what separates the two.
        $chatEvidence = $false
        foreach ($r in $rows) {
          if ($r.kind -eq "messagestr" -and
              $r.raw -match [regex]::Escape($def.ChatPhrase)) { $chatEvidence = $true }
        }

        $result.l1Pass = $l1Evidence
        $result.chatRoundTrip = $chatEvidence
        if ($l1Evidence -and $chatEvidence) {
          $result.status = "PASS"
        } elseif ($l1Evidence) {
          # PARTIAL deliberately does NOT gate -- decision, not an oversight:
          #
          # 1. This orchestrator never provisions the PLUGIN side of the
          #    handshake. start-backend.ps1 writes novalink.yml with a random
          #    {{CLIENT_PASSWORD}} on the default port 27905, and neither it
          #    nor start-server.ps1 writes plugins/NovaChat/config.yml (JSON
          #    for LeviLamina, HOCON for Sponge). The plugin therefore keeps
          #    its bundled template -- username "Survival_Server", port 8888 --
          #    and can NEVER authenticate against this backend. The chat
          #    round-trip is unreachable here by construction, on every
          #    platform, regardless of plugin health.
          # 2. Under that condition PARTIAL is exactly what a healthy plugin
          #    produces: it did load and answered /nc help, but has no backend
          #    to route through. Gating on PARTIAL would paint every platform
          #    red for a harness gap, which is the same class of dishonest
          #    signal as the vacuous PASS this change removes.
          #
          # Consequence, stated plainly: PASS here means "plugin loaded and
          # answered a command", NOT "chat round-trip verified". The
          # self-contained run-<platform>-e2e.ps1 scripts are the ones that
          # provision credentials and gate on the backend link.
          $result.status = "PARTIAL"; $result.notes = "plugin answered a command, but chat round-trip not echoed (plugin-side backend credentials are not provisioned by this orchestrator)"
        } else {
          $result.status = "FAIL"; $result.notes = "no plugin-produced line in results.json (only vanilla traffic)"
        }
      } else {
        $result.status = "FAIL"
        # Preserve the specific parse error from the catch above when there is
        # one; only fall back to the generic message if $results came back
        # empty without throwing (e.g. a zero-byte file).
        if (-not $result.notes) { $result.notes = "results.json could not be parsed" }
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
    # Dump AFTER teardown: the processes have released their redirect handles,
    # so the tails are complete rather than whatever happened to be flushed.
    # Every early-return failure path above lands here, which is why the dump
    # lives in `finally` instead of at each return.
    # "PASS" is the only outcome worth no log tail; PARTIAL (L1 ok, chat
    # round-trip missed) and UNKNOWN (an exception unwound through here) both
    # need the same evidence as FAIL.
    if ($result.status -ne "PASS") {
      Write-FailureDiagnostics $runsDir $pf "$($result.status): $($result.notes)"
    }
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
  if ($pfResult.status -eq "SKIPPED") {
    Log "=== [$pf] RESULT: SKIPPED (no assertions ran) ==="
  } else {
    Log "=== [$pf] RESULT: $($pfResult.status) (L1=$($pfResult.l1Pass), chat=$($pfResult.chatRoundTrip)) ==="
  }
  Start-Sleep -Seconds 3
}

# final teardown safety net
Teardown-All

# --- summary -----------------------------------------------------------------
Log ""
Log "=== E2E SUMMARY ==="
$summaryTable = @()
foreach ($r in $allResults) {
  # A SKIPPED platform ran no assertions; printing "L1: FAIL" for it would be
  # just as dishonest as printing PASS. Show a neutral placeholder instead.
  $l1 = if ($r.status -eq "SKIPPED") { "N/A" } elseif ($r.l1Pass) { "PASS" } else { "FAIL" }
  $chat = if ($r.status -eq "SKIPPED") { "N/A" } elseif ($r.chatRoundTrip) { "MATCH" } else { "NO-MATCH" }
  Log ("  {0,-10} | L1: {1,-4} | chat: {2,-9} | {3}" -f $r.platform, $l1, $chat, $r.status)
  if ($r.notes) { Log "             | notes: $($r.notes)" }
  $summaryTable += [PSCustomObject]@{
    platform = $r.platform
    status = $r.status
    skipped = ($r.status -eq "SKIPPED")
    l1Pass = $r.l1Pass
    chatRoundTrip = $r.chatRoundTrip
    chatPhrase = $r.chatPhrase
    notes = $r.notes
  }
}

# write summary JSON
$summaryDir = Split-Path $SummaryFile -Parent
if (-not (Test-Path $summaryDir)) { New-Item -ItemType Directory -Force $summaryDir | Out-Null }
# BOM-free UTF-8: 5.1's -Encoding utf8 prefixes a BOM, and the orchestrator's
# Get-Content|ConvertFrom-Json and Groovy's JsonSlurper both read this file.
$summaryJson = $summaryTable | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText($SummaryFile, $summaryJson, (New-Object System.Text.UTF8Encoding($false)))
Log "Summary written to: $SummaryFile"

$skippedResults = @($allResults | Where-Object { $_.status -eq "SKIPPED" })
$skippedSummary = ($skippedResults | ForEach-Object { $_.platform }) -join ", "
if (-not $overallPass) {
  $failed = ($allResults | Where-Object { $_.status -eq "FAIL" } | ForEach-Object { $_.platform }) -join ", "
  Log "=== E2E FAILED for: $failed ==="
  if ($skippedResults.Count -gt 0) { Log "=== also SKIPPED (no test ran): $skippedSummary ===" }
  exit 1
}
if ($skippedResults.Count -gt 0 -and $skippedResults.Count -eq $allResults.Count -and $allResults.Count -gt 0) {
  Log "=== NO PLATFORM ACTUALLY RAN (all SKIPPED): $skippedSummary ==="
  exit 3
}
if ($skippedResults.Count -gt 0) {
  if ($RequireAllPlatforms) {
    Log "=== STRICT MODE: SKIPPED platform(s) count as failures: $skippedSummary ==="
    exit 1
  }
  Log "=== ALL REQUESTED NON-SKIPPED PLATFORMS PASSED; SKIPPED (no test ran): $skippedSummary ==="
} else {
  Log "=== ALL REQUESTED PLATFORMS PASSED ==="
}
exit 0
