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
#   0 = all REQUESTED platforms passed (some may have been SKIPPED-BY-DESIGN)
#   1 = one or more requested platforms failed or were missing prereqs, OR an
#       ENVIRONMENT-blocked platform was SKIPPED while -RequireAllPlatforms is set
#   2 = orchestrator itself failed (bad args, harness missing)
#   3 = no test ran and at least one skip was ENVIRONMENT-blocked (e.g. BDS
#       unavailable) -- a condition that may clear on its own, so strict mode
#       treats it as a failure
#   4 = no test ran and every skip was BY DESIGN -- a structural limit that will
#       not clear by retrying, so strict mode does NOT fail it
#
# Two skip classes, deliberately distinct. A platform is SKIPPED-BY-DESIGN when
# it can never execute its L1 flow on this orchestrator (velocity/bungee are
# proxy-only and need a downstream server this script does not run); it records
# status="SKIPPED-BY-DESIGN" + skippedByDesign=true. A platform is SKIPPED when
# the environment blocked it; it records status="SKIPPED" + skipped=true. Both
# are distinct terminal states, never PASS. The consumer's contract is in
# run-e2e-orchestrator.ps1's Convert-ExitCodeToStatus: 3 -> 'SKIPPED',
# 4 -> 'SKIPPED-BY-DESIGN'; keep the two in step.
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

# --- random hex ---------------------------------------------------------------
# Backend credentials have to be generated by whoever writes BOTH ends of the
# handshake (novalink.yml clients[].password and the plugin's backend.password),
# so the orchestrator generates them and hands the same pair to both.
#
# Windows PowerShell 5.1 runs on .NET Framework, where the static
# RandomNumberGenerator::GetBytes(int) overload and Convert::ToHexString do not
# exist -- calling them throws "MethodNotFound". The instance
# Create().GetBytes(byte[]) + BitConverter path works on both 5.1 and 7+.
# Mirrors New-RandomHex in start-backend.ps1 and run-<platform>-e2e.ps1.
function New-RandomHex([int]$byteLen) {
  $buf = New-Object byte[] $byteLen
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buf)
  return [BitConverter]::ToString($buf).Replace('-', '')
}

# --- backend port -----------------------------------------------------------
# Single source of truth for the TCP port the backend listens on AND the port
# written into every plugin-side config. start-backend.ps1 defaults to the same
# value; passing it explicitly from both call sites keeps the two ends from
# drifting (a plugin pointing at a port nothing listens on is exactly the bug
# that produced "no plugin-produced line in results.json").
$NovaBackendPort = 27905

# --- provision the plugin-side config ----------------------------------------
# The plugin's bundled template (resources/default-novachat.conf for Sponge,
# resources/config.yml for the JVM/Bedrock plugins) is a USER-facing example:
# port 8888 and username "Sponge_Server"/"Survival_Server". If the plugin's own
# config file is absent at first boot the updater copies that template verbatim,
# so the plugin dials 8888 while the backend listens on -NovaPort 27905 and the
# handshake never leaves the box: every /nc join then answers NC-503
# "not connected", and NO plugin-branded line ever reaches the bot.
#
# This MUST run before the server's first start. The updater only fills MISSING
# keys from the template (HoconConfigUpdater.merge: `if ($targetChild.virtual())`
# is the only write path), so a file written here survives the merge and its
# values win; writing it after boot would race the plugin's own construction.
#
# Kept as a table keyed by platform rather than an if/else so adding another
# platform is one entry, and platforms with no PluginConfig key are untouched.
function Write-PluginBackendConfig {
  param(
    [hashtable]$PlatformDef,
    [string]$ServerDir,
    [int]$BackendPort,
    [string]$ClientPassword,
    [string]$Username = "E2E_Client"
  )
  if (-not $PlatformDef.PluginConfig) { return $null }
  $cfg = $PlatformDef.PluginConfig
  $configPath = Join-Path $ServerDir $cfg.RelativePath
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $configPath) | Out-Null
  # The body is HOCON for Sponge (key = value, braces) and YAML for the rest;
  # the template strings in the platform def carry the platform's syntax so this
  # function stays format-agnostic.
  $body = $cfg.Body.Replace('{{HOST}}', '127.0.0.1').
                       Replace('{{PORT}}', [string]$BackendPort).
                       Replace('{{USERNAME}}', $Username).
                       Replace('{{PASSWORD}}', $ClientPassword)
  # UTF-8 WITHOUT a BOM, deliberately.
  #
  # Set-Content -Encoding UTF8 emits a BOM on Windows PowerShell 5.1 (only
  # PowerShell 7's utf8NoBOM avoids it). The plugin parses this file as HOCON;
  # a leading U+FEFF corrupts the very first key, so `config-version` would be
  # read as "﻿config-version" and the updater would then re-add the real
  # key while the plugin's strict NovaChatConfig reader could reject the file
  # outright. The .NET UTF8Encoding(false) path is identical on 5.1 and 7+.
  $utf8NoBom = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText($configPath, $body, $utf8NoBom)
  return $configPath
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

# Nukkit/PNX prompt on stdin for a language code on first boot when nukkit.yml
# is absent. Under Start-Process stdin is the null device, so the read never
# returns and the server hangs before opening UDP 19132. That is a harness bug
# (start-server.ps1 must seed nukkit.yml), not a slow boot: report it as such
# instead of waiting out ServerReadySec and then blaming the server's speed.
function Test-NukkitLanguagePrompt([string[]]$files) {
  foreach ($file in $files) {
    if (-not (Test-Path -LiteralPath $file)) { continue }
    $content = Get-Content -LiteralPath $file -Raw -ErrorAction SilentlyContinue
    if ($content -and ($content -match 'Please choose a language first')) { return $true }
  }
  return $false
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
    # A Nukkit/PNX language prompt can never be satisfied without a TTY, so the
    # server will never reach the ready line. Bail immediately with a specific
    # note rather than burning the full ServerReadySec budget.
    if (Test-NukkitLanguagePrompt $files) {
      Log "  ERROR: $label is blocked on Nukkit's language prompt (stdin is not a TTY)"
      return $false
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
    # Username the bot script logs in with. Declared here (not re-typed at each
    # use site) because BOTH the bot env below and the chat-round-trip gate
    # need it: a gate keyed on a name the bot did not log in as can never
    # match, and two literals in two places is how the server/bot port drifted
    # before (see the McPort note on nukkit).
    BotName = "E2E_Bot_Alpha"
    # TCP port the server binds AND the bot dials (see the McPort note on nukkit).
    McPort = 25565
  }
  bungee = @{
    LockName = 'waterfall'; BotScript = 'run-e2e.js';    BotKind = 'java'
    ReadyPattern = "(?i)Listening"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    McPort = 25565
    BotName = "E2E_Bot_Alpha"
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
    McPort = 25565
    BotName = "E2E_Bot_Alpha"
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
    # Anchored to Nukkit's lang.ini startFinished line, "Done (<secs>s)! For
    # help, ...". A bare "Done" also matches "idn => Indonesia" in the
    # first-boot language menu, which made a hung server look ready and sent
    # the bot at a dead UDP port (E2E run 39873580052, nukkit + pnx).
    ReadyPattern = "(?i)Done \([0-9.]+s\)!"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    # UDP port the server binds AND the bot dials. Bedrock is UDP 19132 (Java
    # platforms use TCP 25565). This must be one value read by both sides:
    # the -McPort passed to start-server.ps1 and the bot's SERVER_PORT used to
    # be wired separately (start-server defaulted to 25565 while the bot was
    # hardcoded to 19132), so the server bound UDP 25565 and the bot dialed UDP
    # 19132 -- a RakNet timeout on every run, independent of the language prompt.
    McPort = 19132
    # The module's own `build` writes a THIN jar: :NovaChat:common and
    # :NovaChat:client-core are `implementation`, so they are on the compile
    # classpath but NOT inside the artifact. A real Nukkit server loading it
    # dies during Server.<init> with
    #   NoClassDefFoundError: com/nova/chat/client/network/AbstractPlatformNetworkClient
    # and never reaches the ready line, so the platform FAILs on a ready
    # timeout with the plugin error buried in the log. Build the self-contained
    # jar through the same init script run-joint.ps1 uses.
    GradleTask  = ":NovaChat:Bedrock:nukkit:nukkitE2eJar"
    InitScript  = "plugin-e2e-fatjar.init.gradle"
    InitProps   = @{ e2ePluginProject = ":NovaChat:Bedrock:nukkit"; e2ePluginTask = "nukkitE2eJar"; e2ePluginBaseName = "novachat-nukkit" }
    JarGlob     = "NovaChat/Bedrock/nukkit/build/libs/novachat-nukkit-*.jar"
    BuildJdk    = "Jdk21"
    PluginsSubdir = "nukkit/plugins"
    BotName     = "E2E_Bot_Bedrock"
    # Nukkit reads config.yml from new File(getDataFolder(), "config.yml")
    # (NovaChatNukkit.java:241) and getDataFolder() is plugins/<the `name:` in
    # this module's src/main/resources/nukkit.yml>, which is "NovaChat" -- the
    # descriptor is nukkit.yml, not plugin.yml, but the rule is the same.
    # Without this file the YamlConfigUpdater copies the bundled template
    # verbatim (port 8888, username "Nukkit_Server"), the plugin never
    # authenticates, and the platform can only ever report PARTIAL -- which is
    # exit 0, i.e. a green job for a plugin that never talked to anything.
    # Verified 2026-10-23: a run with the bundled defaults logged
    # "Failed to connect to NovaLink: Connection refused ... /127.0.0.1:8888"
    # while the harness backend was up on 27905.
    #
    # EVERY key NovaChatConfig reads through requireString/requireSection must
    # be present here, because those helpers THROW on a missing key and
    # NovaChatNukkit#loadConfiguration turns that into a refused start:
    #   requireNonBlankString backend.host/.username, chat.default_channel/.locale
    #   requireString        backend.password, format.prefix/.error/.success/.default
    #   requireSection       chat.channel-prefixes, format.channels
    #   requirePort/requirePositiveInt/requireBoolean backend.port/.reconnect-delay,
    #                        chat.replace_vanilla, debug
    # A missing section is NOT harmless: YamlConfigUpdater only fills in missing
    # keys when the file is parsed as a valid mapping, so relying on the merge
    # to add format.* would work only by accident and would break silently if
    # that updater ever tightened. Spell them out.
    #
    # The values mirror the bundled template (src/main/resources/config.yml) so
    # the rendered envelope stays "[全服] <player>: <text>", which the chat gate
    # below requires (see the chatEvidence note). All five channel formats are
    # listed because the template's format.channels carries all five; dropping
    # one would fall back to format.default for that channel and change the
    # envelope the gate matches.
    PluginConfig = @{
      RelativePath = "plugins/NovaChat/config.yml"
      Body = @'
config-version: 1

backend:
  host: "{{HOST}}"
  port: {{PORT}}
  username: "{{USERNAME}}"
  password: "{{PASSWORD}}"
  reconnect-delay: 5
chat:
  replace_vanilla: false
  default_channel: "local"
  locale: "en_US"
  channel-prefixes: {}
format:
  prefix: "§8[§bNovaChat§8]§r "
  error: "§c错误: {message}"
  success: "§a成功: {message}"
  channels:
    global: "§c[全服] §7{player}§f: {message}"
    local: "§e[本地] §7{player}§f: {message}"
    resource: "§a[资源] §7{player}§f: {message}"
    pvp: "§c[PVP] §7{player}§f: {message}"
    private_default: "§d[私聊] §7{player}§f: {message}"
  default: "§7[{channel_color}{channel_name}] {player}§f: {message}"
debug: false
'@
    }
  }
  folia = @{
    LockName = 'folia';    BotScript = 'run-e2e.js';     BotKind = 'java'
    # JDK 25 for the SERVER too, not just the plugin build. Folia 26.1.2's
    # version.json declares java_version 25 and booting on 21 exits with
    # "Minecraft 26.1 and newer requires running the server with Java 25 or
    # above" (verified 2026-10-18); on 25 it reaches "Done (".
    ReadyPattern = "(?i)Done \("; Jdk = "Jdk25"; ChatPhrase = "hello from e2e bot"
    McPort = 25565
    # Folia API 26.x requires JDK 25.
    # build includes shadowJar; the thin jar is disabled.
    GradleTask  = ":NovaChat:Plugin:folia:build"
    JarGlob     = "NovaChat/Plugin/folia/build/libs/NovaChat-Folia-*.jar"
    BuildJdk    = "Jdk25"
    PluginsSubdir = "folia/plugins"
    BotName     = "E2E_Bot_Alpha"
    # Data folder follows plugin.yml's name.
    PluginConfig = @{
      RelativePath = "plugins/NovaChat-Folia/config.yml"
      Body = @'
config-version: 1

backend:
  host: "{{HOST}}"
  port: {{PORT}}
  username: "{{USERNAME}}"
  password: "{{PASSWORD}}"
  reconnect-delay: 5
folia:
  network-timeout: 5000
chat:
  replace_vanilla: false
  default_channel: "local"
  locale: "en_US"
  channel-prefixes: {}
format:
  prefix: "&8[&bNovaChat&8]&r "
  error: "&c错误: {message}"
  success: "&a成功: {message}"
  channels:
    global: "&c[全服] &7{player}&f: {message}"
    local: "&e[本地] &7{player}&f: {message}"
    resource: "&a[资源] &7{player}&f: {message}"
    pvp: "&c[PVP] &7{player}&f: {message}"
    private_default: "&d[私聊] &7{player}&f: {message}"
  default: "&7[{channel_color}{channel_name}] {player}&f: {message}"
debug: false
'@
    }
  }
  pnx = @{
    LockName = 'powernukkitx'; BotScript = 'run-e2e-bedrock.js'; BotKind = 'bedrock'
    ReadyPattern = "(?i)Done \([0-9.]+s\)!"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    # Same UDP port as nukkit (see above); one value shared by server and bot.
    McPort = 19132
    # PowerNukkitX Bedrock plugin (fat jar via shadow; archiveBaseName=NovaChat-PNX).
    # plugin.yml is at the jar root. Installed into the PNX server's plugins/ dir.
    GradleTask  = ":NovaChat:Bedrock:pnx:build"
    JarGlob     = "NovaChat/Bedrock/pnx/build/libs/NovaChat-PNX-*.jar"
    BuildJdk    = "Jdk21"
    PluginsSubdir = "pnx/plugins"
    BotName     = "E2E_Bot_Bedrock"
    # Same shape as nukkit above: NovaChatPNX#loadConfiguration reads
    # getDataFolder().toPath().resolve("config.yml")
    # (NovaChatPNX.java:258) and plugin.yml declares `name: NovaChat`, so the
    # data folder is plugins/NovaChat.
    #
    # PNX's NovaChatConfig requires MORE than nukkit's: it also reads
    # world-routing.enabled and world-routing.mappings through
    # requireBoolean/requireSection, so a body without that block throws at
    # construction and the plugin refuses to enable. Both are supplied below.
    # Same bundled-defaults problem as nukkit above (PNX's template ships
    # username "PNX_Server", port 8888).
    PluginConfig = @{
      RelativePath = "plugins/NovaChat/config.yml"
      Body = @'
config-version: 1

backend:
  host: "{{HOST}}"
  port: {{PORT}}
  username: "{{USERNAME}}"
  password: "{{PASSWORD}}"
  reconnect-delay: 5
chat:
  replace_vanilla: false
  default_channel: "local"
  locale: "en_US"
  channel-prefixes: {}
format:
  prefix: "§8[§bNovaChat§8]§r "
  error: "§c错误: {message}"
  success: "§a成功: {message}"
  channels:
    global: "§c[全服] §7{player}§f: {message}"
    local: "§e[本地] §7{player}§f: {message}"
    resource: "§a[资源] §7{player}§f: {message}"
    pvp: "§c[PVP] §7{player}§f: {message}"
    private_default: "§d[私聊] §7{player}§f: {message}"
  default: "§7[{channel_color}{channel_name}] {player}§f: {message}"
world-routing:
  enabled: true
  mappings:
    world: "local"
    world_nether: "nether"
    world_the_end: "end"
debug: false
'@
    }
  }
  sponge = @{
    LockName = 'sponge';   BotScript = 'run-e2e.js';     BotKind = 'java'
    # JDK 21, not 17: the SpongeVanilla universal jar's installer/bootstrap is
    # class-file 65.0, so 17 dies at launch with UnsupportedClassVersionError;
    # on 21 it reaches "Done (" (verified 2026-10-18).
    ReadyPattern = "(?i)(Done|Sponge.*ready|Loading.*complete|Listening)"; Jdk = "Jdk21"; ChatPhrase = "hello from e2e bot"
    McPort = 25565
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
    BotName     = "E2E_Bot_Alpha"
    # Sponge resolves @ConfigDir(sharedRoot=false) to <server>/config/novachat/
    # (plugin id "novachat"), and NovaChatSponge#loadConfiguration reads
    # novachat.conf from it. Without this file the plugin copies its bundled
    # template (port 8888, username "Sponge_Server") and can never authenticate
    # against the backend on 27905 -- see Write-PluginBackendConfig.
    # HOCON: keys are "k = v", not YAML's "k: v".
    PluginConfig = @{
      RelativePath = "config/novachat/novachat.conf"
      Body = @'
config-version = 1

backend {
    host = "{{HOST}}"
    port = {{PORT}}
    username = "{{USERNAME}}"
    password = "{{PASSWORD}}"
    reconnect-delay = 5
}

chat {
    replace_vanilla = false
    default_channel = "global"
    locale = "en_US"
    channel-prefixes {}
}

# NovaChatConfig#<init> calls requireMap(rootNode, "format", "format") and then
# requireString on prefix/error/success/default: a missing block throws and
# NovaChatSponge#loadConfiguration refuses the plugin. HoconConfigUpdater does
# merge the bundled template in, but only for keys absent from a file that
# parses as a valid mapping -- relying on that is the accidental-success trap
# the nukkit block above documents. Spell the block out.
format {
    prefix = "&8[&bNovaChat&8]&r "
    error = "&c错误: {message}"
    success = "&a成功: {message}"
    channels {
        global = "&c[全服] &7{player}&f: {message}"
        local = "&e[本地] &7{player}&f: {message}"
        resource = "&a[资源] &7{player}&f: {message}"
        pvp = "&c[PVP] &7{player}&f: {message}"
        private_default = "&d[私聊] &7{player}&f: {message}"
    }
    default = "&7[{channel_color}{channel_name}] {player}&f: {message}"
}

# Read only through the dynamic-mapping list; absent, the plugin still starts.
world-routing {
    enabled = false
    mappings {
        world = "local"
    }
}

debug = false
'@
    }
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
  # per-platform: bot script + results dir + port
  foreach ($pf in $platforms) {
    if (-not $PlatformDefs.ContainsKey($pf)) {
      $issues += "unknown platform '$pf' (valid: $($PlatformDefs.Keys -join ', '))"
      continue
    }
    # Every platform must declare the port its server binds AND its bot dials.
    # A missing value would silently fall back to start-server.ps1's 25565
    # default for a Bedrock server, which binds UDP 19132 -- the exact drift
    # this field exists to prevent.
    if (-not $PlatformDefs[$pf].McPort) {
      $issues += "platform '$pf' has no McPort in PlatformDefs"
    }
    # BotName is not cosmetic: the chat-round-trip gate builds its regex from
    # it. Empty, the pattern degenerates to "[] : <phrase>" -- an envelope no
    # real line has, so the gate would report NO-MATCH forever and PASS could
    # never be reached. Fail the prereq instead, where the fix is obvious.
    if (-not $PlatformDefs[$pf].BotName) {
      $issues += "platform '$pf' has no BotName in PlatformDefs (needed by the chat round-trip gate)"
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
    # Declared up front, NOT added later. Windows PowerShell 5.1 throws
    # "The property 'skippedByDesign' cannot be found on this object" when a
    # property is assigned to a [PSCustomObject] that does not already have it;
    # with $ErrorActionPreference = "Stop" that aborts Invoke-Platform, so the
    # Note-driven skip below would surface as UNKNOWN instead of a skip.
    # Verified on this host (5.1.26100).
    skippedByDesign = $false
    l1Pass = $false
    chatRoundTrip = $false
    chatPhrase = $def.ChatPhrase
    notes = ""
    pids = @()
  }

  # Platforms with a Note are skipped BY DESIGN: the Note states a structural
  # limit of this orchestrator (a proxy needs a downstream server it does not
  # run), not a transient environment problem. The two classes carry different
  # exit codes -- 4 here, 3 for an environment block -- because only the latter
  # is worth failing strict mode over: retrying a proxy-only platform will
  # never make it run. See the exit-code block in the header.
  #
  # This check MUST stay above the JDK resolution below. A platform that
  # structurally cannot run here must never FAIL because a JDK it will never
  # use is missing: velocity wants JDK 25, and on a host without 25 the old
  # ordering reported "FAIL: runtime JDK Jdk25 could not be resolved" instead
  # of a skip (observed here 2026-10-23) -- which reds a run over a platform
  # that was never going to be driven.
  if ($def.Note) {
    Log "  SKIP-BY-DESIGN: $($def.Note)"
    $result.status = "SKIPPED-BY-DESIGN"
    $result.skippedByDesign = $true
    $result.notes = $def.Note
    return $result
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
  $botRunId = Initialize-BotRun $resultsFile
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
        # InitScript/InitProps let a platform build a SELF-CONTAINED jar from a
        # module whose own `build` only emits a thin one (nukkit). Same shape as
        # run-joint.ps1 so the two harnesses cannot drift on the jar contents.
        $gradleArgList = @($def.GradleTask, "-x", "test", "--console=plain")
        if ($def.InitScript) {
          # Init scripts live in test/bin (the PARENT of $BinDir, which is
          # test/bin/multiplatform here but test/bin in run-joint.ps1).
          $initDir = Split-Path -Parent $BinDir
          $initPath = Join-Path $initDir $def.InitScript
          if (-not (Test-Path $initPath)) {
            $result.status = "FAIL"; $result.notes = "init script not found: $initPath"
            return $result
          }
          $gradleArgList += @("--init-script", $initPath)
        }
        if ($def.InitProps) {
          foreach ($key in $def.InitProps.Keys) {
            $gradleArgList += ("-P{0}={1}" -f $key, $def.InitProps[$key])
          }
        }
        & $gradleW @gradleArgList 1>$buildLog 2>$buildErr
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

    # 0b. provision the PLUGIN-side backend credentials.
    #
    # This is the other half of the handshake. start-backend.ps1 generates a
    # random {{CLIENT_PASSWORD}} for novalink.yml's clients[] entry; unless the
    # plugin's own config carries that same username+password and the real
    # backend port, the plugin keeps its bundled template defaults (port 8888,
    # username "Survival_Server"/"Sponge_Server"), never authenticates, and no
    # plugin-branded line ever reaches the bot -- which is exactly what "no
    # plugin-produced line in results.json (only vanilla traffic)" meant for
    # the sponge job in run 39873580052.
    #
    # Ordering is load-bearing and mirrors run-bukkit-e2e.ps1: the file has to
    # exist BEFORE the server's first start so the plugin's config updater sees
    # a real file and only fills in missing keys from its bundled template.
    # Writing it after boot races plugin construction and loses.
    #
    # Credentials are generated here (not in start-backend.ps1) so the SAME
    # values reach both files; passing them down as arguments keeps the backend
    # script's own random fallback unused.
    #
    # Platforms without a PluginConfig key (bukkit, which provisions its own
    # credentials in run-bukkit-e2e.ps1, and the proxies) are untouched.
    $clientPw = New-RandomHex 16
    $secretKey = New-RandomHex 32
    $pluginConfigPath = Write-PluginBackendConfig -PlatformDef $def `
      -ServerDir $serverDir -BackendPort $NovaBackendPort -ClientPassword $clientPw
    if ($pluginConfigPath) {
      Log "  plugin config provisioned: $pluginConfigPath (backend 127.0.0.1:$NovaBackendPort, user E2E_Client)"
    }

    # 1. start backend (shared start-backend.ps1 primitive)
    Log "  starting backend..."
    # -NovaPort carries the same $NovaBackendPort written into the plugin config
    # above (and is start-backend.ps1's own default); it is passed explicitly so
    # the two ends can never drift. -ClientPassword/-SecretKey pin the
    # credentials the plugin just received instead of letting the script
    # generate its own.
    $bkPid = Invoke-StartScript (Join-Path $BinDir "start-backend.ps1") "$pf-backend" `
      @("-RepoRoot",$RepoRoot, "-RunsDir",$runsDir, "-ClassPathFile",$ClassPathFile, "-JdkHome",$jdkPath,
        "-NovaPort",[string]$NovaBackendPort, "-ClientPassword",$clientPw, "-SecretKey",$secretKey)
    if ($bkPid -le 0) {
      $result.status = "FAIL"; $result.notes = "backend start failed"
      return $result
    }
    Track-Pid $bkPid "$pf-backend"
    $result.pids += $bkPid
    Start-Sleep -Seconds 5

    # 2. start server (shared start-server.ps1 primitive)
    # -McPort MUST be passed: start-server.ps1 defaults to 25565, but Bedrock
    # platforms listen on UDP 19132. The port comes from $def.McPort, the same
    # value the bot dials below, so server and bot can never disagree.
    Log "  starting $pf server on port $($def.McPort)..."
    $sPid = Invoke-StartScript (Join-Path $BinDir "start-server.ps1") "$pf-server" `
      @("-Platform",$pf, "-RepoRoot",$RepoRoot, "-RunsDir",$runsDir, "-DistDir",$DistDir, "-JdkHome",$jdkPath, "-McPort","$($def.McPort)")
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
      # Distinguish "hung on the interactive language prompt" from "slow boot":
      # the former means start-server.ps1 failed to seed nukkit.yml and is a
      # harness bug, the latter means the server really needed more time.
      if (Test-NukkitLanguagePrompt $serverLogCandidates) {
        $result.status = "FAIL"
        $result.notes = "server blocked on Nukkit's first-boot language prompt (nukkit.yml not seeded; stdin is not a TTY under Start-Process) -- never opened its port"
      } else {
        $result.status = "FAIL"; $result.notes = "server did not signal ready within ${ServerReadySec}s"
      }
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
    # and uses the right protocol version. The port is NOT hardcoded here: it is
    # $def.McPort, the same value passed to start-server.ps1 as -McPort. Two
    # literals in two places is exactly how the server ended up binding UDP
    # 25565 while the bot dialed UDP 19132.
    if ($def.BotKind -eq 'bedrock') {
      $env:E2E_MC_PORT = "$($def.McPort)"
      $env:SERVER_PORT = "$($def.McPort)"
      # Same literal the chat gate below keys on ($def.BotName); kept in one
      # place so the name the bot logs in with cannot drift from the name the
      # gate looks for.
      $env:BOT_NAME = $def.BotName
      $env:PLATFORM = $pf
      # Ask for the protocol version the lock declares, same as the Java branch
      # below. The hardcoded "1.26.30" was one revision behind the pinned Nukkit
      # build, which serves 1.26.40 and kicks a 1.26.30 client with
      # disconnectionScreen.outdatedClient (Nukkit does not negotiate down).
      $bedrockLock = Get-LockedServer -Name $def.LockName
      $env:MC_VERSION = if ($bedrockLock -and $bedrockLock.BotMcVersion) { $bedrockLock.BotMcVersion } else { "1.26.40" }
      $env:BACKEND_CHAT_PHRASE = $def.ChatPhrase
      $env:RESULTS_FILE = $resultsFile
      $env:TIMEOUT_MS = "$($BotWaitSec * 1000)"
    } else {
      $env:E2E_MC_PORT = "$($def.McPort)"
      $env:E2E_BOT_USERNAME = $def.BotName
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
    # Keep the native process handle; PowerShell 5.1 otherwise loses ExitCode.
    $null = $botProc.Handle
    Track-Pid $botProc.Id "$pf-bot"
    $result.pids += $botProc.Id

    $botDeadline = (Get-Date).AddSeconds($BotWaitSec)
    while (-not $botProc.HasExited -and (Get-Date) -lt $botDeadline) {
      Start-Sleep -Seconds 3
    }
    $botTimedOut = -not $botProc.HasExited
    if ($botTimedOut) {
      Log "  bot timed out after ${BotWaitSec}s -- killing"
      Stop-Process -Id $botProc.Id -Force -ErrorAction SilentlyContinue
      $result.status = "FAIL"; $result.notes = "bot timed out"
    } else {
      Log "  bot exited (code=$($botProc.ExitCode))"
    }
    $botProc.WaitForExit()
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
        $runFailure = Get-BotRunFailure -Results $results -ExitCode $botProc.ExitCode `
          -TimedOut $botTimedOut -ExpectedRunId $botRunId `
          -ServerLogPaths @((Join-Path $serverDir "$pf.stdout.log"), (Join-Path $serverDir "$pf.stderr.log"),
            (Join-Path $novaDir "stdout.log"), (Join-Path $novaDir "stderr.log"))
        if ($runFailure) {
          $result.status = "FAIL"; $result.notes = $runFailure
          return $result
        }
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

        # Chat round-trip: the phrase must come back inside NovaChat's channel
        # FORMAT, not just arrive on a row that happens to say the phrase.
        #
        # The rule is now: some received row's plain text matches
        #   [<anything>] <bot name>: <-- the phrase -->
        # i.e. the "[<channel>] <player>: <text>" envelope the plugin's
        # format.channels / format.default produce. This is the same predicate
        # run-bukkit/run-pmmp/run-endstone/run-levilamina use.
        #
        # Why the previous rule was WRONG, not merely weak: it accepted any row
        # with kind -eq 'messagestr' whose raw contained the phrase, on the
        # theory that "vanilla's echo is the only row of kind 'chat'". That
        # premise is false on Sponge. CI run 39873580052 recorded:
        #   sent        | hello from e2e bot (replace mode)
        #   messagestr  | <E2E_Bot_Alpha> hello from e2e bot (replace mode)
        #   chat        | <E2E_Bot_Alpha> hello from e2e bot (replace mode)
        # The vanilla re-broadcast arrived as 'messagestr' too, so the gate
        # passed on a platform where l1Pass was false and no plugin-shaped line
        # ever reached the bot: chatRoundTrip=true was a false positive.
        #
        # The envelope is what vanilla never prints. Both flavours of colour
        # code are stripped first (Get-BotReceivedPlainTexts): bedrock-protocol
        # keeps "§c[全服] §7Name§f: text" in the row while mineflayer hands back
        # already-rendered "[全服] Name: text", so a raw-text match would
        # silently fail on every Bedrock platform.
        #
        # No row-kind filter beyond "not the bot's own sent traffic" (already
        # dropped by Get-BotReceivedRows): the kind is not what distinguishes a
        # plugin line -- the envelope is. Java platforms use 'messagestr',
        # Bedrock uses 'text:raw', and requiring one of those would make the
        # gate platform-specific for no gain.
        #
        # The bot name is $def.BotName (E2E_Bot_Alpha on Java, E2E_Bot_Bedrock
        # on Bedrock) -- the same value handed to the bot's env below, so the
        # gate and the bot cannot disagree.
        #
        # The envelope alone already excludes the vanilla echo on Java, but NOT
        # on Bedrock: bedrock-protocol's raw text carries the section-sign
        # colour codes, so the SAME row is a plugin line or a vanilla echo
        # depending only on its TextPacket type. Keep the kind filter as well,
        # and keep 'text:chat' out of it -- that IS the Bedrock vanilla echo.
        #
        # The system-message kind is PER BOT:
        #   run-e2e.js (mineflayer)        -> 'messagestr'
        #   run-e2e-bedrock.js (bedrock-protocol) -> 'text:<type>'
        # The Bedrock bot never records 'messagestr' at all; its only inbound
        # kinds are text:raw / text:translation / text:chat / spawn / kicked /
        # error / close. A 'messagestr'-only gate could never match on nukkit or
        # pnx even when the round-trip demonstrably worked. Verified 2026-10-23
        # with a provisioned plugin config and a live backend: results.json held
        #   {"kind":"text:raw","text":"§c[全服] §7E2E_Bot_Bedrock§f: hello from e2e bot"}
        # and the harness still reported chatRoundTrip=False -> PARTIAL.
        $systemKinds = @('messagestr', 'systemChat', 'text:raw', 'text:translation',
                         'text:system', 'text:announcement')
        $chatPattern = '\[[^\]]+\]\s*' + [regex]::Escape($def.BotName) +
                       '\s*:\s*.*' + [regex]::Escape($def.ChatPhrase)
        $chatEvidence = (@(Select-BotRowsByPlainText $rows $chatPattern |
                          Where-Object { $_.kind -in $systemKinds })).Count -ge 1

        $result.l1Pass = $l1Evidence
        $result.chatRoundTrip = $chatEvidence
        if ($l1Evidence -and $chatEvidence) {
          $result.status = "PASS"
          if ($pf -eq 'pnx') {
            $result.notes = "Native PowerNukkitX $($bedrockLock.Build); protocol $($bedrockLock.BotMcVersion)"
          }
        } elseif ($l1Evidence -and $def.PluginConfig) {
          $result.status = "FAIL"; $result.notes = "plugin answered a command, but no NovaChat-formatted chat line reached the bot (chat round-trip not verified)"
        } elseif ($l1Evidence) {
          $result.status = "PARTIAL"; $result.notes = "plugin answered a command, but chat round-trip not echoed (no PluginConfig for this platform, so the plugin keeps its bundled backend defaults)"
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
  if ($pfResult.status -notin @('PASS', 'SKIPPED', 'SKIPPED-BY-DESIGN')) { $overallPass = $false }
  # A skip of either class ran no assertions, so it must not print an L1/chat
  # verdict. The two classes are named distinctly and the by-design one carries
  # its structural reason, so a reader can tell "cannot run here" from "the
  # environment blocked us this time" without parsing the exit code.
  if ($pfResult.status -eq "SKIPPED-BY-DESIGN") {
    Log "=== [$pf] RESULT: SKIPPED-BY-DESIGN (no assertions ran; by design, not a failure): $($pfResult.notes) ==="
  } elseif ($pfResult.status -eq "SKIPPED") {
    Log "=== [$pf] RESULT: SKIPPED (no assertions ran; environment blocked this run) ==="
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
  $isSkip = ($r.status -eq "SKIPPED" -or $r.status -eq "SKIPPED-BY-DESIGN")
  $l1 = if ($isSkip) { "N/A" } elseif ($r.l1Pass) { "PASS" } else { "FAIL" }
  $chat = if ($isSkip) { "N/A" } elseif ($r.chatRoundTrip) { "MATCH" } else { "NO-MATCH" }
  Log ("  {0,-10} | L1: {1,-4} | chat: {2,-9} | {3}" -f $r.platform, $l1, $chat, $r.status)
  if ($r.notes) { Log "             | notes: $($r.notes)" }
  # 'skipped' stays true for BOTH classes so consumers that only look at the
  # boolean keep working; 'skippedByDesign' is the discriminator the
  # orchestrator's per-row mapping reads (see Convert-ExitCodeToStatus). Exactly
  # one of the two flags is true for a skip row.
  $summaryTable += [PSCustomObject]@{
    platform = $r.platform
    status = $r.status
    skipped = $isSkip
    skippedByDesign = ($r.status -eq "SKIPPED-BY-DESIGN")
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

# The two skip classes are counted apart. Only an ENVIRONMENT block ('SKIPPED')
# is a strict-mode failure: a by-design skip (a proxy-only platform this
# harness structurally cannot drive) will never clear by retrying, so failing
# the night's run over it would be noise, not a signal.
$skippedResults   = @($allResults | Where-Object { $_.status -eq "SKIPPED" })
$byDesignResults  = @($allResults | Where-Object { $_.status -eq "SKIPPED-BY-DESIGN" })
$skippedSummary   = ($skippedResults  | ForEach-Object { $_.platform }) -join ", "
$byDesignSummary  = ($byDesignResults | ForEach-Object { $_.platform }) -join ", "
if (-not $overallPass) {
  $failed = ($allResults | Where-Object { $_.status -notin @('PASS', 'SKIPPED', 'SKIPPED-BY-DESIGN') } | ForEach-Object { $_.platform }) -join ", "
  Log "=== E2E FAILED for: $failed ==="
  if ($skippedResults.Count  -gt 0) { Log "=== also SKIPPED (environment blocked, no test ran): $skippedSummary ===" }
  if ($byDesignResults.Count -gt 0) { Log "=== also SKIPPED-BY-DESIGN (no assertions; by design): $byDesignSummary ===" }
  exit 1
}
if (($skippedResults.Count + $byDesignResults.Count) -eq $allResults.Count -and $allResults.Count -gt 0) {
  # Nothing ran anywhere. The exit code says WHICH kind of nothing: 3 when the
  # environment blocked us (may clear on a retry, strict mode fails it), 4 when
  # every skip was structural (strict mode does not fail it -- see the header).
  if ($skippedResults.Count -gt 0) {
    Log "=== NO PLATFORM ACTUALLY RAN (all SKIPPED; environment blocked): $skippedSummary ==="
    if ($byDesignResults.Count -gt 0) { Log "=== additionally SKIPPED-BY-DESIGN: $byDesignSummary ===" }
    exit 3
  }
  Log "=== NO PLATFORM ACTUALLY RAN, ALL BY DESIGN (not a failure): $byDesignSummary ==="
  exit 4
}
if ($skippedResults.Count -gt 0) {
  if ($RequireAllPlatforms) {
    Log "=== STRICT MODE: environment-blocked SKIPPED platform(s) count as failures: $skippedSummary ==="
    exit 1
  }
  Log "=== ALL REQUESTED NON-SKIPPED PLATFORMS PASSED; SKIPPED (environment blocked, no test ran): $skippedSummary ==="
} elseif ($byDesignResults.Count -gt 0) {
  # Strict mode does NOT fail a by-design skip: it is reported, not fatal.
  Log "=== ALL RUNNABLE PLATFORMS PASSED; SKIPPED-BY-DESIGN (no assertions; by design): $byDesignSummary ==="
} else {
  Log "=== ALL REQUESTED PLATFORMS PASSED ==="
}
exit 0
