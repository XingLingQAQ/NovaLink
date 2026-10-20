# ============================================================================
# run-joint.ps1 -- NovaChat cross-platform JOINT E2E orchestrator (CI-capable).
#
# WHAT IT PROVES
#   Two (or more) DIFFERENT Minecraft server platforms are connected to ONE
#   shared NovaLink backend, and a message a player sends on platform A is
#   actually DELIVERED to a player on platform B. That cross-process delivery is
#   the whole point of NovaChat; no single-platform harness can observe it,
#   because the evidence lives in two independent bot processes.
#
#   A Velvelocity proxy fronting the downstream Java servers is part of the
#   default topology: the proxy is where cross-server routing is most likely to
#   break, and the previous version of this script deliberately excluded it.
#
# HOW IT SCALES (round strategy, user-specified)
#   The harness never runs more than $MaxConcurrentServers server processes at
#   once (default 4: proxy + java server + modded server + one Bedrock server).
#   Instead it runs ROUNDS, each round being one topology:
#
#     round 1: proxy(velocity) + bukkit + fabric(mod) + bedrock-A(nukkit)
#     round 2: proxy(velocity) + bukkit + fabric(mod) + bedrock-B(pmmp)
#     round 3: proxy(velocity) + folia   + fabric(mod) + bedrock-A(nukkit)
#
#   Rounds 2/3 change exactly one leg relative to round 1, which is what makes a
#   failure attributable: same backend config, same clients, same bot plan, one
#   different server binary. -Platforms controls the legs:
#
#     -Platforms "velocity,bukkit,nukkit"      (default: java=velocity+bukkit,
#                                               mod=fabric, bedrock=nukkit)
#     -Platforms "velocity,fabric,nukkit"      (rotate the Java leg to fabric)
#     -Rounds 1                                (single topology; the CI mode)
#
# PATHS
#   NOTHING here is hardcoded to a machine. The repo root is derived from this
#   script's own location, server jar names come from test/versions.lock.ps1,
#   and every port is a parameter with a distinct default per role.
#
# EVIDENCE
#   Each round writes .e2e-artifacts/joint/<runId>/round-<n>/ with:
#     plan-<role>.json      the exact steps each bot was told to run
#     <role>.results.json   raw inbound traffic + sends + per-expect verdicts
#     <role>.stdout.log     bot console
#     joint-summary.json    the orchestrator's cross-process verdicts
#   A cross-server assertion PASSES only when the receiver's recorded inbound
#   traffic contains the sender's unique correlation token. The sender's own
#   claim is never taken at face value.
#
# EXIT CODES
#   0 = every round passed every cross-server assertion
#   1 = at least one assertion failed (or a required process never became ready)
#   2 = prereq failure (missing JDK / lock entry / bot deps / classpath)
#   3 = nothing ran (every requested round was skipped)
# ============================================================================
[CmdletBinding()]
param(
  # Comma-separated legs. Recognised platform names are the keys of
  # $PlatformDefs below; the legacy aliases (purpur->bukkit, pocketmine->pmmp,
  # powernukkitx->pnx) are accepted and normalised.
  [string]$Platforms = "velocity,bukkit,fabric,nukkit",

  # How many rounds to run and how the legs rotate. Round 1 is the -Platforms
  # topology verbatim. Rounds 2+ rotate ONLY the Bedrock leg (alternating the
  # bedrock platforms in -BedrockRotation), then ONLY the Java leg (walking the
  # java platforms in -JavaRotation). Both lists default from -Platforms so a
  # caller who names three platforms gets a sensible sweep without extra flags.
  [int]$Rounds = 3,
  [string]$BedrockRotation = "",
  [string]$JavaRotation = "",

  # Hard ceiling on concurrently running SERVER processes (proxy counts as one).
  # The round builder asserts this before starting anything; the parameter
  # exists so the constraint stays explicit instead of implicit in the topology.
  [int]$MaxConcurrentServers = 4,

  # Repo root. Derived from this script's location when empty -- a parameter
  # default cannot call Split-Path under [CmdletBinding()] (see the note in
  # test/bin/run-pmmp-e2e.ps1).
  [string]$RepoRoot = "",
  [string]$DistDir = "",          # server jar cache; <repo>/.e2e-artifacts/dist
  [string]$JointRoot = "",        # run output; <repo>/.e2e-artifacts/joint
  [string]$BotDir = "",           # bot deps; <repo>/test/bot (shared package.json)
  [string]$ClassPathFile = "",    # <repo>/.e2e-artifacts/novalink-core.classpath.txt

  # JDKs. Empty means "resolve from the environment" -- see Resolve-Jdk. The
  # previous version of this script pinned a scoop path, which is why it could
  # not run on CI.
  [string]$Jdk17 = "",
  [string]$Jdk21 = "",
  [string]$Jdk25 = "",

  # Ports. One distinct default per role so a leftover process from another
  # harness cannot collide with this one.
  [int]$BackendPort = 54720,
  [int]$BackendWsPort = 54730,
  [int]$ProxyPort = 54710,
  [int]$JavaPort = 54700,
  [int]$JavaRconPort = 54705,
  [int]$ModPort = 54701,
  [int]$ModRconPort = 54706,
  [int]$BedrockAPort = 19140,
  [int]$BedrockARconPort = 27021,
  [int]$BedrockBPort = 19141,
  [int]$BedrockBRconPort = 27022,

  [int]$ServerReadySec = 480,
  [int]$BotTimeoutSec = 300,

  # Proxy operating mode. "relay" is the historical topology: the proxy and
  # every downstream server each authenticate against the NovaLink backend.
  # "lite" makes the proxy self-contained (mode = "lite", lite.channels,
  # lite.storage.type = memory) and starts NO backend at all.
  #
  # Lite changes what the round can prove, so the round is asserted differently
  # -- see Invoke-LiteProbe. In particular no downstream plugin can participate:
  # there is no backend for them to connect to, and nothing in-tree carries
  # player chat from a downstream server to the proxy (no plugin messaging).
  [ValidateSet("relay", "lite")]
  [string]$ProxyMode = "relay",

  # Build the platform plugin/mod jars with Gradle before starting servers.
  # Off is useful when re-running against jars built moments ago.
  [switch]$SkipBuild,

  # Keep the run directories (logs/results) after a passing run. Failures are
  # always kept.
  [switch]$KeepArtifacts,

  # Resolve platforms, build the round list, run the prereq checks, then stop.
  # No process is started. Use this to validate a -Platforms/-Rounds combination
  # on a machine that cannot run the whole matrix (and in CI as a fast gate).
  [switch]$PlanOnly,

  # Run only joint-bot.js's --selftest: no server, no topology, no prereqs.
  # It exercises the effect-assertion machinery (expectEvent /
  # expectEventAbsent / expectKicked / expectState) against synthetic effect
  # rows, including negative controls that MUST fail. Exit code 0 means every
  # case behaved as expected.
  [switch]$SelfTest
)

$ErrorActionPreference = "Stop"

# --- normalise the process environment ---------------------------------------
# Windows treats environment names case-insensitively, but a process block can
# still carry two spellings of the same name (proxies are the common case:
# "NO_PROXY" and "no_proxy" both set by the surrounding tooling). Start-Process
# rebuilds the child block in a case-insensitive dictionary and throws
# "Item has already been added. Key in dictionary: 'NO_PROXY'" before the child
# even starts, which killed a round inside Invoke-FetchServer. Collapse each
# case-insensitive group to its upper-case spelling (keeping the value) so every
# later Start-Process call has a well-formed block.
$envNames = @([System.Environment]::GetEnvironmentVariables('Process').Keys)
$envGroups = $envNames | Group-Object { $_.ToUpperInvariant() } | Where-Object { $_.Count -gt 1 }
foreach ($group in $envGroups) {
  $keep = @($group.Group | Where-Object { $_ -ceq $group.Name })[0]
  if (-not $keep) { $keep = $group.Group[0] }
  foreach ($spelling in $group.Group) {
    if ($spelling -ceq $keep) { continue }
    [System.Environment]::SetEnvironmentVariable($spelling, $null, 'Process')
  }
}

# --- resolve derived paths ---------------------------------------------------
if (-not $RepoRoot)       { $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)) }
if (-not $DistDir)        { $DistDir = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $JointRoot)      { $JointRoot = Join-Path $RepoRoot ".e2e-artifacts/joint" }
if (-not $BotDir)         { $BotDir = Join-Path $RepoRoot "test/bot" }
if (-not $ClassPathFile)  { $ClassPathFile = Join-Path $RepoRoot ".e2e-artifacts/novalink-core.classpath.txt" }
$binDir  = Join-Path $RepoRoot "test/bin"
$jointBinDir = Join-Path $RepoRoot "test/joint/bots"
$confDir = Join-Path $RepoRoot "test/conf"
$jointBotScript = Join-Path $jointBinDir "joint-bot.js"

# --- logging -----------------------------------------------------------------
$script:RunId = (Get-Date -Format "yyyyMMdd-HHmmss")
$script:LogPath = Join-Path $JointRoot "joint-$($script:RunId).log"
New-Item -ItemType Directory -Force -Path $JointRoot | Out-Null

function Log([string]$msg) {
  $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg
  Write-Host $line
  try { Add-Content -Path $script:LogPath -Value $line -Encoding utf8 -ErrorAction SilentlyContinue } catch { }
}

# --- SHA-256 without Get-FileHash -------------------------------------------
# Get-FileHash lives in Microsoft.PowerShell.Utility, which a -NoProfile child
# powershell.exe on the GitHub runner does not always auto-load; the failure
# surfaces as CommandNotFoundException only after a download already succeeded.
# System.Security.Cryptography needs no module at all.
function Get-Sha256Hex([string]$file) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $stream = [System.IO.File]::OpenRead($file)
    try {
      return ([System.BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToUpperInvariant()
    } finally { $stream.Dispose() }
  } finally { $sha.Dispose() }
}

function Test-PortOpen([int]$port, [int]$timeoutMs = 1500) {
  try {
    $client = New-Object System.Net.Sockets.TcpClient
    $ok = $client.ConnectAsync("127.0.0.1", $port).Wait($timeoutMs) -and $client.Connected
    $client.Close()
    return $ok
  } catch { return $false }
}

# Read a log file as UTF-8 regardless of PowerShell version. Get-Content -Raw
# decodes with the ANSI code page on 5.1, which turns Chinese server output into
# mojibake and makes -match against it silently fail.
function Read-LogText([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { return "" }
  # Open explicitly with FileShare.ReadWrite: the log files are being appended
  # to by a StreamWriter for the whole run, and File.ReadAllText's default
  # sharing mode makes this throw "file is being used by another process". The
  # caller treats "" as "no marker yet", so an IOException here silently
  # converts a healthy boot into a timeout.
  try {
    $share = [System.IO.FileShare]::ReadWrite
    $stream = [System.IO.FileStream]::new($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, $share)
    try {
      $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
      try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }
  } catch { return "" }
}

function Wait-LogMarker([string]$file, [string]$pattern, [int]$timeoutSec, [string]$label) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $text = Read-LogText $file
    if ($text -and ($text -match $pattern)) {
      Log "  $label ready (matched: $pattern)"
      return $true
    }
    Start-Sleep -Seconds 2
  }
  Log "  TIMEOUT waiting for $label (pattern: $pattern) in $file"
  return $false
}

function Write-LogTail([string]$path, [int]$lines, [string]$label) {
  if (-not (Test-Path -LiteralPath $path)) { return }
  Log "  --- $label ($path) ---"
  Get-Content -LiteralPath $path -Tail $lines -Encoding UTF8 -ErrorAction SilentlyContinue |
    ForEach-Object { Log "    $_" }
}

# --- JDK resolution ----------------------------------------------------------
# Order of preference, per major version:
#   1. an explicit -JdkNN argument
#   2. the env var actions/setup-java exports for a multi-version request
#   3. a JAVA_HOME whose `release` file names the requested major
#   4. the author's local scoop layout (dev convenience only)
# The previous version of this script hardcoded (4) and nothing else, which is
# exactly why it could not run on CI.
function Resolve-Jdk {
  param([string]$Explicit, [string]$EnvName, [int]$Major, [string[]]$LocalCandidates)
  $candidates = @()
  if ($Explicit) { $candidates += $Explicit }
  $envValue = [Environment]::GetEnvironmentVariable($EnvName)
  if ($envValue) { $candidates += $envValue }
  if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
  $candidates += $LocalCandidates
  foreach ($c in $candidates) {
    if (-not $c) { continue }
    $exe = Join-Path $c "bin/java.exe"
    if (-not (Test-Path -LiteralPath $exe)) { $exe = Join-Path $c "bin/java" }
    if (-not (Test-Path -LiteralPath $exe)) { continue }
    $release = Join-Path $c "release"
    if (Test-Path -LiteralPath $release) {
      $content = Read-LogText $release
      if ($content -match 'JAVA_VERSION="(\d+)') {
        if ([int]$Matches[1] -ne $Major) { continue }
        return $c
      }
    }
    if ($c -match "(?:jdk|jdk-|temurin)?[-]?$Major(?:[.\-_]|$)") { return $c }
    if ($c -eq $Explicit) { return $c }
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

# Explicit switch, NOT "$Jdk25 or else $Jdk21". The old ternary silently mapped
# Jdk25 onto Jdk21, so a Velocity/Folia leg booted on 21 and died instantly with
# UnsupportedClassVersionError while the harness waited out the full ready
# timeout for a marker that could never appear.
function Get-JdkPath([string]$jdkVar) {
  switch ($jdkVar) {
    'Jdk25' { return $Jdk25 }
    'Jdk17' { return $Jdk17 }
    default { return $Jdk21 }
  }
}

# --- platform definitions ----------------------------------------------------
# One entry per leg the joint harness can start. LockName is the
# test/versions.lock.ps1 entry that owns the binary; the jar itself is resolved
# from the lock at run time, so a lock bump moves this harness with it.
#
# Jdk is the RUNTIME requirement (verified by booting each jar, see
# test/bin/multiplatform/start-server.ps1). BuildJdk is what Gradle needs.
# Proxy=$true marks the leg that fronts the Java servers instead of being
# joined directly by a bot.
$PlatformDefs = @{
  bukkit = @{
    LockName = 'purpur'; Kind = 'java'; SubDir = 'bukkit'
    Jdk = 'Jdk21'; ReadyPattern = '(?i)Done \('
    GradleTask = ':NovaChat:Plugin:bukkit:build'; BuildJdk = 'Jdk21'
    JarGlob = 'NovaChat/Plugin/bukkit/build/libs/bukkit-*.jar'
    PluginsSubdir = 'plugins'; ClientId = 'E2E_JobBukkit'
  }
  folia = @{
    LockName = 'folia'; Kind = 'java'; SubDir = 'folia'
    Jdk = 'Jdk25'; ReadyPattern = '(?i)Done \('
    GradleTask = ':NovaChat:Plugin:folia:build'; BuildJdk = 'Jdk25'
    JarGlob = 'NovaChat/Plugin/folia/build/libs/NovaChat-Folia-*.jar'
    PluginsSubdir = 'plugins'; ClientId = 'E2E_JobFolia'
  }
  fabric = @{
    LockName = 'fabric'; Kind = 'mod'; SubDir = 'fabric'
    Jdk = 'Jdk21'; ReadyPattern = '(?i)Done \('
    # Built through the E2E init script: the module's own remapJar artifact is
    # the loader shim only, and a real server rejects it with ClassNotFound on
    # com.nova.chat.mod.platform.Platform (see test/bin/fabric-fatjar.init.gradle).
    GradleTask = ':NovaChat:MOD:fabric:fabricE2eJar'; BuildJdk = 'Jdk21'
    InitScript = 'fabric-fatjar.init.gradle'
    JarGlob = 'NovaChat/MOD/fabric/build/libs/nova-chat-fabric-e2e-*.jar'
    PluginsSubdir = 'mods'; ClientId = 'E2E_JobFabric'
  }
  nukkit = @{
    LockName = 'nukkit'; Kind = 'bedrock'; SubDir = 'nukkit'
    Jdk = 'Jdk21'; ReadyPattern = '(?i)(Done|server.*started|Nukkit.*started|Loading.*complete|Listening)'
    # build produces a THIN jar (no shared layers): loading it in a real server
    # dies with NoClassDefFoundError com/nova/chat/client/network/AbstractPlatformNetworkClient.
    # The E2E fat-jar task bundles :NovaChat:common + :NovaChat:client-core +
    # snakeyaml into a self-contained plugin.
    GradleTask = ':NovaChat:Bedrock:nukkit:nukkitE2eJar'; BuildJdk = 'Jdk21'
    InitScript = 'plugin-e2e-fatjar.init.gradle'
    InitProps = @{ e2ePluginProject = ':NovaChat:Bedrock:nukkit'; e2ePluginTask = 'nukkitE2eJar'; e2ePluginBaseName = 'novachat-nukkit' }
    JarGlob = 'NovaChat/Bedrock/nukkit/build/libs/novachat-nukkit-*.jar'
    PluginsSubdir = 'plugins'; ClientId = 'E2E_JobNukkit'
    # Both engines below run BDS-protocol 1.26.x; bedrock-protocol rejects a
    # client one revision behind with disconnectionScreen.outdatedClient.
    BotMcVersion = '1.26.40'
  }
  pmmp = @{
    LockName = 'pocketmine'; Kind = 'bedrock'; SubDir = 'pmmp'
    Jdk = $null; ReadyPattern = '(?i)Done'
    JarGlob = ''; PluginsSubdir = 'plugins'; ClientId = 'E2E_JobPmmp'
    BotMcVersion = '1.26.40'
  }
  pnx = @{
    LockName = 'nukkit'; Kind = 'bedrock'; SubDir = 'pnx'
    Jdk = 'Jdk21'; ReadyPattern = '(?i)(Done|server.*started|Nukkit.*started|Loading.*complete|Listening)'
    GradleTask = ':NovaChat:Bedrock:pnx:build'; BuildJdk = 'Jdk21'
    JarGlob = 'NovaChat/Bedrock/pnx/build/libs/NovaChat-PNX-*.jar'

    PluginsSubdir = 'plugins'; ClientId = 'E2E_JobPnx'
    BotMcVersion = '1.26.40'
  }
  velocity = @{
    LockName = 'velocity'; Kind = 'proxy'; SubDir = 'velocity'
    Jdk = 'Jdk25'; ReadyPattern = '(?i)(Listening on|Done \()'
    GradleTask = ':NovaChat:Proxy:velocity:build'; BuildJdk = 'Jdk25'
    JarGlob = 'NovaChat/Proxy/velocity/build/libs/velocity-*.jar'
    PluginsSubdir = 'plugins'; Proxy = $true
  }
  bungee = @{
    LockName = 'waterfall'; Kind = 'proxy'; SubDir = 'bungee'
    Jdk = 'Jdk21'; ReadyPattern = '(?i)Listening'
    GradleTask = ':NovaChat:Proxy:bungee:build'; BuildJdk = 'Jdk21'
    JarGlob = 'NovaChat/Proxy/bungee/build/libs/bungee-*.jar'
    PluginsSubdir = 'plugins'; Proxy = $true
  }
}

# Aliases so callers can use whichever name they already know.
$PlatformAliases = @{
  purpur = 'bukkit'; sponge = ''; pocketmine = 'pmmp'; pocketminemp = 'pmmp'
  powernukkitx = 'pnx'; mod = 'fabric'; waterfall = 'bungee'
}

function Resolve-PlatformName([string]$name) {
  $n = $name.Trim().ToLowerInvariant()
  if ($PlatformAliases.ContainsKey($n)) { return $PlatformAliases[$n] }
  return $n
}

# --- process management ------------------------------------------------------
# Every process the harness starts is tracked here and torn down in reverse
# order. A trap + PowerShell.Exiting subscription guarantee teardown even on
# Ctrl+C or a terminating error, so a failed round never leaves a JVM holding a
# port for the next round.
$script:Pids = [System.Collections.Generic.List[int]]::new()
$script:PidLabels = @{}
# Backends are launched through System.Diagnostics.Process with a kept-open
# stdin pipe (see Start-JvmProcess) so this map lets teardown close it.
$script:BackendProcesses = @{}
# One entry per Start-JvmProcess call: the StreamWriter pair draining that
# process's stdout/stderr. They stay open for the process's whole life (see the
# note in Start-JvmProcess) and are closed in Teardown-All.
$script:DrainWriters = @{}
# Strong references to every Process object this script launches. Without them
# the GC can finalize a Process whose redirected stdin pipe is still load
# bearing -- see the note in Start-JvmProcess.
$script:LiveProcesses = @{}

function Track-Pid([int]$procId, [string]$label) {
  if ($procId -le 0) { return }
  $script:Pids.Add($procId) | Out-Null
  $script:PidLabels[$procId] = $label
}

function Stop-TrackedPid([int]$procId, [string]$label) {
  if ($procId -le 0) { return }
  try {
    $p = Get-Process -Id $procId -ErrorAction Stop
    Log "    stopping $label (pid=$procId)"
    try { $p.CloseMainWindow() | Out-Null } catch { }
    Start-Sleep -Milliseconds 400
    if (-not $p.HasExited) { Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue }
  } catch {
    # Already exited -- the common case for a bot that finished its plan.
  }
}

function Teardown-All {
  if ($script:Pids.Count -eq 0) { return }
  Log "  teardown: stopping $($script:Pids.Count) process(es)"
  # Close backend stdin FIRST: NovaLinkMain's console loop treats stdin EOF as
  # Ctrl+D and exits cleanly, which flushes its pending writes. Stop-Process
  # would kill it mid-write instead.
  foreach ($key in @($script:BackendProcesses.Keys)) {
    $proc = $script:BackendProcesses[$key]
    if ($proc -and -not $proc.HasExited) {
      try { $proc.StandardInput.Close() } catch { }
      Start-Sleep -Milliseconds 600
    }
    Remove-Item (Join-Path "Env:" "NovaLinkDrain_$key") -ErrorAction SilentlyContinue
  }
  Get-EventSubscriber -ErrorAction SilentlyContinue |
    Where-Object { $_.SourceIdentifier -like 'NovaLinkJoint*' } |
    ForEach-Object { Unregister-Event -SubscriptionId $_.SubscriptionId -ErrorAction SilentlyContinue }

  for ($i = $script:Pids.Count - 1; $i -ge 0; $i--) {
    $procId = $script:Pids[$i]
    Stop-TrackedPid $procId $script:PidLabels[$procId]
  }

  # Flush + close the log writers LAST, after the processes are gone: closing
  # them earlier would drop whatever the process wrote in its final moments
  # (the shutdown lines a failure diagnosis depends on).
  foreach ($key in @($script:DrainWriters.Keys)) {
    foreach ($w in $script:DrainWriters[$key]) {
      if ($w) {
        try { $w.Flush(); $w.Dispose() } catch { }
      }
    }
  }
  $script:DrainWriters = @{}
  $script:LiveProcesses = @{}

  $script:Pids.Clear()
  $script:PidLabels.Clear()
  $script:BackendProcesses = @{}
}

trap {
  Log "TRAP: $($_.Exception.Message)"
  if ($_.ScriptStackTrace) { Log "  at: $($_.ScriptStackTrace)" }
  Teardown-All
  break
}
$null = Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action { Teardown-All }

# Launch a JVM whose stdin pipe stays OPEN for the whole run.
#
# Start-Process gives the child no usable stdin: the JVM sees EOF immediately.
# NovaLinkMain runs BackendConsole on its main thread and treats readLine()
# throwing EndOfFileException as Ctrl+D, so the backend logs "started",
# prints "EOF received, shutting down...", and exits ~1s later. Every plugin
# connection then gets refused and every /nc command answers NC-503.
# System.Diagnostics.Process with RedirectStandardInput = $true hands the JVM a
# pipe we never write to and never close, so readLine() blocks instead.
function Start-JvmProcess {
  param(
    [string]$JavaExe,
    [string[]]$Arguments,
    [string]$WorkingDirectory,
    [string]$StdoutPath,
    [string]$StderrPath
  )
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $JavaExe
  # Each argument is quoted individually; Join-String is PowerShell 7+ and 5.1
  # is still a supported runner. The JVM needs quoting for paths with spaces.
  $psi.Arguments = (($Arguments | ForEach-Object { '"' + ($_ -replace '"', '\"') + '"' }) -join ' ')
  $psi.WorkingDirectory = $WorkingDirectory
  $psi.UseShellExecute = $false
  $psi.RedirectStandardInput = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.CreateNoWindow = $true
  $proc = [System.Diagnostics.Process]::Start($psi)

  # Drain the redirected pipes into files on background events. Reading
  # synchronously (ReadToEnd) would block until exit and starve the readiness
  # loop; not draining at all would eventually block the JVM once the OS pipe
  # buffer filled.
  #
  # The writer is a StreamWriter held OPEN for the life of the process, NOT
  # Add-Content inside the event action. A Bukkit server with NovaChat's debug
  # logging on emits thousands of lines per minute, and Add-Content reopens the
  # file for every single line: under that rate PowerShell 5.1's event queue
  # drops events and the handler races other writers, which produced a 3.9 MB
  # log containing only KeepAlive lines and NO `Done (…)` line at all -- so the
  # readiness poll timed out on a server that had in fact booted. One open
  # writer with AutoFlush keeps every line and makes it visible immediately.
  $tag = "NovaLinkJoint$($proc.Id)"
  Remove-Item -LiteralPath $StdoutPath, $StderrPath -Force -ErrorAction SilentlyContinue
  # New-Object with a multi-argument ctor is ambiguous here (PowerShell may bind
  # the argument array as one object); the explicit [type]::new ctor is
  # unambiguous on both 5.1 and 7+. Creating the writer can also fail if the
  # parent directory is missing, so surface that instead of silently returning a
  # process whose output goes nowhere -- which is exactly how a round reported
  # "backend did not become ready" with no backend log at all.
  $outDir = Split-Path -Parent $StdoutPath
  if ($outDir) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
  # FileShare.ReadWrite is REQUIRED, not cosmetic. StreamWriter's constructor
  # opens the file with FileShare.Read at best (and no sharing on some
  # runtimes), so the readiness poll's ReadAllText could not open the log at all
  # and `Read-LogText` swallowed the IOException into "" -- the loop then waited
  # out its full 60s on a backend that had logged "started successfully" eight
  # seconds in. Sharing read+write lets the poll read a file that is being
  # appended to.
  $share = [System.IO.FileShare]::ReadWrite
  try {
    $outStream = [System.IO.FileStream]::new($StdoutPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, $share)
    $errStream = [System.IO.FileStream]::new($StderrPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, $share)
    $outWriter = [System.IO.StreamWriter]::new($outStream, [System.Text.UTF8Encoding]::new($false))
    $errWriter = [System.IO.StreamWriter]::new($errStream, [System.Text.UTF8Encoding]::new($false))
    $outWriter.AutoFlush = $true
    $errWriter.AutoFlush = $true
  } catch {
    Log "  ERROR: could not open log writers for $tag ($StdoutPath): $($_.Exception.Message)"
    try { $proc.Kill() } catch { }
    return $null
  }
  $script:DrainWriters[$tag] = @($outWriter, $errWriter)
  # Hold a strong reference to the Process object itself. Its finalizer closes
  # the redirected stdin handle, which the backend reads as Ctrl+D and shuts
  # down cleanly (`Console loop exited; NovaLink main thread returning.`) about
  # two seconds after "started successfully" -- the round then reports "backend
  # did not become ready" even though the log shows a healthy boot followed by a
  # graceful stop. Keeping the object alive for the script's lifetime keeps the
  # pipe open, which is the whole reason this launcher exists.
  $script:LiveProcesses[$tag] = $proc

  $null = Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -SourceIdentifier "$tag-out" -Action {
    if ($EventArgs.Data -ne $null) {
      try { $Event.MessageData.WriteLine($EventArgs.Data) } catch { }
    }
  } -MessageData $outWriter
  $null = Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -SourceIdentifier "$tag-err" -Action {
    if ($EventArgs.Data -ne $null) {
      try { $Event.MessageData.WriteLine($EventArgs.Data) } catch { }
    }
  } -MessageData $errWriter
  $proc.BeginOutputReadLine()
  $proc.BeginErrorReadLine()
  return $proc
}

# Launch a process whose stdin does not matter (bots, and every server except
# the backend). Start-Process is fine here and keeps the code simple; the
# backend path above exists only because of the stdin-EOF shutdown.
function Start-PlainProcess {
  param(
    [string]$FilePath,
    [string[]]$Arguments,
    [string]$WorkingDirectory,
    [string]$StdoutPath,
    [string]$StderrPath
  )
  Remove-Item $StdoutPath, $StderrPath -ErrorAction SilentlyContinue
  return Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory `
    -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath -PassThru -NoNewWindow
}

# --- minimal RCON client -----------------------------------------------------
# Source RCON, enough for "op <player>", "stop", and /nc moderation commands.
# Inlined (rather than calling test/bin/rcon.ps1) so the joint harness has no
# cross-directory script dependency and can be read top-to-bottom.
function Send-Rcon {
  param([int]$Port, [string]$Password, [string]$Command, [int]$TimeoutMs = 5000)
  $client = $null
  try {
    $client = New-Object System.Net.Sockets.TcpClient
    if (-not $client.ConnectAsync("127.0.0.1", $Port).Wait($TimeoutMs)) { return $null }
    $stream = $client.GetStream()
    $stream.ReadTimeout = $TimeoutMs
    $enc = [System.Text.Encoding]::UTF8

    $writePacket = {
      param($id, $type, $payload)
      $body = New-Object System.IO.MemoryStream
      $idBytes = [BitConverter]::GetBytes([int]$id); $body.Write($idBytes, 0, 4)
      $typeBytes = [BitConverter]::GetBytes([int]$type); $body.Write($typeBytes, 0, 4)
      $pbytes = $enc.GetBytes($payload); $body.Write($pbytes, 0, $pbytes.Length)
      $body.WriteByte(0); $body.WriteByte(0)
      $payloadBytes = $body.ToArray()
      $lenBytes = [BitConverter]::GetBytes([int]$payloadBytes.Length)
      $stream.Write($lenBytes, 0, 4)
      $stream.Write($payloadBytes, 0, $payloadBytes.Length)
      $stream.Flush()
    }
    $readPacket = {
      $reader = New-Object System.IO.BinaryReader $stream
      $len = $reader.ReadUInt32()
      $buf = $reader.ReadBytes($len)
      if ($len -le 10) { return "" }
      return $enc.GetString($buf, 8, $len - 10).TrimEnd([char]0)
    }

    & $writePacket 3 3 $Password
    $null = & $readPacket
    & $writePacket 10 2 $Command
    return (& $readPacket)
  } catch {
    Log "    RCON failed ($Port): $($_.Exception.Message)"
    return $null
  } finally {
    if ($client) { try { $client.Close() } catch { } }
  }
}

# --- topology planning -------------------------------------------------------
# -Platforms is parsed into three legs:
#   Proxy   -- at most one; fronts the Java servers (velocity/waterfall).
#   Java    -- the "bukkit-style" legs (bukkit/folia). At most one per round:
#              they share the proxy's downstream slot and the same bot plan.
#   Mod     -- fabric/quilt-style loader servers. At most one per round.
#   Bedrock -- nukkit/pmmp/pnx. At most one per round: they are UDP servers on
#              the same bot transport and adding a second buys little.
#   Anything else (endstone/levilamina/sponge) is rejected with a clear message
#   rather than silently ignored.
function Split-PlatformLegs([string[]]$names) {
  $legs = @{ Proxy = $null; Java = @(); Mod = @(); Bedrock = @() }
  $unknown = @()
  foreach ($raw in $names) {
    $name = Resolve-PlatformName $raw
    if (-not $name) { continue }
    if (-not $PlatformDefs.ContainsKey($name)) { $unknown += $raw; continue }
    switch ($PlatformDefs[$name].Kind) {
      'proxy'   { $legs.Proxy = $name }
      'java'    { $legs.Java += $name }
      'mod'     { $legs.Mod += $name }
      'bedrock' { $legs.Bedrock += $name }
      default   { $unknown += $raw }
    }
  }
  return @{ Legs = $legs; Unknown = $unknown }
}

# Build the ordered list of rounds, matching the documented strategy:
#
#   round 1: proxy + java + mod + bedrock[0]
#   round 2: proxy + java + mod + bedrock[1]   <- change the Bedrock leg
#   round 3: proxy + java[1] + mod + bedrock[0] <- change the Java leg
#   round 4: proxy + java[0] + mod + bedrock[1]
#   ... alternating, so a failure is attributable to the leg that moved.
#
# Rotation lists are consumed with a per-kind cursor that advances ONLY when
# that kind's leg is the one being rotated; a list with one entry therefore
# repeats (the right behaviour for -Rounds 1 or for a caller who named a single
# platform of that kind).
function Build-Rounds([hashtable]$legs, [string[]]$bedrockSweep, [string[]]$javaSweep, [int]$count) {
  $roundList = @()
  $bedrockList = @($bedrockSweep | Where-Object { $_ })
  $javaList = @($javaSweep | Where-Object { $_ })
  if ($bedrockList.Count -eq 0) { $bedrockList = @($legs.Bedrock) }
  if ($javaList.Count -eq 0) { $javaList = @($legs.Java) }

  $modLeg = if ($legs.Mod.Count -gt 0) { $legs.Mod[0] } else { $null }

  for ($i = 0; $i -lt $count; $i++) {
    $javaLeg = if ($javaList.Count -gt 0) { $javaList[0] } else { $null }
    $bedrockLeg = if ($bedrockList.Count -gt 0) { $bedrockList[0] } else { $null }

    if ($i -gt 0) {
      if ($i % 2 -eq 1) {
        # Odd index = change the Bedrock leg. Round 2 is the first such round
        # (i=1) and must move to the SECOND bedrock platform, since round 1
        # already used the first.
        $bedrockLeg = $bedrockList[([int](($i - 1) / 2) + 1) % $bedrockList.Count]
      } else {
        # Even index (>0) = change the Java leg: round 3 takes index 1,
        # round 5 takes index 2, ...
        $javaLeg = $javaList[([int](($i - 2) / 2) + 1) % $javaList.Count]
      }
    }

    $serverCount = @($legs.Proxy, $javaLeg, $modLeg, $bedrockLeg | Where-Object { $_ }).Count
    $roundList += [PSCustomObject]@{
      Index = $i + 1
      Proxy = $legs.Proxy
      Java = $javaLeg
      Mod = $modLeg
      Bedrock = $bedrockLeg
      ServerCount = $serverCount
    }
  }
  return $roundList
}

function Format-RoundLabel($round) {
  $parts = @()
  if ($round.Proxy) { $parts += $round.Proxy }
  if ($round.Java) { $parts += $round.Java }
  if ($round.Mod) { $parts += $round.Mod }
  if ($round.Bedrock) { $parts += $round.Bedrock }
  return ($parts -join ' + ')
}

# --- build the platform artifacts -------------------------------------------
# One Gradle invocation for all legs. gradlew writes banner lines to stderr
# ("Picked up JAVA_TOOL_OPTIONS", Fabric Loom's "Cannot remap modifiers") and
# under $ErrorActionPreference = 'Stop' the call operator turns each into a
# terminating NativeCommandError that kills the run before Gradle even starts.
# Drop to Continue for exactly this call and read the exit code explicitly; a
# real build failure is still caught.
function Invoke-GradleBuild($roundList, [string[]]$requestedPlatforms) {
  # Platforms whose build is a plain task with no -P properties go in ONE Gradle
  # invocation. Platforms driven by an init script that reads -P properties get
  # their OWN invocation: the properties name one project + one task, so two of
  # them cannot share a process without clobbering each other.
  $tasks = [System.Collections.Generic.List[string]]::new()
  $initScripts = [System.Collections.Generic.List[string]]::new()
  $perPlatformCalls = @()
  $built = @{}

  foreach ($pf in $requestedPlatforms) {
    $name = Resolve-PlatformName $pf
    if (-not $name -or -not $PlatformDefs.ContainsKey($name)) { continue }
    if ($built.ContainsKey($name)) { continue }
    $def = $PlatformDefs[$name]
    if (-not $def.GradleTask) { continue }
    $built[$name] = $true
    if ($def.InitProps) {
      $perPlatformCalls += [PSCustomObject]@{
        Name = $name
        Task = $def.GradleTask
        Init = (Join-Path $binDir $def.InitScript)
        Props = $def.InitProps
      }
      continue
    }
    $tasks.Add($def.GradleTask) | Out-Null
    if ($def.InitScript) {
      $init = Join-Path $binDir $def.InitScript
      if (-not $initScripts.Contains($init)) { $initScripts.Add($init) | Out-Null }
    }
  }

  if ($tasks.Count -eq 0 -and $perPlatformCalls.Count -eq 0) {
    Log "  no Gradle tasks to run (all requested platforms ship prebuilt plugin sources)"
    return $true
  }

  # The fabric leg's build JDK is 21 (loom refuses < 21) and velocity/folia need
  # 25 for the toolchain. Gradle picks the toolchain from the requested
  # languageVersion, so one launcher JVM is enough as long as every required
  # JDK is discoverable. Point it at JDK 25 when available (the highest), and
  # blank the dev-machine toolchain override so auto-detection finds the
  # setup-java JDKs on CI.
  $launcherJdk = if ($Jdk25) { $Jdk25 } elseif ($Jdk21) { $Jdk21 } else { $null }
  if (-not $launcherJdk) {
    Log "  ERROR: no usable JDK to launch Gradle"
    return $false
  }

  $isWin = $IsWindows -or $env:OS -eq "Windows_NT"
  $gradleW = if ($isWin) { Join-Path $RepoRoot "gradlew.bat" } else { Join-Path $RepoRoot "gradlew" }
  $buildLog = Join-Path $JointRoot "gradle-build.out.log"
  $buildErr = Join-Path $JointRoot "gradle-build.err.log"

  $args = @()
  $args += $tasks
  $args += @("-x", "test", "--console=plain")
  $args += @("-Porg.gradle.java.installations.paths=", "-Dorg.gradle.java.installations.paths=")
  foreach ($init in $initScripts) { $args += @("--init-script", $init) }

  if ($tasks.Count -gt 0) { Log "  gradlew $($tasks -join ' ')" }
  foreach ($call in $perPlatformCalls) {
    Log "  gradlew $($call.Task) (init script: $(Split-Path -Leaf $call.Init))"
    $callArgs = @($call.Task, "-x", "test", "--console=plain",
      "-Porg.gradle.java.installations.paths=", "-Dorg.gradle.java.installations.paths=",
      "--init-script", $call.Init)
    foreach ($key in $call.Props.Keys) {
      $callArgs += ("-P{0}={1}" -f $key, $call.Props[$key])
    }
    $savedJavaHome2 = $env:JAVA_HOME
    $savedEap2 = $ErrorActionPreference
    $callExit = 1
    try {
      $env:JAVA_HOME = $launcherJdk
      $ErrorActionPreference = "Continue"
      Push-Location $RepoRoot
      try {
        & $gradleW @callArgs 1>$buildLog 2>$buildErr
        $callExit = $LASTEXITCODE
      } finally { Pop-Location }
    } catch {
      Log "  gradle invocation threw: $($_.Exception.Message)"
      $callExit = 1
    } finally {
      $ErrorActionPreference = $savedEap2
      $env:JAVA_HOME = $savedJavaHome2
    }
    if ($callExit -ne 0) {
      Log "  ERROR: $($call.Name) build failed (exit=$callExit; see $buildErr)"
      Get-Content $buildErr -ErrorAction SilentlyContinue | Select-Object -Last 25 | ForEach-Object { Log "    $_" }
      return $false
    }
  }
  if ($tasks.Count -eq 0) { return $true }

  $savedJavaHome = $env:JAVA_HOME
  $savedPath = $env:PATH
  $savedEap = $ErrorActionPreference
  $exit = 1
  try {
    $env:JAVA_HOME = $launcherJdk
    $env:PATH = "$launcherJdk\bin;$savedPath"
    $ErrorActionPreference = "Continue"
    Push-Location $RepoRoot
    try {
      & $gradleW @args 1>$buildLog 2>$buildErr
      $exit = $LASTEXITCODE
    } finally { Pop-Location }
  } catch {
    Log "  gradle invocation threw: $($_.Exception.Message)"
    $exit = 1
  } finally {
    $ErrorActionPreference = $savedEap
    $env:JAVA_HOME = $savedJavaHome
    $env:PATH = $savedPath
  }

  if ($exit -ne 0) {
    Log "  ERROR: gradle build failed (exit=$exit; see $buildErr)"
    Get-Content $buildErr -ErrorAction SilentlyContinue | Select-Object -Last 25 | ForEach-Object { Log "    $_" }
    return $false
  }
  return $true
}

# Resolve the artifact to install for one platform: prefer an E2E-built jar
# (glob matched, newest first) and fall back to the platform's declared JarGlob.
function Resolve-PluginArtifact([string]$platform) {
  $def = $PlatformDefs[$platform]
  if (-not $def.JarGlob) { return $null }
  $glob = Join-Path $RepoRoot $def.JarGlob
  $jar = Get-ChildItem $glob -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($jar) { return $jar.FullName }
  return $null
}

# Install a platform's plugin/mod into a server directory.
function Install-Plugin([string]$platform, [string]$serverDir) {
  $def = $PlatformDefs[$platform]
  if (-not $def.PluginsSubdir) { return $true }
  $jar = Resolve-PluginArtifact $platform
  if (-not $jar) {
    Log "  WARN: no artifact for $platform (glob: $($def.JarGlob)); starting the server without the plugin"
    return $false
  }
  $dir = Join-Path $serverDir $def.PluginsSubdir
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Copy-Item $jar -Destination $dir -Force
  Log "  installed $(Split-Path -Leaf $jar) -> $dir"

  # Fabric refuses to load any mod that declares "depends: fabric-api" without
  # the API jar in mods/ (fabric.mod.json here does). The runtime download in
  # Invoke-FetchFabricRuntime fetches it, but nothing used to copy it into the
  # server, so the leg could never actually load the mod. The E2E jar does not
  # bundle it either (verified: 0 net/fabricmc entries in the jar).
  if ($platform -eq 'fabric') {
    $api = Join-Path $DistDir $FabricRuntime.ApiFile
    if (Test-Path -LiteralPath $api) {
      Copy-Item $api -Destination $dir -Force
      Log "  installed fabric-api $(Split-Path -Leaf $api) -> $dir"
    } else {
      Log "  WARN: fabric-api jar missing at $api; the Fabric server will refuse to load novachat"
    }
  }
  return $true
}

# --- backend -----------------------------------------------------------------
# The backend config is written verbatim (not via the shared template) because
# the joint run needs THREE distinct client identities -- one per server leg --
# and the template only carries one. Every password is generated per run.
function Write-BackendConfig([string]$path, $clients, [string]$superAdminPassword) {
  $clientBlocks = @()
  foreach ($c in $clients) {
    $clientBlocks += @"
  - username: "$($c.Username)"
    password: "$($c.Password)"
    display_name: "$($c.DisplayName)"
"@
  }
  $body = @"
# NovaLink backend config for the JOINT E2E run (generated per run).
server:
  bind-address: 127.0.0.1
  port: $BackendPort
  websocket-port: $BackendWsPort
  secret-key: "joint-e2e-secret-$($script:RunId)"
  worker-threads: 2
  locale: en_US
  insecure-allow-plaintext: true

database:
  type: memory

security:
  allowed-ips:
    - 127.0.0.1
  ip-ban-duration: 300

# Populated so the announce/title scenarios can exercise the real super-admin
# path. The previous version of this harness left this empty, which made
# NC-403 unreachable-by-design and got recorded as a product bug (BUG-J4).
super-admins:
  - uuid: "$($script:SuperAdminUuid)"
    password: "$superAdminPassword"
    username: "E2E_SuperAdmin"

panel-users: []

debug: true

global_channels:
  global:
    display_name: "Global"
    permission: "novachat.channel.global"
    max_capacity: 1000

clients:
$($clientBlocks -join "`r`n")

features:
  filter-enabled: false
  # ON for the relay identity checks: MessagePipeline persists each delivered
  # message with the clientId stamped from the CARRYING connection
  # (NovaLinkCore's ChatMessagePacket handler -> packet.setClientId), and
  # GET /api/messages returns it. That is the only per-message attribution the
  # backend exposes (it logs none at INFO), and it is what
  # Assert-RelayAttributionAndDelivery reads to prove which server's link
  # carried a chat line. database.type = memory, so the records live only for
  # this run.
  message-log-enabled: true
  cross-server-chat-enabled: true
  private-messages-enabled: true
  message-log-retention-days: 1

filter:
  words: []
  patterns: []
"@
  [System.IO.File]::WriteAllText($path, $body, (New-Object System.Text.UTF8Encoding $false))
}

# Start the shared backend and block until it is genuinely serving.
#
# Readiness needs BOTH the "started successfully" log line AND an accepting TCP
# port. The line alone is not enough: it appears before the console loop notices
# a stdin EOF, so a one-poll match would declare ready a backend that is already
# exiting (that is the NC-503 every plugin then reports).
function Start-Backend([string]$configPath, [string]$workDir, [string]$stdoutPath, [string]$stderrPath) {
  $java = Join-Path $Jdk21 "bin/java.exe"
  if (-not (Test-Path -LiteralPath $java)) { $java = Join-Path $Jdk21 "bin/java" }
  $cp = (Read-LogText $ClassPathFile).Trim()
  if ([string]::IsNullOrWhiteSpace($cp)) {
    Log "  ERROR: backend classpath file is empty: $ClassPathFile"
    return $null
  }
  $proc = Start-JvmProcess -JavaExe $java `
    -Arguments @("-Xmx512m", "-cp", $cp, "com.nova.link.NovaLinkMain", $configPath) `
    -WorkingDirectory $workDir -StdoutPath $stdoutPath -StderrPath $stderrPath
  if (-not $proc) {
    Log "  ERROR: backend process could not be started"
    return $null
  }
  Track-Pid $proc.Id "backend"
  $script:BackendProcesses["joint"] = $proc
  Log "  backend pid=$($proc.Id) on tcp $BackendPort ws $BackendWsPort"

  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if ($proc.HasExited) {
      Log "  ERROR: backend exited early (code=$($proc.ExitCode))"
      Write-LogTail $stdoutPath 30 "backend stdout"
      Write-LogTail $stderrPath 15 "backend stderr"
      return $null
    }
    $text = Read-LogText $stdoutPath
    if ($text -match '(?i)started successfully' -and (Test-PortOpen $BackendPort)) {
      Log "  backend ready"
      return $proc
    }
  }
  Log "  ERROR: backend did not become ready within 60s"
  Write-LogTail $stdoutPath 30 "backend stdout"
  return $null
}

# --- per-platform server launcher -------------------------------------------
# Writes the platform's run directory (properties / EULA / plugin config) and
# starts the process. The shapes below match what each engine actually reads:
# Nukkit/PNX/PMMP take the Bedrock-style key set, Purpur/Folia/Fabric the Java
# one, Velocity its own TOML (written by the proxy branch).
function Start-PlatformServer {
  param(
    [string]$platform,
    [string]$serverDir,
    [int]$port,
    [int]$rconPort,
    [string]$clientId,
    [string]$clientPassword,
    [string]$stdoutPath,
    [string]$stderrPath
  )
  $def = $PlatformDefs[$platform]
  $jdkPath = Get-JdkPath $def.Jdk
  if (-not $jdkPath -and $def.Jdk) {
    Log "  ERROR: runtime JDK $($def.Jdk) could not be resolved for $platform"
    return $null
  }
  New-Item -ItemType Directory -Force -Path $serverDir | Out-Null

  # Reuse a pre-generated world when one is cached.
  #
  # A Java server with no world spends its whole first boot generating spawn
  # chunks -- measured 171-236s for Purpur 1.21.11 on this box, which is most of
  # a round's budget and is pure overhead: the joint assertions do not care what
  # the terrain looks like. When <JointRoot>/seed/<platform>/world exists it is
  # copied in before the server starts, turning a ~3-minute boot into ~20s.
  # The cache is populated by the harness itself after a round that generated
  # one (see the copy at the end of Invoke-Round).
  #
  # A HALF-COPIED cache is worse than no cache: Purpur sees level.dat/region
  # missing, decides the world is new, then trips over the leftover session.lock
  # and hangs after "[ERROR]: No key layers in MapLike[{}]" -- no "Preparing
  # level", no "Done (", so the round dies on a ready-timeout that says nothing
  # about the real cause. Require the files a usable world actually needs and
  # drop the cache when they are absent, so the next round regenerates.
  $seedWorld = Join-Path (Join-Path (Join-Path $JointRoot "seed") $platform) "world"
  $targetWorld = Join-Path $serverDir "world"
  if (Test-Path -LiteralPath $seedWorld) {
    $seedComplete = (Test-Path -LiteralPath (Join-Path $seedWorld "level.dat")) -and
                    (@(Get-ChildItem -LiteralPath (Join-Path $seedWorld "region") -Filter "*.mca" -ErrorAction SilentlyContinue).Count -gt 0)
    if (-not $seedComplete) {
      Log "  WARN: cached world for $platform is incomplete (no level.dat/region); discarding it"
      Remove-Item -LiteralPath $seedWorld -Recurse -Force -ErrorAction SilentlyContinue
    }
  }
  if ((Test-Path -LiteralPath $seedWorld) -and -not (Test-Path -LiteralPath $targetWorld)) {
    Copy-Item $seedWorld $targetWorld -Recurse -Force
    Log "  seeded pre-generated world for $platform"
  }

  # Drop the platform's plugin/mod into the server's plugins|mods directory.
  # This has to happen BEFORE the server starts: every engine here scans those
  # directories once at boot, so a jar copied afterwards is never loaded
  # ("Initialized 0 plugins" in the bukkit log, no NovaChat line in Nukkit's).
  $null = Install-Plugin $platform $serverDir

  # Nukkit/PNX initialise EncryptionUtils eagerly and fetch their Xbox discovery
  # document over TLS from client.discovery.minecraft-services.net. That host is
  # unreachable from CI and from this box, and the failure is an
  # AssertionError inside the static initializer -- the server dies before the
  # console exists. Nukkit caches the response in the SERVER ROOT as
  # discovery-cache.json / openid-cache.json; seed them from any previously
  # populated run directory so the lookup never happens.
  if ($platform -in @('nukkit', 'pnx')) {
    $cacheSources = @(
      (Join-Path $RepoRoot ".e2e-artifacts/dist/nukkit-cache"),
      (Join-Path $RepoRoot ".e2e/nukkit")
    )
    # Any earlier round's server root is also a valid source. The dist cache
    # above only ever holds discovery-cache.json (it is populated by hand), so
    # openid-cache.json was missing and every Nukkit boot raced a network
    # lookup that times out -- run 20261020-173744 died this way with
    # "Unable to fetch OpenID configuration", 480 s of ready-timeout, and a
    # round that reported no assertions at all. Prefer the NEWEST run.
    $earlierRuns = @()
    if (Test-Path -LiteralPath $JointRoot) {
      $earlierRuns = @(Get-ChildItem -LiteralPath $JointRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d{8}-\d{6}$' -and $_.Name -ne $script:RunId } |
        Sort-Object Name -Descending)
    }
    foreach ($run in $earlierRuns) {
      $cacheSources += (Join-Path (Join-Path $run.FullName "round-1") $platform)
    }
    foreach ($cacheFile in @('discovery-cache.json', 'openid-cache.json')) {
      $dest = Join-Path $serverDir $cacheFile
      if (Test-Path -LiteralPath $dest) { continue }
      foreach ($src in $cacheSources) {
        $candidate = Join-Path $src $cacheFile
        if (Test-Path -LiteralPath $candidate) {
          Copy-Item $candidate $dest -Force
          Log "  seeded $cacheFile for $platform from $src"
          break
        }
      }
      if (-not (Test-Path -LiteralPath $dest)) {
        Log "  WARN: $cacheFile not found for $platform; the server will try the network and may fail to boot"
      }
    }
  }

  # Backend credentials for the plugin/mod config. Written BEFORE first start:
  # the bundled template points at 127.0.0.1:8888 with a placeholder username,
  # and both config loaders only write a default when the file is absent.
  #
  # In lite there is no backend process at all, so the reconnect loop's WARN
  # lines are pure noise -- but debug = true is what surfaces the
  # plugin-messaging transport decisions, which is what this run needs.
  $debugFlag = if ($ProxyMode -eq 'lite') { 'true' } else { 'false' }
  $pluginConfig = @"
backend:
  host: "127.0.0.1"
  port: $BackendPort
  username: "$clientId"
  password: "$clientPassword"
  reconnect-delay: 3
chat:
  replace_vanilla: true
  default_channel: "global"
  locale: "en_US"
  channel-prefixes: {}
# Off in relay. Packet-level debug emits two lines per keep-alive and the
# backend pings every 30s; on a client whose keep-alive echo is not recognised
# that becomes a tight loop and the resulting ~2700 lines/s starve this
# harness's PowerShell event pump (the ready marker is in the log, but the poll
# never observes it). Diagnostics that matter in relay are lifecycle lines,
# which are INFO.
#
# On in lite. Lite has no backend link at all, so there is no keep-alive flood,
# and the plugin-messaging transport only reports itself at debug level
# ("Forwarded chat to the proxy over novachat:main", "Sent ChatMessagePacket to
# the proxy through <carrier>"). Without those lines a silently-lost plugin
# message is indistinguishable from one that was never attempted.
debug: $debugFlag
"@

  # Plugin-messaging identity for the legs that can run behind a proxy.
  #
  # The proxy rejects any envelope whose declared server id does not match the
  # server the payload actually arrived from (ProxyChannelHandler.Outcome
  # REJECTED_SERVER_ID). Write-VelocityToml keys [servers] by the LEG NAME
  # ($round.Java / $round.Mod), so server-id must be that same name -- not the
  # backend username and not a display name. Leaving it empty falls back to
  # backend.username (here E2E_LiteBukkit), which never matches and makes a
  # working channel look broken.
  #
  # Only bukkit/folia parse this section; fabric/nukkit/pnx take no section so
  # their loaders never see an unknown key.
  if ($platform -in @('bukkit', 'folia')) {
    $pluginConfig += @"

plugin-messaging:
  enabled: true
  server-id: "$platform"
"@
  }

  switch ($platform) {
    'fabric' {
      # Fabric reads <server>/config/novachat.yml (FabricLoader config dir).
      $cfgDir = Join-Path $serverDir "config"
      New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
      [System.IO.File]::WriteAllText((Join-Path $cfgDir "novachat.yml"), $pluginConfig,
        (New-Object System.Text.UTF8Encoding $false))
      [System.IO.File]::WriteAllText((Join-Path $serverDir "eula.txt"), "eula=true`r`n",
        (New-Object System.Text.UTF8Encoding $false))
      $props = @"
server-port=$port
server-ip=127.0.0.1
online-mode=false
motd=NovaChat-Joint-$platform
level-name=world
level-type=minecraft\:flat
gamemode=survival
difficulty=easy
spawn-protection=0
view-distance=4
simulation-distance=4
max-players=5
sync-chunk-writes=false
"@
      [System.IO.File]::WriteAllText((Join-Path $serverDir "server.properties"), $props,
        (New-Object System.Text.UTF8Encoding $false))
    }
    { $_ -in @('bukkit', 'folia') } {
      $cfgDir = Join-Path $serverDir "plugins/NovaChat"
      New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
      [System.IO.File]::WriteAllText((Join-Path $cfgDir "config.yml"), $pluginConfig,
        (New-Object System.Text.UTF8Encoding $false))
      [System.IO.File]::WriteAllText((Join-Path $serverDir "eula.txt"), "eula=true`r`n",
        (New-Object System.Text.UTF8Encoding $false))
      $props = @"
enable-rcon=true
rcon.port=$rconPort
rcon.password=joint-e2e-rcon
server-port=$port
server-ip=127.0.0.1
online-mode=false
max-players=5
motd=NovaChat-Joint-$platform
level-name=world
level-type=minecraft\:flat
gamemode=survival
difficulty=easy
pvp=false
spawn-protection=0
view-distance=4
simulation-distance=4
white-list=false
enforce-secure-profile=false
op-permission-level=4
allow-flight=true
spawn-monsters=false
spawn-animals=false
allow-nether=false
"@
      [System.IO.File]::WriteAllText((Join-Path $serverDir "server.properties"), $props,
        (New-Object System.Text.UTF8Encoding $false))
    }
    { $_ -in @('nukkit', 'pnx') } {
      $cfgDir = Join-Path $serverDir "plugins/NovaChat"
      New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
      [System.IO.File]::WriteAllText((Join-Path $cfgDir "config.yml"), $pluginConfig,
        (New-Object System.Text.UTF8Encoding $false))
      [System.IO.File]::WriteAllText((Join-Path $serverDir "eula.txt"), "eula=true`r`n",
        (New-Object System.Text.UTF8Encoding $false))
      $props = @"
motd=NovaChat-Joint-$platform
server-port=$port
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
rcon.port=$rconPort
rcon.password=joint-e2e-rcon
allow-nether=off
allow-the-end=off
"@
      [System.IO.File]::WriteAllText((Join-Path $serverDir "server.properties"), $props,
        (New-Object System.Text.UTF8Encoding $false))
      $nukkitYml = @"
aliases:
  showtheversion:
    - version
worlds:
  world:
    seed: 12345
    generator: flat
settings:
  language: "eng"
  force-language: false
  shutdown-message: "Server closed"
  async-workers: auto
  safe-spawn: false
network:
  batch-threshold: 256
  compression-level: 7
  async-compression: true
level-settings:
  default-format: anvil
  auto-tick-rate: true
  base-tick-rate: 1
chunk-sending:
  per-tick: 4
  max-chunks: 192
player:
  save-player-data: false
"@
      [System.IO.File]::WriteAllText((Join-Path $serverDir "nukkit.yml"), $nukkitYml,
        (New-Object System.Text.UTF8Encoding $false))
    }
    'pmmp' {
      $cfgDir = Join-Path $serverDir "plugin_data/NovaChat"
      New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
      [System.IO.File]::WriteAllText((Join-Path $cfgDir "config.yml"), $pluginConfig,
        (New-Object System.Text.UTF8Encoding $false))
      $props = @"
motd=NovaChat-Joint-pmmp
server-port=$port
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
spawn-protection=0
auto-save=off
xbox-auth=off
"@
      [System.IO.File]::WriteAllText((Join-Path $serverDir "server.properties"), $props,
        (New-Object System.Text.UTF8Encoding $false))
    }
  }

  $jarName = $DefJarName[$platform]
  if (-not $jarName) {
    Log "  ERROR: no resolved jar for $platform"
    return $null
  }
  $jarPath = Join-Path $DistDir $jarName
  if (-not (Test-Path -LiteralPath $jarPath)) {
    Log "  ERROR: server jar missing: $jarPath"
    return $null
  }

  $javaArgs = @("-Xms512M", "-Xmx1024M", "-jar", $jarPath, "nogui")
  $java = Join-Path $jdkPath "bin/java.exe"
  if (-not (Test-Path -LiteralPath $java)) { $java = Join-Path $jdkPath "bin/java" }
  $proc = Start-JvmProcess -JavaExe $java -Arguments $javaArgs -WorkingDirectory $serverDir `
    -StdoutPath $stdoutPath -StderrPath $stderrPath
  if (-not $proc) {
    Log "  ERROR: $platform process could not be started"
    return $null
  }
  Track-Pid $proc.Id "$platform-server"
  Log "  $platform pid=$($proc.Id) port=$port jdk=$($def.Jdk)"
  return $proc
}

# --- proxy launcher ----------------------------------------------------------
# Velocity needs a velocity.toml that (a) binds the joint port, (b) runs offline,
# and (c) lists the downstream servers this round started. The plugin's own
# config.toml is separate and is written by Install-Plugin/Write-ProxyPluginConfig.
function Write-VelocityToml([string]$path, [hashtable]$downstreams) {
  $serverLines = @()
  foreach ($name in $downstreams.Keys) {
    $serverLines += "$($name) = `"127.0.0.1:$($downstreams[$name])`""
  }
  $tryFirst = @($downstreams.Keys)[0]
  $toml = @"
config-version = "2.8"
bind = "127.0.0.1:$ProxyPort"
motd = "<#09add3>NovaChat Joint E2E"
show-max-players = 5
online-mode = false
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "none"
announce-forge = false
kick-existing-players = false
ping-passthrough = "disabled"

[servers]
$($serverLines -join "`r`n")

try = [
    "$tryFirst"
]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 0
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = false
announce-proxy-commands = false
log-command-executions = false
log-player-connections = true
accepts-transfers = false

[query]
enabled = false
port = $ProxyPort
"@
  [System.IO.File]::WriteAllText($path, $toml, (New-Object System.Text.UTF8Encoding $false))
}

# Proxy plugin configuration. The shape depends on the mode:
#   relay -- backend credentials are required and parsed.
#   lite  -- no backend section is read at all (ProxyConfig skips it), and the
#            lite.server-id / lite.channels / lite.storage sections are required
#            and validated. chat.default_channel MUST name a declared channel,
#            so the sample channel is called "global" to match it.
#
# debug = true in lite is deliberate: the local authority's chat, join and DM
# decisions are only logged through platform.logDebug, and this harness proves
# that path is actually reached rather than inferring it from "the proxy booted".
#
# -Identities (relay only) fills [backend.identities]: downstream server name ->
# backend username. Keys MUST be the velocity.toml [servers] keys (the leg
# names), which is also what the sub-server puts in its plugin-messaging
# envelope; the proxy compares them case-insensitively. When the table is
# present the harness deliberately leaves backend.username EMPTY so the legacy
# single-link fallback cannot exist: a run that accidentally exercised the old
# model would then fail loudly instead of quietly passing on the shared link.
function Write-ProxyPluginConfig([string]$path, [string]$clientId, [string]$clientPassword,
                                 [string]$mode = "relay", [hashtable]$identities = $null) {
  $identityLines = @()
  if ($identities) {
    foreach ($server in $identities.Keys) {
      $identityLines += "$server = `"$($identities[$server])`""
    }
  }
  $identitySection = if ($identityLines.Count -gt 0) {
    "`r`n[backend.identities]`r`n" + ($identityLines -join "`r`n")
  } else { "" }
  # Empty username when every downstream server has its own identity: a
  # non-empty one would open an extra fallback link and make "exactly N
  # authenticated clients" indistinguishable from the legacy one-connection
  # model. ProxyConfig rejects an identity equal to backend.username, so the
  # two are mutually exclusive by design.
  $relayUsername = if ($identityLines.Count -gt 0) { "" } else { $clientId }
  $relaySection = @"

[backend]
host = "127.0.0.1"
port = $BackendPort
username = "$relayUsername"
password = "$clientPassword"
reconnect-delay = 3
$identitySection
"@
  $liteSection = @"

# Lite probe config (see -ProxyMode lite in this harness).
[lite]
server-id = "$clientId"

[lite.channels]
global = { display-name = "Global", max-capacity = 0, slow-mode = 0 }
local  = { display-name = "Local",  max-capacity = 0, slow-mode = 0 }

[lite.storage]
type = "memory"
"@
  $modeSection = if ($mode -eq "lite") { $liteSection } else { $relaySection }
  $debugFlag = if ($mode -eq "lite") { "true" } else { "false" }
  $toml = @"
config-version = 1
mode = "$mode"
# Relay: packet-level debug floods proxy.stdout.log (two lines per keep-alive)
# and starves the harness's log poll, so it stays off. Lite: on, because the
# authority's decisions are only emitted through logDebug.
debug = $debugFlag
$modeSection

[chat]
replace_vanilla = true
default_channel = "global"
locale = "en_US"

[chat.channel-prefixes]

[format]
prefix = "&8[&bNovaChat&8]&r "
error = "&cError: {message}"
success = "&aSuccess: {message}"
default = "&7[{channel_color}{channel_name}] {player}&f: {message}"

[format.channels]
global = "&c[Global] &7{player}&f: {message}"
local = "&e[Local] &7{player}&f: {message}"
"@
  [System.IO.File]::WriteAllText($path, $toml, (New-Object System.Text.UTF8Encoding $false))
}

# --- bot plans ---------------------------------------------------------------
# A plan is the bot-side contract: the orchestrator decides what each role does
# and the bot records exactly what it saw. The correlation token is the crux --
# every cross-server assertion is "the RECEIVER's recording contains the token
# the SENDER emitted", never the sender's own claim.
function New-CrossServerPlan {
  param(
    [string]$role,
    [string]$kind,
    [string]$username,
    [int]$port,
    [string]$mcVersion,
    [string]$resultsFile,
    [string]$handoffDir,
    [int]$timeoutMs,
    [string]$runId,
    [object[]]$steps
  )
  $plan = [ordered]@{
    role = $role
    kind = $kind
    username = $username
    host = '127.0.0.1'
    port = $port
    mcVersion = $mcVersion
    resultsFile = $resultsFile
    handoffDir = $handoffDir
    timeoutMs = $timeoutMs
    runId = $runId
    steps = $steps
  }
  return ($plan | ConvertTo-Json -Depth 12)
}

function Start-Bot([string]$role, [string]$planPath, [string]$workDir, [string]$stdoutPath, [string]$stderrPath) {
  New-Item -ItemType Directory -Force -Path $workDir | Out-Null
  $proc = Start-PlainProcess -FilePath "node" -Arguments @($jointBotScript, "--plan", $planPath) `
    -WorkingDirectory $workDir -StdoutPath $stdoutPath -StderrPath $stderrPath
  Track-Pid $proc.Id "bot-$role"
  Log "  bot $role pid=$($proc.Id)"
  return $proc
}

function Wait-Bots([object[]]$bots, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  foreach ($b in $bots) {
    $remaining = [math]::Max(1, [int]($deadline - (Get-Date)).TotalSeconds)
    if (-not $b.Process.WaitForExit($remaining * 1000)) {
      Log "  bot $($b.Role) still running after ${timeoutSec}s -- killing"
      Stop-Process -Id $b.Process.Id -Force -ErrorAction SilentlyContinue
    } else {
      Log "  bot $($b.Role) exited (code=$($b.Process.ExitCode))"
    }
  }
}

# Read a bot result file. UTF-8 explicitly: Node writes UTF-8, and Get-Content
# -Raw on 5.1 decodes with the ANSI code page, which turns the Chinese lines the
# servers emit into mojibake and makes token matching silently fail.
function Read-BotResult([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { return $null }
  try {
    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    return ($text | ConvertFrom-Json)
  } catch {
    Log "  WARN: could not parse bot result ${path}: $($_.Exception.Message)"
    return $null
  }
}

# Prove token delivery from the RECEIVER's recording.
function Assert-Delivered([string]$assertionId, [string]$token, $receiverResult) {
  $hit = $null
  if ($receiverResult -and $receiverResult.received) {
    foreach ($entry in $receiverResult.received) {
      if ($entry.text -and $entry.text.Contains($token)) { $hit = $entry; break }
      if ($entry.raw -and $entry.raw.Contains($token)) { $hit = $entry; break }
    }
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $token
    pass = ($null -ne $hit)
    evidence = if ($hit) { $hit.text } else { '' }
    kind = 'delivery'
  }
}

function Assert-Absent([string]$assertionId, [string]$token, $receiverResult) {
  $hit = $null
  if ($receiverResult -and $receiverResult.received) {
    foreach ($entry in $receiverResult.received) {
      if (($entry.text -and $entry.text.Contains($token)) -or
          ($entry.raw -and $entry.raw.Contains($token))) { $hit = $entry; break }
    }
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $token
    pass = ($null -eq $hit)
    evidence = if ($hit) { $hit.text } else { '' }
    kind = 'absence'
  }
}

# Prove a proxy leg actually loaded the plugin. Without this the round can go
# green while the proxy ran bare: the downstream servers talk to the backend on
# their own, so every cross-server assertion passes without the proxy doing
# anything. A proxy named in -Platforms is part of the topology under test, so
# "it started" is not enough -- its log must carry the plugin's own init line.
function Assert-ProxyPluginLoaded([string]$assertionId, [string]$proxyName, [string]$logPath) {
  $pattern = 'NovaChat Velocity plugin enabled|NovaChat plugin enabled|Loaded plugin novachat'
  $text = Read-LogText $logPath
  $pass = [bool]($text -and ($text -match $pattern))
  $evidence = ''
  if ($pass) {
    $line = ($text -split "`n" | Where-Object { $_ -match $pattern } | Select-Object -First 1)
    $evidence = if ($line) { $line.Trim() } else { '' }
  } else {
    $evidence = "no plugin init line in $(Split-Path -Leaf $logPath)"
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $proxyName
    pass = $pass
    evidence = $evidence
    kind = 'proxy-plugin'
  }
}

# Prove a marker appears in a log. Used by the lite probe, where the evidence
# that the LOCAL AUTHORITY (not merely the proxy) did the work is a debug line
# the authority emits only on the path under test. "The proxy booted" is not
# enough: relay mode boots the same way, and a bare proxy would pass every
# substrate-level check.
function Assert-LogContains([string]$assertionId, [string]$token, [string]$logPath,
                            [string]$pattern) {
  $text = Read-LogText $logPath
  $pass = [bool]($text -and ($text -match $pattern))
  $evidence = ''
  if ($pass) {
    $line = ($text -split "`n" | Where-Object { $_ -match $pattern } | Select-Object -First 1)
    $evidence = if ($line) { $line.Trim() } else { '' }
  } else {
    $evidence = "pattern not found in $(Split-Path -Leaf $logPath): $pattern"
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $token
    pass = $pass
    evidence = $evidence
    kind = 'proxy-log'
  }
}

# Prove a marker does NOT appear in a log. This is what makes the "no backend"
# claim falsifiable: if the lite proxy ever dials 127.0.0.1:<BackendPort> the
# relay client logs a connect/retry line, and this fails the round instead of
# letting a silently-relay proxy look like a working lite one.
function Assert-LogAbsent([string]$assertionId, [string]$token, [string]$logPath,
                          [string]$pattern) {
  $text = Read-LogText $logPath
  $hit = [bool]($text -and ($text -match $pattern))
  $evidence = ''
  if ($hit) {
    $evidence = ($text -split "`n" | Where-Object { $_ -match $pattern } | Select-Object -First 1)
    $evidence = if ($evidence) { $evidence.Trim() } else { '' }
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $token
    pass = (-not $hit)
    evidence = $evidence
    kind = 'proxy-log-absent'
  }
}

# --- relay per-server identity assertions (proxy commit 95c9a85) -------------
#
# The relay round used to prove only that "the proxy accepts plugin messages and
# the backend delivers them". It could not distinguish the OLD single-connection
# model from the new per-server model, because both produce the same chat lines.
# The helpers below assert the difference.
#
# Evidence chain, all from this run's own logs (never the sender's claim):
#
#   1. The proxy's own "Relay backend links: N connection(s) (<servers>)" line
#      names the dedicated links it created. That is the PROXY's claim about
#      configuration, so it is necessary but not sufficient.
#   2. The backend's INFO "Client authenticated: <id> from <addr>" line is the
#      authoritative record: one per accepted connection, naming the identity
#      the connection authenticated as. Under the old model there is exactly one
#      from the proxy; under the new model there is one per downstream server.
#   3. Per-server attribution: the backend stamps ChatMessagePacket.clientId
#      from the connection that carried it and forwards that same packet down.
#      The proxy's debug line "Received chat message from backend: <packet>"
#      prints the packet's toString, which includes clientId='...'. That line is
#      therefore direct evidence of which link the backend attributed the
#      message to.
#
# Kind assignment:
#   'relay-identity' / 'relay-attribution' assertions GATE, and are emitted only
#   in relay mode. 'diagnostic' ones report and never gate.
function Get-BackendAuthIdentities([string]$logPath) {
  $text = Read-LogText $logPath
  $ids = @()
  $lines = @()
  if ($text) {
    foreach ($line in ($text -split "`n")) {
      # "HH:mm:ss.SSS [thread] INFO  c.n.l.NovaLinkCore - Client authenticated: <id> from <addr>"
      $m = [regex]::Match($line, 'Client authenticated:\s*(\S+)\s+from\s+(\S+)')
      if ($m.Success) {
        $ids += $m.Groups[1].Value
        $lines += $line.Trim()
      }
    }
  }
  return [PSCustomObject]@{ Identities = $ids; Lines = $lines }
}

# Prove the backend saw one distinct authenticated identity per downstream
# server, and that the set is exactly the identities the harness configured.
#
# Fails when: an expected identity is missing, the count is short (the old
# single-connection model authenticates ONCE, as one shared id), or a different
# identity from the proxy appeared (the fallback link fired).
function Assert-RelayDistinctIdentities([string]$assertionId, [string]$backendLogPath,
                                        [string[]]$expectedIdentities,
                                        [string[]]$proxyOwnedIdentities) {
  $auth = Get-BackendAuthIdentities $backendLogPath
  $unique = @($auth.Identities | Sort-Object -Unique)
  $missing = @($expectedIdentities | Where-Object { $unique -notcontains $_ })
  $unexpectedProxy = @($proxyOwnedIdentities | Where-Object { $unique -contains $_ })
  $pass = ($missing.Count -eq 0) -and ($unexpectedProxy.Count -eq 0) -and
          ($unique.Count -ge $expectedIdentities.Count)

  # Evidence: the specific "Client authenticated" lines for the expected ids.
  $evidenceLines = @()
  foreach ($line in $auth.Lines) {
    foreach ($id in $expectedIdentities) {
      if ($line -match "Client authenticated:\s*$([regex]::Escape($id))\s") { $evidenceLines += $line }
    }
  }
  $evidence = if ($pass) {
    ($evidenceLines -join ' | ')
  } else {
    $parts = @()
    if ($missing.Count -gt 0) { $parts += "missing identity(ies): $($missing -join ', ')" }
    if ($unexpectedProxy.Count -gt 0) { $parts += "proxy fallback identity(ies) also authenticated: $($unexpectedProxy -join ', ')" }
    $parts += "authenticated this run: $(if ($unique.Count -gt 0) { $unique -join ', ' } else { '<none>' })"
    ($parts -join '; ')
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = ($expectedIdentities -join ',')
    pass = $pass
    evidence = $evidence
    kind = 'relay-identity'
  }
}

# Records whether this topology can distinguish per-server links from the old
# single shared connection. It CANNOT when the proxy fronts fewer than two
# downstream servers: then "one dedicated connection" and "one shared
# connection" are both a single link, and the only difference is which identity
# it authenticated as (which relay-links-created already checks).
#
# Kind: 'diagnostic'. It never gates, because a thin topology is not a product
# failure -- but it is reported on every relay run so a green result on a
# one-downstream topology is never mistaken for proof of the per-server model.
function Assert-RelayIdentitiesCoverage([string]$assertionId, [string[]]$servers) {
  $count = @($servers).Count
  $sufficient = ($count -ge 2)
  $evidence = if ($sufficient) {
    "$count proxy-fronted downstream servers ($($servers -join ', ')): distinct per-server identities are observable"
  } else {
    "only $count proxy-fronted downstream server(s) ($($servers -join ', ')): " +
    "the old single-connection model and the new per-server model both produce one proxy link here, " +
    "so this run cannot prove the per-server fan-out. Add a second Java leg behind the proxy " +
    "(e.g. -Platforms velocity,bukkit,fabric,nukkit) to make it falsifiable."
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = ($servers -join ',')
    pass = $sufficient
    evidence = $evidence
    kind = 'diagnostic'
  }
}

# Prove the proxy actually opened a dedicated link per downstream server, rather
# than one shared link with a populated identities table that never took effect.
# The shell logs: "Relay backend links: N connection(s) (bukkit, fabric)".
function Assert-RelayLinksCreated([string]$assertionId, [string]$proxyLogPath,
                                  [string[]]$expectedServers) {
  $text = Read-LogText $proxyLogPath
  $line = ''
  if ($text) {
    $line = ($text -split "`n" | Where-Object { $_ -match 'Relay backend links:' } | Select-Object -First 1)
  }
  $line = if ($line) { $line.Trim() } else { '' }
  $pass = $false
  $detail = 'no "Relay backend links:" line in the proxy log'
  if ($line) {
    # Expected count is one per downstream server; the fallback link does not
    # exist in this harness (backend.username is empty), so N == servers.
    $m = [regex]::Match($line, 'Relay backend links:\s*(\d+)\s+connection\(s\)')
    if ($m.Success) {
      $count = [int]$m.Groups[1].Value
      $missingServers = @($expectedServers | Where-Object { $line -notmatch [regex]::Escape($_) })
      $mentionsShared = $line -match 'shared backend\.username link'
      $pass = ($count -eq $expectedServers.Count) -and ($missingServers.Count -eq 0) -and (-not $mentionsShared)
      $detail = if ($pass) { '' } else {
        "expected $($expectedServers.Count) dedicated connection(s) for $($expectedServers -join ', '); " +
        "line reports $count" + $(if ($mentionsShared) { ' and the shared fallback' } else { '' }) +
        $(if ($missingServers.Count -gt 0) { "; not named: $($missingServers -join ', ')" } else { '' })
      }
    } else {
      $detail = "could not parse a connection count from: $line"
    }
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = ($expectedServers -join ',')
    pass = $pass
    evidence = if ($pass) { $line } else { $detail }
    kind = 'relay-identity'
  }
}

# Prove per-server delivery attribution: the backend received this run's chat
# token on server X's authenticated link, i.e. the receiving connection was
# X's identity and not a shared one.
#
# Evidence: the backend stamps ChatMessagePacket.clientId from the carrying
# connection and the proxy logs the decoded packet, whose toString includes
# clientId='...'. This helper looks for the token and the identity on the SAME
# log line, so an unrelated line about the identity cannot satisfy it.
#
# Kind: 'diagnostic' unless the caller knows the debug line was emitted. The
# relay rounds turn proxy debug OFF (keep-alive flood -- see Write-ProxyPluginConfig),
# which suppresses that line entirely. Report it as a diagnostic rather than
# inventing a pass; the gating attribution evidence in THIS harness is the
# backend-side identity set above plus the per-server dedicated-link count.
function Assert-RelayAttribution([string]$assertionId, [string]$token, [string]$identity,
                                 [string]$proxyLogPath, [bool]$gating = $false) {
  $text = Read-LogText $proxyLogPath
  $hit = ''
  if ($text) {
    foreach ($line in ($text -split "`n")) {
      if ($line.Contains($token) -and $line.Contains("clientId='$identity'")) { $hit = $line.Trim(); break }
    }
  }
  $pass = [bool]$hit
  $evidence = if ($pass) { $hit } else {
    "no proxy log line carries both the token and clientId='$identity' " +
    "(proxy debug is off in relay, so the decoded-packet line is not emitted; " +
    "see Assert-RelayAttribution's doc comment)"
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = "$token@$identity"
    pass = $pass
    evidence = $evidence
    kind = if ($gating) { 'relay-attribution' } else { 'diagnostic' }
  }
}

# Prove a message originating on server A is not delivered twice to a player on
# server B. Under the old single shared connection every downstream broadcast
# was unscoped, so a line could reach a sub-server through more than one path.
#
# Evidence: the RECEIVER bot's own recording, counted per RENDER EVENT rather
# than per recording kind. The two transports report differently:
#   java    -- mineflayer fires `messagestr` once per rendered line, plus a
#              `chat` copy of the same wire message for player chat.
#   bedrock -- bedrock-protocol fires `text:raw` / `text:translation` per
#              rendered line and never fires `messagestr` at all.
# Counting only `messagestr` therefore reports 0 hits on a Bedrock receiver,
# which passes vacuously (observed in run 20261020-165248: the receiver DID get
# the line, as `text:raw`, and the check reported "0 messagestr line(s)").
#
# So: count distinct RENDER kinds that carry the token, and require at least
# one (the line must have arrived) and at most one per kind. A duplicated
# delivery shows up as a second entry of the SAME kind. Deduplicating by
# (kind, text) is deliberate -- a chat line that arrives via `messagestr` and
# also as a `chat` copy is one wire message reported twice by the library, not
# two deliveries.
function Assert-NoDuplicateDelivery([string]$assertionId, [string]$token, $receiverResult) {
  $hits = @()
  if ($receiverResult -and $receiverResult.received) {
    $hits = @($receiverResult.received | Where-Object {
      $_.kind -ne 'spawn' -and $_.kind -ne 'end' -and $_.kind -ne 'close' -and
      (($_.text -and $_.text.Contains($token)) -or ($_.raw -and $_.raw.Contains($token)))
    })
  }
  # One entry per (kind, text): the same line reported through two library
  # callbacks is a single arrival.
  $perKind = @{}
  foreach ($h in $hits) {
    $key = "$($h.kind)|$($h.text)"
    if (-not $perKind.ContainsKey($key)) { $perKind[$key] = @() }
    $perKind[$key] += $h
  }
  $duplicates = @()
  foreach ($key in $perKind.Keys) {
    if ($perKind[$key].Count -gt 1) { $duplicates += "$key x$($perKind[$key].Count)" }
  }
  $arrived = ($perKind.Count -gt 0)
  $pass = $arrived -and ($duplicates.Count -eq 0)
  $evidence = if (-not $receiverResult) {
    'receiver wrote no results.json'
  } elseif (-not $arrived) {
    "receiver recorded no line carrying the token (kinds seen: " +
    "$((@($receiverResult.received | ForEach-Object { $_.kind } | Sort-Object -Unique)) -join ', '))"
  } else {
    "receiver recorded $($perKind.Count) distinct render line(s) carrying the token" +
    $(if ($duplicates.Count -gt 0) { "; duplicated: $($duplicates -join '; ')" } else { '; no duplicate render' })
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $token
    pass = $pass
    evidence = $evidence
    kind = 'dedup'
  }
}

# --- backend REST helpers (per-message attribution) --------------------------
#
# The backend logs NO per-message client id at INFO: MessagePipeline's fan-out
# decisions are debug-only and the chat handler stamps clientId silently
# (NovaLinkCore: packet.setClientId(connection.getClientId())). The only
# per-message attribution it exposes is the persisted message record, written
# when features.message-log-enabled is true, and readable through
# GET /api/messages (clientId is included for roles >= ADMIN).
#
# This is the honest, non-invented evidence for "the packet the backend
# received carried A's identity": the record's clientId is the id of the
# CONNECTION the message arrived on, which is exactly what the per-server link
# model changes.
function Connect-BackendRest([string]$username, [string]$password) {
  $body = @{ username = $username; password = $password } | ConvertTo-Json -Compress
  try {
    $resp = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$BackendWsPort/api/auth/login" `
      -ContentType "application/json" -Body $body -TimeoutSec 20
    return $resp.token
  } catch {
    Log "  WARN: backend panel login failed for '$username': $($_.Exception.Message)"
    return $null
  }
}

# Returns the persisted message records whose content contains $token, as
# PSCustomObjects with .clientId / .senderName / .content. Empty on any
# failure (the caller reports that as a failure, never as a pass).
function Get-BackendMessagesForToken([string]$token, [string]$jwt) {
  if (-not $jwt) { return @() }
  $uri = "http://127.0.0.1:$BackendWsPort/api/messages?q=$([uri]::EscapeDataString($token))&size=50"
  # MessageLogService persists asynchronously on its own executor, so the row
  # may land a moment after the bot recorded the line. Poll briefly; a missing
  # row after that is a real failure (the caller reports it, never a pass).
  for ($attempt = 0; $attempt -lt 10; $attempt++) {
    try {
      $resp = Invoke-RestMethod -Method Get -Uri $uri -Headers @{ Authorization = "Bearer $jwt" } -TimeoutSec 20
      $items = @($resp.items | Where-Object { $_.content -and $_.content.Contains($token) })
      if ($items.Count -gt 0) { return $items }
    } catch {
      Log "  WARN: GET /api/messages failed: $($_.Exception.Message)"
      return @()
    }
    Start-Sleep -Milliseconds 500
  }
  return @()
}

# Prove one cross-server delivery AND its per-server attribution in a single
# assertion: the RECEIVER recorded the token, and the message record the
# backend persisted for that same token names the expected server identity.
#
# Both halves matter. Delivery alone passes under the old single-connection
# model (which is exactly the run this replaces). Attribution alone could in
# principle be read off a message that never reached anyone. Together they
# assert "B saw A's line, and the backend received it on A's identity".
#
# Fails when the token is not persisted (attribution unobservable, which must
# not look like success), when the recorded clientId is anything else -- in
# particular the old model's single shared proxy identity -- or when the
# receiver never recorded the line.
function Assert-RelayAttributionAndDelivery([string]$assertionId, [string]$token,
                                            [string]$expectedClientId, [string]$jwt,
                                            $receiverResult) {
  $match = @(Get-BackendMessagesForToken $token $jwt)
  $ids = @($match | ForEach-Object { $_.clientId } | Sort-Object -Unique)
  $attributed = ($match.Count -gt 0) -and ($ids.Count -eq 1) -and ($ids[0] -eq $expectedClientId)

  $delivered = $false
  if ($receiverResult -and $receiverResult.received) {
    foreach ($entry in $receiverResult.received) {
      if (($entry.text -and $entry.text.Contains($token)) -or
          ($entry.raw -and $entry.raw.Contains($token))) { $delivered = $true; break }
    }
  }

  $pass = $attributed -and $delivered
  $evidence = if ($pass) {
    "receiver saw the token; backend record clientId='$($ids[0])' content='$($match[0].content)'"
  } else {
    $parts = @()
    if ($match.Count -eq 0) {
      $parts += "no persisted message record carries the token (message log disabled, or the line never reached the backend)"
    } elseif (-not $attributed) {
      $parts += "backend record clientId(s)='$($ids -join ', ')', expected '$expectedClientId'"
    }
    if (-not $delivered) { $parts += 'the receiver never recorded the token' }
    ($parts -join '; ')
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = "$token@$expectedClientId"
    pass = $pass
    evidence = $evidence
    kind = 'relay-attribution'
  }
}

function Assert-BotExpectations([string]$role, $result) {
  $out = @()
  if (-not $result) { return $out }
  if ($result.assertions) {
    foreach ($a in $result.assertions) {
      # The bot tags each verdict with its own kind so an effect assertion is
      # never reported as a chat-token match. Older results carry no kind and
      # keep reporting as 'in-bot-expect', exactly as before.
      $botKind = if ($a.kind) { [string]$a.kind } else { 'expect' }
      # A bot assertion the plan marked `diagnostic` reports but never gates,
      # matching the harness's existing diagnostic convention.
      $isDiagnostic = ($a.diagnostic -eq $true)
      $out += [PSCustomObject]@{
        id = "$role/$($a.id)"
        token = $a.token
        pass = [bool]$a.pass
        evidence = $a.evidence
        kind = if ($isDiagnostic) { 'diagnostic' } else {
          switch ($botKind) {
            'effect'         { 'in-bot-effect' }
            'effect-absent'  { 'in-bot-effect-absent' }
            'kicked'         { 'in-bot-kicked' }
            'state'          { 'in-bot-state' }
            'selftest'       { 'in-bot-selftest' }
            default          { 'in-bot-expect' }
          }
        }
      }
    }
  }
  if ($result.absentChecks) {
    foreach ($a in $result.absentChecks) {
      $out += [PSCustomObject]@{
        id = "$role/$($a.id)"
        token = $a.token
        pass = [bool]$a.pass
        evidence = $a.evidence
        kind = 'in-bot-absent'
      }
    }
  }
  return $out
}

# --- versions.lock.ps1 resolution -------------------------------------------
# The lock owns every server binary: URL, SHA-256, file name, JDK. Reading the
# file name from it (rather than repeating it here) is what stops this harness
# from drifting the way the previous version did -- it pinned
# purpur-1.21.8-2497.jar and nukkit-...-1239.jar while the lock had already
# moved on.
$lockFile = Join-Path $RepoRoot "test/versions.lock.ps1"
if (-not (Test-Path -LiteralPath $lockFile)) {
  Log "FATAL: version lock not found: $lockFile"
  exit 2
}
. $lockFile

# Extra entries the joint harness needs that no per-platform harness does.
# They live here (not in versions.lock.ps1) because they are Fabric-runtime
# pieces rather than server binaries, and only this harness starts Fabric.
$FabricRuntime = @{
  # The launcher jar is content-stable: meta.fabricmc.net serves the same bytes
  # for a given (MC, loader, installer) triple. Verified 2026-10-18 by two
  # independent downloads comparing equal (SHA-256 recorded below).
  LauncherUrl = 'https://meta.fabricmc.net/v2/versions/loader/1.21.11/0.19.3/1.1.2/server/jar'
  LauncherSha256 = 'B855772F5A5D591262260E8ED3C218419366D74C3ACFC38409F4A376F167051B'
  LauncherFile = 'fabric-server-launch-1.21.11-0.19.3.jar'
  ApiUrl = 'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.141.6%2B1.21.11/fabric-api-0.141.6%2B1.21.11.jar'
  # The previous value was 65 hex characters (one 'D' too many) and could never
  # match any file -- SHA-256 is 32 bytes / 64 hex chars. Re-downloaded the
  # pinned URL on 2026-10-20 and hashed it: bdff7fd7...d0f (2,426,039 bytes).
  ApiSha256 = 'BDFF7FD7E220085CFAD2FF9B1F40DDE6534AE0B96CF378F97A374BC54CB9ED0F'
  ApiFile = 'fabric-api-0.141.6+1.21.11.jar'
  McVersion = '1.21.11'
}

# platform -> resolved jar file name (filled by Resolve-PlatformJars)
$script:DefJarName = @{}

function Resolve-PlatformJars([string[]]$platforms) {
  foreach ($pf in $platforms) {
    if (-not $pf) { continue }
    $def = $PlatformDefs[$pf]
    if (-not $def) { continue }
    # fabric has no versions.lock.ps1 entry: its launcher jar and fabric-api mod
    # are Fabric-runtime pieces with their own pins ($FabricRuntime), because
    # fetch-server.ps1 resolves Minecraft *servers*, not mod loaders.
    if ($pf -eq 'fabric') {
      $script:DefJarName[$pf] = $FabricRuntime.LauncherFile
      Log "  $pf -> fabricated launcher '$($FabricRuntime.LauncherFile)' (pinned in this script)"
      continue
    }
    $lock = Get-LockedServer -Name $def.LockName
    if (-not $lock) {
      Log "FATAL: no '$($def.LockName)' entry in test/versions.lock.ps1 (needed by $pf)"
      return $false
    }
    $script:DefJarName[$pf] = $lock.File
    Log "  $pf -> lock '$($def.LockName)' file '$($script:DefJarName[$pf])'"
  }
  return $true
}

# Fetch a server binary through the shared fetch-server.ps1 (pinned mode: it
# enforces the lock's SHA-256 before the jar is allowed to run). -Auto is not
# used here on purpose: a joint run must be reproducible, so a build cannot
# change underneath a green result.
function Invoke-FetchServer([string]$lockName) {
  $fetch = Join-Path $binDir "fetch-server.ps1"
  if (-not (Test-Path -LiteralPath $fetch)) {
    Log "  ERROR: fetch-server.ps1 missing: $fetch"
    return $false
  }
  Log "  fetching '$lockName' (pinned, SHA-256 verified)"
  $out = Join-Path $JointRoot "fetch-$lockName.log"
  $err = Join-Path $JointRoot "fetch-$lockName.err.log"
  $proc = Start-Process -FilePath "powershell.exe" `
    -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $fetch,
      "-Name", $lockName, "-DistDir", $DistDir) `
    -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -NoNewWindow -Wait
  if ($proc.ExitCode -ne 0) {
    Log "  ERROR: fetch-server failed for '$lockName' (exit=$($proc.ExitCode))"
    Write-LogTail $out 15 "fetch stdout"
    Write-LogTail $err 15 "fetch stderr"
    return $false
  }
  return $true
}

# Download + verify the Fabric runtime pieces (launcher jar, fabric-api mod).
# Both carry a pinned SHA-256; a mismatch aborts rather than running an
# unverified binary.
function Invoke-FetchFabricRuntime() {
  New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
  $pairs = @(
    @{ Url = $FabricRuntime.LauncherUrl; Sha = $FabricRuntime.LauncherSha256; File = $FabricRuntime.LauncherFile },
    @{ Url = $FabricRuntime.ApiUrl; Sha = $FabricRuntime.ApiSha256; File = $FabricRuntime.ApiFile }
  )
  foreach ($p in $pairs) {
    $dest = Join-Path $DistDir $p.File
    if (Test-Path -LiteralPath $dest) {
      if ((Get-Sha256Hex $dest) -eq $p.Sha) {
        Log "  fabric runtime cache hit: $($p.File)"
        continue
      }
      Log "  fabric runtime hash mismatch on cached $($p.File); re-downloading"
      Remove-Item -LiteralPath $dest -Force -ErrorAction SilentlyContinue
    }
    $tmp = "$dest.download.tmp"
    Log "  downloading $($p.Url)"
    try {
      Invoke-WebRequest -Uri $p.Url -OutFile $tmp -UseBasicParsing -TimeoutSec 300
    } catch {
      Log "  ERROR: download failed: $($_.Exception.Message)"
      Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
      return $false
    }
    $actual = Get-Sha256Hex $tmp
    if ($actual -ne $p.Sha) {
      Log "  ERROR: SHA-256 mismatch for $($p.File): expected $($p.Sha), got $actual"
      Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
      return $false
    }
    Move-Item -Path $tmp -Destination $dest -Force
    Log "  verified + saved: $dest"
  }
  return $true
}

# --- lite probe --------------------------------------------------------------
# Lite mode is a different product shape from relay, so it gets its own round.
# What this round can and cannot prove, stated up front so a green result is
# never read as more than it is:
#
#   PROVES (evidence is the proxy's own log or a bot's recording):
#     * the plugin loads and the LOCAL AUTHORITY starts: the proxy logs the
#       lite-mode line and never logs a relay connect/retry, so it cannot be a
#       silently-relaying proxy wearing the lite config;
#     * the lite config sections (server-id / channels / storage) validate;
#     * two players connected to the PROXY exchange chat through the authority:
#       the receiver records the sender's correlation token, and the sender
#       records the DM echo;
#     * /nc join on a declared channel is acknowledged locally and /nc who is
#       answered by the authority;
#     * an UNDECLARED channel is rejected (NC-404), proving the local channel
#       table -- not a backend -- is the authority.
#
#   RECORDS BUT DOES NOT PROVE:
#     * anything a DOWNSTREAM server's player does. There is no plugin
#       messaging in this tree: ProxyPlatform#sendPluginMessage is a default
#       no-op and no platform registers an incoming plugin channel, so a line
#       typed on the Bukkit/Nukkit server has no path to the proxy. The lite
#       authority only ever sees players connected to the PROXY itself.
#       The downstream legs still boot with their plugins so the run is a real
#       topology, and their bots record exactly what they saw. Their outcome is
#       reported as `downstream-player-*` assertions of kind 'diagnostic': they
#       describe the gap, they do not gate the round.
function Invoke-LiteRound($round, [string]$roundDir) {
  Log ""
  Log "=== LITE ROUND $($round.Index): lite $(Format-RoundLabel $round) ==="
  New-Item -ItemType Directory -Force -Path $roundDir | Out-Null

  $result = [PSCustomObject]@{
    round = $round.Index
    topology = "lite: $(Format-RoundLabel $round)"
    proxy = $round.Proxy
    java = $round.Java
    mod = $round.Mod
    bedrock = $round.Bedrock
    status = 'UNKNOWN'
    assertions = @()
    notes = ''
  }
  $assertions = @()
  $bots = @()

  if (-not $round.Proxy) {
    $result.status = 'FAIL'; $result.notes = 'lite mode requires a proxy leg'; return $result
  }

  $ports = @{
    Java = $JavaPort; JavaRcon = $JavaRconPort
    Mod = $ModPort; ModRcon = $ModRconPort
    Bedrock = $BedrockAPort; BedrockRcon = $BedrockARconPort
  }
  $downstreams = @{}
  $serverProcs = @{}
  $tokenPrefix = "$($script:RunId)-r$($round.Index)"

  try {
    # ---- downstream servers ----
    # They exist so the probe runs the real topology. Their plugins cannot
    # connect (no backend), which is exactly the gap this round measures.
    if ($round.Java) {
      $d = Join-Path $roundDir $round.Java
      $p = Start-PlatformServer -platform $round.Java -serverDir $d `
        -port $ports.Java -rconPort $ports.JavaRcon `
        -clientId 'E2E_LiteBukkit' -clientPassword 'lite-probe' `
        -stdoutPath (Join-Path $d "server.stdout.log") -stderrPath (Join-Path $d "server.stderr.log")
      if ($p) { $serverProcs[$round.Java] = $p; $downstreams[$round.Java] = $ports.Java }
    }
    if ($round.Mod) {
      $d = Join-Path $roundDir $round.Mod
      $p = Start-PlatformServer -platform $round.Mod -serverDir $d `
        -port $ports.Mod -rconPort $ports.ModRcon `
        -clientId 'E2E_LiteMod' -clientPassword 'lite-probe' `
        -stdoutPath (Join-Path $d "server.stdout.log") -stderrPath (Join-Path $d "server.stderr.log")
      if ($p) { $serverProcs[$round.Mod] = $p; $downstreams[$round.Mod] = $ports.Mod }
    }
    if ($round.Bedrock) {
      $d = Join-Path $roundDir $round.Bedrock
      $p = Start-PlatformServer -platform $round.Bedrock -serverDir $d `
        -port $ports.Bedrock -rconPort $ports.BedrockRcon `
        -clientId 'E2E_LiteBedrock' -clientPassword 'lite-probe' `
        -stdoutPath (Join-Path $d "server.stdout.log") -stderrPath (Join-Path $d "server.stderr.log")
      if ($p) { $serverProcs[$round.Bedrock] = $p }
    }

    # ---- proxy ----
    $dir = Join-Path $roundDir $round.Proxy
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $jdkPath = Get-JdkPath $PlatformDefs[$round.Proxy].Jdk
    if (-not $jdkPath) {
      $result.status = 'FAIL'; $result.notes = "runtime JDK $($PlatformDefs[$round.Proxy].Jdk) unresolved for $($round.Proxy)"
      return $result
    }
    $null = Install-Plugin $round.Proxy $dir
    Write-VelocityToml -path (Join-Path $dir "velocity.toml") -downstreams $downstreams
    $plugCfgDir = Join-Path $dir "plugins/novachat"
    New-Item -ItemType Directory -Force -Path $plugCfgDir | Out-Null
    Write-ProxyPluginConfig -path (Join-Path $plugCfgDir "config.toml") `
      -clientId 'E2E_LiteProxy' -clientPassword '' -mode 'lite'
    $jarPath = Join-Path $DistDir $script:DefJarName[$round.Proxy]
    $java = Join-Path $jdkPath "bin/java.exe"
    if (-not (Test-Path -LiteralPath $java)) { $java = Join-Path $jdkPath "bin/java" }
    $p = Start-JvmProcess -JavaExe $java `
      -Arguments @("-Xms256M", "-Xmx512M", "-jar", $jarPath) `
      -WorkingDirectory $dir `
      -StdoutPath (Join-Path $dir "proxy.stdout.log") -StderrPath (Join-Path $dir "proxy.stderr.log")
    if (-not $p) {
      $result.status = 'FAIL'; $result.notes = "$($round.Proxy) proxy could not be started"; return $result
    }
    Track-Pid $p.Id "$($round.Proxy)-proxy"
    $serverProcs[$round.Proxy] = $p
    Log "  $($round.Proxy) pid=$($p.Id) port=$ProxyPort jdk=$($PlatformDefs[$round.Proxy].Jdk)"

    # ---- readiness: ALL processes ----
    # The proxy gate is plugin-init AND listening: its own ready pattern fires
    # for a bare proxy too, and a bare proxy would prove nothing about lite.
    $proxyLog = Join-Path $dir "proxy.stdout.log"
    $deadline = (Get-Date).AddSeconds($ServerReadySec)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
      $text = Read-LogText $proxyLog
      if ($text -match 'NovaChat Velocity plugin enabled' -and $text -match 'Listening on') { $ready = $true; break }
      Start-Sleep -Seconds 2
    }
    if (-not $ready) {
      $result.status = 'FAIL'; $result.notes = 'lite proxy never loaded the plugin / never listened'
      Write-LogTail $proxyLog 40 'lite proxy stdout'
      return $result
    }

    # Downstream readiness uses each engine's own pattern against ONE shared
    # deadline. A slow engine must not fail the round (the proxy-level
    # assertions do not depend on it) but a per-server fresh timeout would
    # multiply the wait by the number of legs, so all of them are polled
    # together and whatever is not up by then is recorded as ready=false.
    $pendingDown = @()
    foreach ($name in $serverProcs.Keys) {
      if ($name -eq $round.Proxy) { continue }
      $pendingDown += [PSCustomObject]@{ Name = $name; Ready = $false }
    }
    $dEnd = (Get-Date).AddSeconds($ServerReadySec)
    while ((Get-Date) -lt $dEnd -and @($pendingDown | Where-Object { -not $_.Ready }).Count -gt 0) {
      foreach ($entry in $pendingDown) {
        if ($entry.Ready) { continue }
        $def = $PlatformDefs[$entry.Name]
        foreach ($lf in @((Join-Path (Join-Path $roundDir $entry.Name) "server.stdout.log"),
                          (Join-Path (Join-Path $roundDir $entry.Name) "logs/latest.log"))) {
          $t = Read-LogText $lf
          if ($t -and ($t -match $def.ReadyPattern)) { $entry.Ready = $true; break }
        }
      }
      if (@($pendingDown | Where-Object { -not $_.Ready }).Count -gt 0) { Start-Sleep -Seconds 2 }
    }
    foreach ($entry in $pendingDown) { Log "  downstream $($entry.Name) ready=$($entry.Ready)" }
    Start-Sleep -Seconds 3

    # ---- bots ----
    $botWork = Join-Path $roundDir "bots"
    $downWork = Join-Path $botWork "downstream"
    New-Item -ItemType Directory -Force -Path $botWork | Out-Null
    New-Item -ItemType Directory -Force -Path $downWork | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $botWork "handoff") | Out-Null
    $javaMc = '1.21.11'
    if ($round.Java) {
      $lock = Get-LockedServer -Name $PlatformDefs[$round.Java].LockName
      if ($lock -and $lock.BotMcVersion) { $javaMc = $lock.BotMcVersion }
    }

    $fanoutToken = "$tokenPrefix-lite-fanout"
    $dmToken = "$tokenPrefix-lite-dm"
    $mentionToken = "$tokenPrefix-lite-mention"
    $downToken = "$tokenPrefix-downstream-chat"

    # Alice and Bob connect to the PROXY. Only these two are visible to the
    # lite authority. E2EDownstream (started below) connects STRAIGHT to the
    # sub-server and is invisible to the proxy.
    #
    # Ordering matters twice over:
    #
    #  1. A bot records only the tokens its plan has declared interest in
    #     (joint-bot.js `expectedTokens`), so an `expect` must be armed before
    #     the line arrives. Bob arms right after his join; Alice waits 12 s
    #     before speaking so Bob's arm is well ahead of her message.
    #
    #  2. The sub-server writes upstream through ONE arbitrary online player's
    #     connection (ProxyChannelClient picks `getOnlinePlayers().iterator()
    #     .next()`), and a plugin message rides that player's own connection.
    #     If the carrier is the DIRECT-connect bot, the payload goes to that
    #     bot's client instead of to the proxy and is silently lost. Starting
    #     the direct bot LAST keeps both earlier entrants proxy-connected, so
    #     the carrier is a proxied player. The bukkit log assertion
    #     `upstream-carrier-is-proxied` below fails loudly if that ever stops
    #     holding, instead of surfacing as "plugin messaging is broken".
    $aliceSteps = @(
      @{ id = 'join-global'; action = 'cmd'; text = '/nc join global' },
      # EFFECT: joining a channel flashes a one-shot action bar
      # ("Current channel: global (<mode>)") through ProxyPlatform#showChannelStatusBar.
      # This is the only action-bar emission the product has on the join path, so
      # observing it proves the whole showChannelStatusBar -> packet path works.
      @{ id = 'expect-join-actionbar'; action = 'expectEvent'; kind = 'actionBar'; match = 'Current channel: global'; timeoutMs = 20000 },
      # NEGATIVE CONTROL: no title is sent on a join. If a title ever starts
      # arriving here the product changed behaviour, and this assertion says so
      # rather than letting a stray title pass unnoticed.
      @{ id = 'no-join-title'; action = 'expectEventAbsent'; kind = 'title'; withinMs = 2000 },
      # Deterministic, not a fixed sleep: the direct-connect bot publishes this
      # once it has spawned, so Alice speaks only after it is in the sub-server
      # roster and able to receive the downstream fan-out.
      @{ id = 'wait-downstream'; action = 'handoffGet'; key = 'downready'; timeoutMs = 150000 },
      @{ id = 'settle'; action = 'wait'; ms = 3000 },
      @{ id = 'send-fanout'; action = 'chat'; text = "lite hello $fanoutToken" },
      # EFFECT: a channel message that @-mentions Bob must produce a mention
      # notification on BOB only -- a title carrying the mentioner's name plus the
      # mention sound. Alice is the sender, so she must NOT get one: a bot's own
      # mention is never self-notified (MentionNotifier filters
      # uuid.equals(mentionerId)), and that is what the negative half asserts.
      # Sent here, NOT after expect-dm: Bob's plan waits on this effect, so
      # gating it behind a token Bob sends would deadlock the two plans.
      @{ id = 'mention-bob'; action = 'chat'; text = "hey @E2ELiteBob $mentionToken" },
      # This expect must be ARMED (declared) before the downstream player
      # speaks, because joint-bot.js only records tokens its plan declared
      # interest in. The downstream plan therefore waits 12 s after publishing
      # `downready`; by then this arm is live.
      @{ id = 'expect-downstream'; action = 'expect'; token = $downToken; timeoutMs = 60000 },
      # Bob sends this only after his own mention expects resolve, so it can never
      # deadlock against the mention above.
      @{ id = 'expect-dm'; action = 'expect'; token = $dmToken; timeoutMs = 60000 },
      @{ id = 'mention-settle'; action = 'wait'; ms = 1500 },
      @{ id = 'alice-no-self-mention-title'; action = 'expectEventAbsent'; kind = 'title'; match = 'E2ELiteAlice'; withinMs = 3000 },
      # MODERATION REACHABILITY PROBE (see Invoke-ModerationScenarios).
      # A proxy-connected player runs the moderation command the topology would
      # need to support. Alice is the TARGET here on purpose: if the command
      # ever starts working, the proof is that her own next line disappears, and
      # this probe must not pre-empt that. What comes back is recorded verbatim
      # and judged as a diagnostic -- the point is to make the current behaviour
      # visible in every run, not to lock a defect in.
      @{ id = 'probe-mute'; action = 'cmd'; text = '/nc mute E2ELiteBob 1h global' },
      @{ id = 'probe-settle'; action = 'wait'; ms = 2500 },
      @{ id = 'quit'; action = 'quit' }
    )
    $bobSteps = @(
      @{ id = 'join-global'; action = 'cmd'; text = '/nc join global' },
      @{ id = 'settle'; action = 'wait'; ms = 1000 },
      # CRITICAL ORDER: `expect` records only tokens the plan has DECLARED, so
      # every chat expect must be armed before its line is spoken. The effect
      # steps below come after them on purpose -- `expectEvent` searches the
      # whole effect history, so an effect that arrived during an earlier wait
      # still matches, while an `expect` armed too late can never match.
      #
      # Order = the order the lines arrive. Alice's fan-out comes first
      # (downready + ~3 s), the downstream player's line second (+12 s).
      @{ id = 'expect-fanout'; action = 'expect'; token = $fanoutToken; timeoutMs = 150000 },
      @{ id = 'expect-downstream'; action = 'expect'; token = $downToken; timeoutMs = 120000 },
      # EFFECT: the same join action bar, observed from a SECOND client. Bob's
      # own /nc join makes him the join command's caller, so this is a weaker
      # assertion than Alice's (it cannot distinguish a local render from a
      # server-sent bar); it is kept because it is nearly free.
      @{ id = 'expect-join-actionbar'; action = 'expectEvent'; kind = 'actionBar'; match = 'Current channel: global'; timeoutMs = 20000 },
      # EFFECT: Alice's @mention must reach Bob as a title naming Alice.
      @{ id = 'expect-mention-title'; action = 'expectEvent'; kind = 'title'; match = 'E2ELiteAlice'; timeoutMs = 60000 },
      # EFFECT (diagnostic, does not gate): the mention should also play the
      # configured sound. MEASURED 2026-10-21: NO sound packet reaches the client
      # at all -- the raw-packet probe recorded 14 `sound_effect` packets during
      # the run and not one at the mention instant, and no `entity_sound_effect`
      # ever arrived. Two independent causes are visible in source:
      #   1. VelocityProxyPlatform#playSound builds `minecraft:entity_experience_orb_pickup`
      #      from DEFAULT_SOUND, which is not a registry id (`entity.experience_orb.pickup`).
      #   2. ConnectedPlayer#playSound routes through the backend server entity and
      #      returns early when the player has no server / the sound is client-only.
      # The assertion stays so the gap is visible in every run's report; it is
      # diagnostic so a known product defect does not make the lite run red.
      @{ id = 'expect-mention-sound'; action = 'expectEvent'; kind = 'sound'; match = 'experience_orb'; timeoutMs = 8000; diagnostic = $true },
      @{ id = 'who'; action = 'cmd'; text = '/nc who global' },
      @{ id = 'settle2'; action = 'wait'; ms = 2000 },
      @{ id = 'bad-channel'; action = 'cmd'; text = '/nc join nosuchchannel' },
      @{ id = 'settle3'; action = 'wait'; ms = 2000 },
      @{ id = 'dm'; action = 'cmd'; text = "/nc msg E2ELiteAlice lite dm $dmToken" },
      @{ id = 'settle4'; action = 'wait'; ms = 3000 },
      @{ id = 'quit'; action = 'quit' }
    )

    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    $alicePlanPath = Join-Path $botWork "plan-alice.json"
    $bobPlanPath = Join-Path $botWork "plan-bob.json"
    $alicePlanJson = New-CrossServerPlan -role 'alice' -kind 'java' -username 'E2ELiteAlice' -port $ProxyPort `
      -mcVersion $javaMc -resultsFile (Join-Path $botWork "alice.results.json") -handoffDir (Join-Path $botWork "handoff") `
      -timeoutMs ($BotTimeoutSec * 1000) -runId "$tokenPrefix-lite" -steps $aliceSteps
    $bobPlanJson = New-CrossServerPlan -role 'bob' -kind 'java' -username 'E2ELiteBob' -port $ProxyPort `
      -mcVersion $javaMc -resultsFile (Join-Path $botWork "bob.results.json") -handoffDir (Join-Path $botWork "handoff") `
      -timeoutMs ($BotTimeoutSec * 1000) -runId "$tokenPrefix-lite" -steps $bobSteps
    [System.IO.File]::WriteAllText($alicePlanPath, $alicePlanJson, $utf8NoBom)
    [System.IO.File]::WriteAllText($bobPlanPath, $bobPlanJson, $utf8NoBom)

    # Bob goes first: his plan's `expect` must be registered before Alice's line
    # is sent. Alice then joins and blocks on the downstream-ready handoff, so
    # her message cannot be sent before the direct-connect player exists.
    $bots += [PSCustomObject]@{ Role = 'bob'; Process = (Start-Bot 'bob' $bobPlanPath $botWork `
      (Join-Path $botWork "bob.stdout.log") (Join-Path $botWork "bob.stderr.log")) }
    Start-Sleep -Seconds 3
    $bots += [PSCustomObject]@{ Role = 'alice'; Process = (Start-Bot 'alice' $alicePlanPath $botWork `
      (Join-Path $botWork "alice.stdout.log") (Join-Path $botWork "alice.stderr.log")) }

    # Wait for BOTH proxy players to be in the sub-server's player list before
    # starting the direct-connect bot, then give it a head start on logging in.
    #
    # Why this is a wait and not a sleep: the sub-server sends upstream through
    # `getOnlinePlayers().iterator().next()` (ProxyChannelClient), and CraftServer
    # returns `PlayerList.players` -- insertion-ordered. If the direct-connect
    # bot is first, the payload is written to ITS connection, the proxy never
    # sees it, and the run looks like a broken channel. Start order alone does
    # not decide join order (measured: the bot started last joined first), so
    # poll each bot's own stdout for its spawn line.
    $spawnDeadline = (Get-Date).AddSeconds(180)
    foreach ($role in @('bob', 'alice')) {
      $spawnLog = Join-Path $botWork "$role.stdout.log"
      while ((Get-Date) -lt $spawnDeadline) {
        $t = Read-LogText $spawnLog
        if ($t -and $t -match 'spawn:\s*spawned') { break }
        Start-Sleep -Milliseconds 500
      }
      Log "  $role spawn observed: $([bool]($t -and $t -match 'spawn:\s*spawned'))"
    }

    # ---- downstream player probe: the plugin-messaging leg ----
    # One bot connects STRAIGHT to the BUKKIT downstream (port $ports.Java, not
    # the proxy) and speaks. This is the player the lite proxy cannot see, so
    # their line can only reach the proxy over novachat:main -- the whole point
    # of the channel under test.
    #
    # Started AFTER Alice and Bob (see the carrier note above): the upstream
    # write rides one arbitrary online player's connection, and the direct bot
    # must not be a candidate.
    #
    # No `/nc join` here on purpose. The default channel from config.yml is
    # already "global", and the join COMMAND does not use the plugin-messaging
    # transport (it gates on the backend link), so issuing it would inject an
    # NC-503 that says nothing about the chat path. With it absent, every
    # NC-503 this bot records comes from chat, which is what the assertion
    # below is about.
    $downRes = $null
    if ($round.Java) {
      $downSteps = @(
        @{ id = 'settle'; action = 'wait'; ms = 2000 },
        # Announce that this player is in the sub-server roster: Alice blocks on
        # it before speaking, so the fan-out cannot be sent while the only
        # downstream recipient is still logging in.
        @{ id = 'publish-ready'; action = 'handoffSet'; key = 'downready'; pattern = '^spawned$' },
        # 12 s: Alice arms `expect-downstream` ~3 s after this handoff, and a
        # bot only records tokens its plan has declared. Speaking earlier
        # would make the line arrive before either proxy bot armed for it.
        @{ id = 'settle2'; action = 'wait'; ms = 12000 },
        # Speak first: the upstream leg is the half the product claims today.
        @{ id = 'chat'; action = 'chat'; text = "downstream hello $downToken" },
        @{ id = 'settle3'; action = 'wait'; ms = 4000 },
        # Then wait for Alice's fan-out to arrive DOWNSTREAM. This is the leg
        # under question: proxy-originated chat has to be pushed to sub-servers
        # for a direct-connect player to see it.
        @{ id = 'expect-fanout'; action = 'expect'; token = $fanoutToken; timeoutMs = 60000 },
        @{ id = 'settle4'; action = 'wait'; ms = 2000 },
        @{ id = 'quit'; action = 'quit' }
      )
      $downPlanPath = Join-Path $downWork "plan-downstream.json"
      # Same runId as alice/bob: the handoff rendezvous scans the directory for
      # "<runId>-*-<key>.json", so a different runId would never be found.
      $downPlanJson = New-CrossServerPlan -role 'downstream' -kind 'java' -username 'E2EDownstream' -port $ports.Java `
        -mcVersion $javaMc -resultsFile (Join-Path $downWork "downstream.results.json") `
        -handoffDir (Join-Path $botWork "handoff") -timeoutMs ($BotTimeoutSec * 1000) `
        -runId "$tokenPrefix-lite" -steps $downSteps
      [System.IO.File]::WriteAllText($downPlanPath, $downPlanJson, $utf8NoBom)
      $downBot = [PSCustomObject]@{ Role = 'downstream'; Process = (Start-Bot 'downstream' $downPlanPath $downWork `
        (Join-Path $downWork "downstream.stdout.log") (Join-Path $downWork "downstream.stderr.log")) }
      $bots += $downBot
    }

    Wait-Bots $bots $BotTimeoutSec

    # ---- assertions ----
    $aliceRes = Read-BotResult (Join-Path $botWork "alice.results.json")
    $bobRes = Read-BotResult (Join-Path $botWork "bob.results.json")
    if ($round.Java) {
      $downRes = Read-BotResult (Join-Path $downWork "downstream.results.json")
    }

    # 1. The plugin loaded at all (same guarantee the relay round makes).
    $assertions += Assert-ProxyPluginLoaded "round$($round.Index)/proxy-plugin-loaded" $round.Proxy $proxyLog
    # 2. The LOCAL AUTHORITY is the running mode. This is the line the shell
    #    emits only after building the lite object graph.
    $assertions += Assert-LogContains "round$($round.Index)/lite-mode-active" 'local-authority' $proxyLog `
      'NovaChat running in lite mode \(local authority, no backend connection\)'
    # 3. No backend dial. A relay client that fell back to the backend would
    #    log one of these; their absence is what distinguishes lite from relay.
    $assertions += Assert-LogAbsent "round$($round.Index)/lite-no-backend-connect" 'no-backend-dial' $proxyLog `
      'Connected to NovaLink backend|Reconnecting to NovaLink|Lost connection to NovaLink'
    # 4. The authority accepted the chat and joined the channel: its own debug
    #    lines name the channel. (The proxy is configured with debug = true.)
    $assertions += Assert-LogContains "round$($round.Index)/lite-authority-joined" 'join' $proxyLog `
      'Lite authority joined E2ELiteAlice to channel global'
    $assertions += Assert-LogContains "round$($round.Index)/lite-authority-fanout" 'chat' $proxyLog `
      'Lite send: ChatMessagePacket'
    # 5. Direct message went through the authority (packet type, no backend).
    $assertions += Assert-LogContains "round$($round.Index)/lite-authority-dm" 'dm' $proxyLog `
      'Lite send: PrivateMessagePacket'
    # 6. The receiver really saw the sender's token, and the DM echo reached
    #    the sender. Both are receiver-recorded evidence, never the sender's claim.
    if ($bobRes) {
      $assertions += Assert-Delivered "round$($round.Index)/lite-chat-fanout" $fanoutToken $bobRes
    }
    if ($aliceRes) {
      $assertions += Assert-Delivered "round$($round.Index)/lite-direct-message" $dmToken $aliceRes
    }
    $assertions += Assert-BotExpectations 'alice' $aliceRes
    $assertions += Assert-BotExpectations 'bob' $bobRes

    # 7. An undeclared channel is rejected locally with NC-404. This proves the
    #    authority's own channel table -- not a backend's -- gates joins.
    if ($bobRes) {
      $assertions += Assert-Delivered "round$($round.Index)/lite-undeclared-channel-rejected" 'NC-404' $bobRes
    }
    # 8. /nc who was answered by the authority's own membership table. The
    #    token is the WHO HEADER with its member count, not a player name: a
    #    name would also match the earlier chat line and pass vacuously.
    if ($bobRes) {
      $assertions += Assert-Delivered "round$($round.Index)/lite-who-lists-members" 'online members (2)' $bobRes
    }

    # 9. Proxy-side channel activity. "No error" is not evidence: the channel
    #    must be registered, must have actually accepted a payload from the
    #    sub-server, and must have written at least one fan-out back down. The
    #    rejection patterns are asserted absent so a server-id mismatch fails
    #    loudly instead of silently dropping every packet.
    $assertions += Assert-LogContains "round$($round.Index)/proxy-channel-registered" 'novachat:main' $proxyLog `
      'Registered plugin-messaging channel novachat:main'
    $assertions += Assert-LogContains "round$($round.Index)/proxy-channel-accepted" 'accepted' $proxyLog `
      'Lite send: ChatMessagePacket'
    $assertions += Assert-LogContains "round$($round.Index)/proxy-channel-sent-downstream" 'downstream' $proxyLog `
      'Sent downstream ChatMessagePacket'
    # The upstream write rides ONE player's connection (plugin messaging has no
    # server-addressed write): ProxyChannelClient picks
    # getOnlinePlayers().iterator().next(). If that carrier is a DIRECT-connect
    # player the payload is written to that client, the proxy never sees it,
    # and the channel looks dead for no visible reason. Assert the carrier and
    # the proxy-side receipt separately so each half is named.
    $javaLog = Join-Path (Join-Path $roundDir $round.Java) "server.stdout.log"
    if ($round.Java) {
      $assertions += Assert-LogContains "round$($round.Index)/upstream-carrier-is-proxied" 'carrier' $javaLog `
        'Sent ChatMessagePacket to the proxy through E2ELite(Alice|Bob)'
      $assertions += Assert-LogAbsent "round$($round.Index)/upstream-carrier-not-direct" 'carrier-direct' $javaLog `
        'Sent ChatMessagePacket to the proxy through E2EDownstream'
    }
    $assertions += Assert-LogContains "round$($round.Index)/proxy-received-downstream-chat" 'upstream-received' $proxyLog `
      "Received chat message from backend: .*$downToken"
    $assertions += Assert-LogAbsent "round$($round.Index)/proxy-channel-no-server-id-reject" 'REJECTED_SERVER_ID' $proxyLog `
      'Rejected novachat:main payload: claimed server id'
    $assertions += Assert-LogAbsent "round$($round.Index)/proxy-channel-no-malformed-drop" 'malformed' $proxyLog `
      'Dropped malformed novachat:main payload'

    # ---- plugin messaging: cross-boundary delivery, in both directions ----
    # These GATE the round. Before the novachat:main channel existed every one of
    # them failed (the downstream player's line never reached the proxy, and
    # their own chat was cancelled into NC-503); they are the point of the run.
    #
    #   proxy -> sub-server : Alice's fanout token reaching a player who is
    #                         connected STRAIGHT to the sub-server. The proxy
    #                         cannot render to that player (it does not know they
    #                         exist), so only plugin messaging can carry it.
    #   sub-server -> proxy : the downstream bot's own line reaching Alice/Bob
    #                         (asserted by their in-bot `expect-downstream`).
    #   no NC-503           : the downstream plugin no longer cancels the line
    #                         into "not connected to the backend".
    if ($downRes) {
      # GATING. The sender's own line coming back proves both halves: the
      # sub-server reached the proxy (otherwise the authority never saw it and
      # produced no fan-out) and the proxy wrote a fan-out back to the
      # sub-server (otherwise the direct-connect player, who is not among the
      # excluded proxy players, would have nothing to render).
      $assertions += Assert-Delivered "round$($round.Index)/downstream-upstream-roundtrip" $downToken $downRes
      $assertions += Assert-Absent "round$($round.Index)/downstream-no-nc503" 'NC-503' $downRes
      # The downstream echo arrives on the server main thread. If the plugin's
      # inbound handler fires an EVENT_ASYNC event from there, Paper/Purpur
      # throws and the whole render aborts -- the packet was received yet the
      # player sees nothing. Name that failure instead of reporting a generic
      # missing token.
      $assertions += Assert-LogAbsent "round$($round.Index)/downstream-render-no-async-violation" 'async-event' $javaLog `
        'ChannelMessageEvent may only be triggered asynchronously'

      # GATED: a proxy-ORIGINATED line reaching a direct-connect sub-server
      # player. Proxy players only see what ProxyChatCore renders locally, so
      # this token can arrive on the downstream bot only through
      # LocalAuthorityClient.bindChatBroadcast -> ProxyChannelHandler
      # .broadcastProxyChat. It used to be a diagnostic while that push did not
      # exist; it is now the assertion that proves the push works, so a
      # regression in the broadcast path fails the round instead of hiding in
      # an observation.
      $assertions += Assert-Delivered "round$($round.Index)/proxy-to-downstream-chat" $fanoutToken $downRes

      $downKinds = @($downRes.received | ForEach-Object { $_.kind } | Sort-Object -Unique)
      $downNote = "downstream bot received kinds: $($downKinds -join ', ')"
      Log "  $downNote"
      $result.notes = $downNote
    } else {
      Log "  downstream bot wrote no results.json (server may not have booted)"
      $assertions += [PSCustomObject]@{
        id = "round$($round.Index)/downstream-bot-ran"; token = 'results'
        pass = $false; evidence = 'no downstream.results.json'; kind = 'delivery'
      }
      $result.notes = 'downstream bot wrote no results.json'
    }

    # Duplicate-render guard: the proxy lists the players it already rendered to
    # in excludedRecipients, so a proxied player must see each line exactly once.
    # A count > 1 on a proxy bot means the de-duplication broke (the sub-server
    # rendered the same line the proxy had already delivered).
    foreach ($pair in @(@{ Role = 'alice'; Res = $aliceRes }, @{ Role = 'bob'; Res = $bobRes })) {
      if (-not $pair.Res) { continue }
      foreach ($tok in @(@{ Name = 'fanout'; Value = $fanoutToken }, @{ Name = 'downstream'; Value = $downToken })) {
        $hits = @($pair.Res.received | Where-Object {
          ($_.text -and $_.text.Contains($tok.Value)) -or ($_.raw -and $_.raw.Contains($tok.Value))
        })
        # mineflayer emits one `messagestr` per line; a `chat`/`systemChat` copy
        # of the SAME line is a second event for the same wire message, so count
        # only messagestr (the canonical "one line arrived" signal).
        $lineHits = @($hits | Where-Object { $_.kind -eq 'messagestr' })
        $assertions += [PSCustomObject]@{
          id = "round$($round.Index)/no-duplicate-render-$($pair.Role)-$($tok.Name)"
          token = $tok.Value
          pass = ($lineHits.Count -le 1)
          evidence = "messagestr matches: $($lineHits.Count) (all kinds: $($hits.Count))"
          kind = 'dedup'
        }
      }
    }

    # ---- moderation EFFECT scenarios (separate gating group) ----
    # Kept apart from the chat assertions above so a failure names the feature
    # that broke. See Invoke-ModerationScenarios for why lite cannot execute
    # them today; the reason is reported, never swallowed.
    $assertions += Invoke-ModerationScenarios -RoundIndex $round.Index -ProxyLog $proxyLog `
      -AliceRes $aliceRes -BobRes $bobRes -RunId "$tokenPrefix-lite"

    # `diagnostic` observations are recorded, never gated.
    $failed = @($assertions | Where-Object { -not $_.pass -and $_.kind -ne 'diagnostic' })
    if ($failed.Count -gt 0) {
      $result.status = 'FAIL'
      $result.notes = (($result.notes, "failed: $((($failed | ForEach-Object { $_.id }) -join ', '))") |
        Where-Object { $_ }) -join '; '
    } else {
      $result.status = 'PASS'
    }
  } catch {
    $result.status = 'FAIL'
    $result.notes = "lite round threw: $($_.Exception.Message)"
    Log "  LITE ROUND ERROR: $($_.Exception.Message)"
  } finally {
    if ($result.status -ne 'PASS') {
      if ($round.Proxy) {
        Write-LogTail (Join-Path (Join-Path $roundDir $round.Proxy) "proxy.stdout.log") 50 "lite proxy stdout"
      }
      foreach ($b in $bots) {
        $roleDir = if ($b.Role -eq 'downstream') { 'bots/downstream' } else { 'bots' }
        Write-LogTail (Join-Path (Join-Path $roundDir $roleDir) "$($b.Role).stdout.log") 40 "bot $($b.Role) stdout"
      }
    }
    Teardown-All
    Start-Sleep -Seconds 2
  }

  $result.assertions = $assertions
  return $result
}

# --- moderation EFFECT scenarios (separate gating group) ----------------------
#
# Why this exists as its own group instead of more steps in the happy-path chat
# plan: every chat assertion above proves a line ARRIVED, and a command that
# changes no state cannot fail any of them. The harness had never executed a
# single mute/kick/ban, so a command could report "success" while doing nothing
# and the run stayed green -- which is exactly what happened to PMMP's kick and
# mute (they sent `target`, the backend reads `targetName`, so they could never
# have worked). This group asserts STATE CHANGED, and a failure here names the
# feature that broke instead of hiding among the chat assertions.
#
# The lite topology is the only one that can run these today, and the reason is
# structural rather than a harness gap -- see $script:ModerationUnavailableReason
# in the round below. The shape it needs is already there: alice and bob are
# proxy-connected (they are the only players the lite authority can see), and a
# third player could observe from anywhere.
#
# WHAT EACH SCENARIO ASSERTS, and from whose recording:
#
#   mute-takes-effect      bob (observer) mutes alice (target) in `global`;
#                          alice speaks; bob's recording must NOT contain the
#                          token, and neither may any other observer's.
#                          GATING (negative) + GATING (control): alice speaks a
#                          CONTROL token in the same channel BEFORE the mute, so
#                          "she cannot speak at all" cannot fake the result.
#   mute-is-channel-scoped alice speaks in `local`, where she is not muted; the
#                          line MUST arrive. Proves the mute is not a blanket
#                          silence -- without this, "mute works" and "every line
#                          from alice is broken" are indistinguishable.
#   kick-takes-effect      bob kicks alice from `global`; alice speaks; nobody
#                          receives it (she is no longer a member).
#   kick-is-not-a-ban      alice rejoins `global`; the rejoin must SUCCEED and
#                          her next line must arrive. A kick that behaves like a
#                          ban would pass every "kick worked" assertion.
#   ban-blocks-rejoin      bob bans alice; alice's `/nc join global` must be
#                          REFUSED -- the refusal is rendered to alice as an
#                          NC-403 error line, which is her own recording.
#   unban-restores         bob unbans; alice's join succeeds and her line arrives.
#   permission-denied      alice (not an operator) tries to mute bob; the
#                          attempt must be refused (NC-403 to alice) AND bob's
#                          line must still arrive. Both halves gate: a refusal
#                          that still applies the effect is worse than none.
#
# HONEST LIMITS, stated so a green run is not over-read:
#
#   * The mute's effect on the SENDER is unobservable. The backend drops a muted
#     line silently (MessagePipeline SENDER_MUTED) and the lite authority only
#     sends NC-436 when the muted player speaks -- so the sender-side story is
#     "nothing happened", which no chat assertion can distinguish from a
#     dropped connection. Only the observer-side absence is asserted.
#   * The kick/mute TARGET notice (title + action bar) exists in the product
#     (`notifyKickMuteTarget`), but this group does not depend on it: the bot's
#     effect observation is a parallel agent's work, and a target notice proves
#     the NOTICE arrived, not that the state changed.
#   * `kick` here means "removed from the channel", not "disconnected from the
#     server". The product implements the former.
#
# Each token carries the run id, so a stale recording from an earlier run can
# never satisfy an assertion.

# Runs the moderation-effect group against an already-running lite topology.
#
# PRECONDITION, and the reason this function refuses to run rather than
# soft-passing: a moderation command must have an execution path to the
# authority. In lite the ONLY such path is a proxy-connected player running a
# /nc command whose plugin is connected and authenticated to a backend -- and
# lite starts no backend. A downstream plugin's /nc mute therefore stops at
# `checkConnection()` with NC-503 and never reaches the authority, while the
# proxy's own /nc has no moderation subcommand at all (ProxyCommandCore:
# help/join/leave/list/who/toggle/ignore/unignore/msg/r/reload).
#
# So the scenarios below become executable the moment EITHER (a) a proxy-side
# moderation command exists, or (b) a plugin gains an authenticated path into
# the lite authority. Until then every one of them is reported as `diagnostic`
# with the reason spelled out -- a named gap, not a green tick. That is the
# honest outcome: writing the assertions now and marking them gating would fail
# the run on an architectural absence the harness cannot fix, and marking them
# passing without running them would be the exact vacuity this group exists to
# prevent.
function Invoke-ModerationScenarios {
  param(
    [int]$RoundIndex,
    [string]$ProxyLog,
    $AliceRes,
    $BobRes,
    [string]$RunId
  )

  $prefix = "round$RoundIndex/mod"
  $out = @()

  # ---- 0. Reachability: could a moderation command execute at all? ----
  # This is the assertion that makes the rest honest. Without it every scenario
  # below would report "not executed" for a reason a reader has to take on
  # faith. Here the run's OWN evidence answers it: alice ran `/nc mute` as a
  # proxy-connected player, and whatever came back is in her recording.
  #
  # The probe is deliberately a real command with a real target and duration.
  # Two outcomes are possible, and both are informative:
  #   * a mute acknowledgement / refusal        -> moderation IS reachable on
  #     this topology; the scenarios below should be promoted to gating;
  #   * NC-404 "Not found"                      -> the proxy treated "mute" as
  #     a CHANNEL NAME (ProxyCommandCore's default branch forwards an unknown
  #     subcommand as a channel message), so no moderation path exists.
  #
  # Kind 'diagnostic': this records a product-shape fact, it does not gate. The
  # scenarios it explains are separately reported as not-executed.
  #
  # Match the response by shape, not by a bare substring: the word "mute" also
  # appears in the /nc help listing and in unrelated chat, and counting those
  # would report a reachable surface that does not exist.
  $probeHit = $null
  if ($AliceRes -and $AliceRes.received) {
    foreach ($entry in $AliceRes.received) {
      $t = if ($entry.text) { [string]$entry.text } else { [string]$entry.raw }
      if (-not $t) { continue }
      if ($t.Contains('NC-404') -or $t.Contains('NC-403') -or $t.Contains('NC-436') -or
          $t.Contains('Muting') -or $t.Contains('muted by') -or $t.Contains('Player muted')) {
        $probeHit = $entry; break
      }
    }
  }
  $reachable = $false
  $probeText = if ($probeHit) { [string]$probeHit.text } else { '' }
  $probeEvidence = if (-not $AliceRes) {
    'alice recorded nothing, so the probe cannot be judged'
  } elseif (-not $probeHit) {
    'alice recorded no response to /nc mute at all (no acknowledgement and no error)'
  } else {
    "alice's response to /nc mute E2ELiteBob: $probeText"
  }
  if ($probeHit) {
    # A response only counts as "reachable" when it is NOT the unknown-channel
    # fallthrough. NC-404 is the proxy saying it read "mute" as a channel name.
    $reachable = -not $probeText.Contains('NC-404')
  }
  $out += [PSCustomObject]@{
    id = "$prefix/moderation-command-reachable"
    token = 'probe-mute'
    pass = $reachable
    evidence = $probeEvidence
    kind = 'diagnostic'
  }

  $reason = if ($reachable) {
    'the probe shows a moderation surface, but this group has not been promoted to gating yet'
  } else {
    'no moderation execution path on this topology: /nc from a proxy-connected player has no mute/kick/ban subcommand (ProxyCommandCore treats the word as a channel name), and a downstream player''s /nc stops at checkConnection() because lite starts no backend'
  }

  # ---- 1..7: the effect scenarios ----
  # Each is reported with the assertion it WOULD make, so the gap is precise
  # and reviewable rather than a bare "skipped".
  $scenarios = @(
    @{ Id = 'mute-takes-effect';       What = 'muted target''s line never reaches any observer; a pre-mute control line from the same sender did' }
    @{ Id = 'mute-is-channel-scoped';  What = 'the same target speaks in an unmuted channel and the line DOES arrive' }
    @{ Id = 'kick-takes-effect';       What = 'kicked target''s next line never reaches any observer' }
    @{ Id = 'kick-is-not-a-ban';       What = 'the kicked target can rejoin the channel and is heard again' }
    @{ Id = 'ban-blocks-rejoin';       What = 'the banned target''s /nc join is refused (NC-403 rendered to the target)' }
    @{ Id = 'unban-restores';          What = 'after unban the join succeeds and the target is heard again' }
    @{ Id = 'permission-denied';       What = 'a non-operator''s mute is refused AND the target''s line still arrives' }
  )
  foreach ($s in $scenarios) {
    $out += [PSCustomObject]@{
      id = "$prefix/$($s.Id)"
      token = $s.What
      pass = $false
      evidence = "NOT EXECUTED: $reason"
      kind = 'diagnostic'
    }
  }
  return $out
}

# --- one round ---------------------------------------------------------------
# A round starts the backend once, then every leg, then the bots, then asserts
# token delivery across the receiver's recording. Teardown is unconditional.
#
# Role assignment: the java leg joins DIRECTLY when there is no proxy (a bot
# speaks to it), and when a proxy exists the java/mod legs are its downstreams
# while the bots speak to the proxy. The Bedrock leg is always joined directly
# -- Velocity does not front Bedrock servers.
function Invoke-Round($round, [string]$roundDir) {
  Log ""
  Log "=== ROUND $($round.Index): $(Format-RoundLabel $round) ($($round.ServerCount) server process(es)) ==="
  New-Item -ItemType Directory -Force -Path $roundDir | Out-Null

  $result = [PSCustomObject]@{
    round = $round.Index
    topology = (Format-RoundLabel $round)
    proxy = $round.Proxy
    java = $round.Java
    mod = $round.Mod
    bedrock = $round.Bedrock
    status = 'UNKNOWN'
    assertions = @()
    notes = ''
  }
  $assertions = @()
  $bots = @()

  # Per-run identities. Each server leg gets a DISTINCT backend client identity:
  # two legs sharing one clientId would make the backend treat them as the same
  # upstream and cross-server routing would be indistinguishable from local.
  $legs = @()
  if ($round.Java) { $legs += $round.Java }
  if ($round.Mod) { $legs += $round.Mod }
  if ($round.Bedrock) { $legs += $round.Bedrock }

  $clientSecrets = @{}
  $backendClients = @()
  foreach ($leg in $legs) {
    $id = $PlatformDefs[$leg].ClientId
    $pw = "joint-e2e-$leg-$($script:RunId)"
    $clientSecrets[$leg] = @{ Username = $id; Password = $pw }
    $backendClients += [PSCustomObject]@{ Username = $id; Password = $pw; DisplayName = "Joint $leg" }
  }

  # Relay per-server identities (proxy commit 95c9a85).
  #
  # The proxy opens ONE backend connection per downstream server when
  # backend.identities is populated, and each connection authenticates as that
  # server's own username. The harness therefore has to:
  #   1. name every downstream server (the velocity.toml [servers] key, which is
  #      also the id the sub-server puts in its plugin-messaging envelope), and
  #   2. register the matching username in novalink.yml `clients:` with the
  #      shared backend.password -- the backend rejects an unknown client.
  #
  # The identity is NOT the leg's own direct-link username ($clientSecrets[$leg]):
  # the sub-server plugin still opens its own backend connection in this
  # topology, and the backend keeps one connection per client id, so reusing the
  # id would make the two links supersede each other.
  #
  # backend.username is left EMPTY in the generated proxy config when every
  # downstream is mapped, which disables the legacy fallback link. That is what
  # makes "the backend saw exactly these identities" a real assertion: under the
  # old single-connection model the proxy would authenticate as one shared id.
  $relayDownstreams = @()
  if ($round.Proxy) {
    if ($round.Java) { $relayDownstreams += $round.Java }
    if ($round.Mod) { $relayDownstreams += $round.Mod }
  }
  $relayIdentities = @{}
  $relaySharedPassword = "joint-e2e-relay-$($script:RunId)"
  foreach ($server in $relayDownstreams) {
    $relayIdentities[$server] = "E2E_Relay_$server"
    $backendClients += [PSCustomObject]@{
      Username = $relayIdentities[$server]; Password = $relaySharedPassword
      DisplayName = "Joint relay link for $server"
    }
  }
  if ($relayDownstreams.Count -gt 0) {
    Log "  relay identities: $(@($relayDownstreams | ForEach-Object { "$_=$($relayIdentities[$_])" }) -join ', ') (shared password, no fallback link)"
  }

  if ($round.Proxy) {
    if ($relayIdentities.Count -gt 0) {
      # The proxy itself is no longer a backend client: its links authenticate
      # as the downstream identities above.
      $clientSecrets[$round.Proxy] = @{ Username = ''; Password = $relaySharedPassword }
    } else {
      # No downstream behind the proxy: keep the historical single client.
      $proxyId = 'E2E_JointProxy'
      $proxyPw = "joint-e2e-proxy-$($script:RunId)"
      $clientSecrets[$round.Proxy] = @{ Username = $proxyId; Password = $proxyPw }
      $backendClients += [PSCustomObject]@{ Username = $proxyId; Password = $proxyPw; DisplayName = "Joint $($round.Proxy) proxy" }
    }
  }

  # Generated secrets. The super-admin UUID is the offline-mode UUID of the bot
  # username, which is what the backend keys super-admins by (set in main).
  $superAdminPassword = "joint-super-$($script:RunId)"

  $backendDir = Join-Path $roundDir "backend"
  $backendWork = Join-Path $backendDir "work"
  New-Item -ItemType Directory -Force -Path $backendWork | Out-Null
  $backendConfig = Join-Path $backendDir "novalink.yml"
  if ($ProxyMode -eq 'relay') {
    Write-BackendConfig -path $backendConfig -clients $backendClients -superAdminPassword $superAdminPassword
    Log "  backend config: $backendConfig ($($backendClients.Count) clients, super-admin enabled)"
  } else {
    Log "  lite mode: no backend is started (the proxy is self-contained)"
  }

  try {
    # ---- backend ----
    # Lite has no backend at all: the proxy runs a LocalAuthority in-process and
    # every downstream plugin would have nothing to authenticate against.
    if ($ProxyMode -eq 'relay') {
      $bk = Start-Backend -configPath $backendConfig -workDir $backendWork `
        -stdoutPath (Join-Path $backendDir "stdout.log") -stderrPath (Join-Path $backendDir "stderr.log")
      if (-not $bk) {
        $result.status = 'FAIL'; $result.notes = 'backend did not become ready'
        return $result
      }

      # Panel token for the attribution checks (GET /api/messages needs ADMIN+;
      # the super-admin entry in the generated config is also a panel account
      # -- NovaLinkCore registers every super-admin for web-panel login).
      $script:RelayPanelJwt = Connect-BackendRest 'E2E_SuperAdmin' $superAdminPassword
      if ($script:RelayPanelJwt) {
        Log "  backend panel login ok (attribution evidence via GET /api/messages)"
      } else {
        Log "  WARN: backend panel login failed; per-message attribution will be reported as unverified"
      }
    }

    # ---- servers ----
    $serverProcs = @{}
    $downstreams = @{}
    $ports = @{
      Java = $JavaPort; JavaRcon = $JavaRconPort
      Mod = $ModPort; ModRcon = $ModRconPort
      Bedrock = $BedrockAPort; BedrockRcon = $BedrockARconPort
    }

    if ($round.Java) {
      $dir = Join-Path $roundDir $round.Java
      $p = Start-PlatformServer -platform $round.Java -serverDir $dir `
        -port $ports.Java -rconPort $ports.JavaRcon `
        -clientId $clientSecrets[$round.Java].Username -clientPassword $clientSecrets[$round.Java].Password `
        -stdoutPath (Join-Path $dir "server.stdout.log") -stderrPath (Join-Path $dir "server.stderr.log")
      if ($p) { $serverProcs[$round.Java] = $p; $downstreams[$round.Java] = $ports.Java }
    }
    if ($round.Mod) {
      $dir = Join-Path $roundDir $round.Mod
      $p = Start-PlatformServer -platform $round.Mod -serverDir $dir `
        -port $ports.Mod -rconPort $ports.ModRcon `
        -clientId $clientSecrets[$round.Mod].Username -clientPassword $clientSecrets[$round.Mod].Password `
        -stdoutPath (Join-Path $dir "server.stdout.log") -stderrPath (Join-Path $dir "server.stderr.log")
      if ($p) { $serverProcs[$round.Mod] = $p; $downstreams[$round.Mod] = $ports.Mod }
    }
    if ($round.Bedrock) {
      $dir = Join-Path $roundDir $round.Bedrock
      $p = Start-PlatformServer -platform $round.Bedrock -serverDir $dir `
        -port $ports.Bedrock -rconPort $ports.BedrockRcon `
        -clientId $clientSecrets[$round.Bedrock].Username -clientPassword $clientSecrets[$round.Bedrock].Password `
        -stdoutPath (Join-Path $dir "server.stdout.log") -stderrPath (Join-Path $dir "server.stderr.log")
      if ($p) { $serverProcs[$round.Bedrock] = $p }
    }

    # ---- proxy (after its downstreams exist, so velocity.toml can list them) ----
    if ($round.Proxy) {
      $dir = Join-Path $roundDir $round.Proxy
      New-Item -ItemType Directory -Force -Path $dir | Out-Null
      $jdkPath = Get-JdkPath $PlatformDefs[$round.Proxy].Jdk
      if (-not $jdkPath) {
        $result.status = 'FAIL'; $result.notes = "runtime JDK $($PlatformDefs[$round.Proxy].Jdk) unresolved for $($round.Proxy)"
        return $result
      }
      # The plugin jar has to be in place BEFORE the proxy boots: Velocity scans
      # plugins/ once. Installing it was missing here, which made every proxy
      # round silently test a bare proxy -- the downstream legs connect to the
      # backend themselves, so all cross-server assertions still passed.
      $null = Install-Plugin $round.Proxy $dir
      if ($round.Proxy -eq 'velocity') {
        Write-VelocityToml -path (Join-Path $dir "velocity.toml") -downstreams $downstreams
        $plugCfgDir = Join-Path $dir "plugins/novachat"
        New-Item -ItemType Directory -Force -Path $plugCfgDir | Out-Null
        Write-ProxyPluginConfig -path (Join-Path $plugCfgDir "config.toml") `
          -clientId $clientSecrets[$round.Proxy].Username -clientPassword $clientSecrets[$round.Proxy].Password `
          -mode $ProxyMode -identities $(if ($ProxyMode -eq 'relay') { $relayIdentities } else { $null })
      }
      $jarPath = Join-Path $DistDir $script:DefJarName[$round.Proxy]
      $java = Join-Path $jdkPath "bin/java.exe"
      if (-not (Test-Path -LiteralPath $java)) { $java = Join-Path $jdkPath "bin/java" }
      $p = Start-JvmProcess -JavaExe $java `
        -Arguments @("-Xms256M", "-Xmx512M", "-jar", $jarPath) `
        -WorkingDirectory $dir `
        -StdoutPath (Join-Path $dir "proxy.stdout.log") -StderrPath (Join-Path $dir "proxy.stderr.log")
      if (-not $p) {
        $result.status = 'FAIL'; $result.notes = "$($round.Proxy) proxy could not be started"
        return $result
      }
      Track-Pid $p.Id "$($round.Proxy)-proxy"
      $serverProcs[$round.Proxy] = $p
      Log "  $($round.Proxy) pid=$($p.Id) port=$ProxyPort jdk=$($PlatformDefs[$round.Proxy].Jdk)"
    }

    # ---- wait for readiness (ALL servers concurrently) ----
    # A sequential per-server wait gives the SECOND server a fresh timeout that
    # starts only after the first finished, so a slow-but-healthy first boot
    # starves the rest of the round budget -- and if a later server is slow the
    # earlier ones can sit past their own readiness while nothing observes it.
    # Poll every server against ONE shared deadline instead; that is also what
    # "at most N processes concurrently" implies.
    # A per-server PSCustomObject (not a nested hashtable): assigning
    # `$entry = $pendingReady[$name]` on a hashtable of hashtables hands back a
    # COPY, so `$entry.Ready = $true` updates the copy and the loop spins until
    # the deadline even though the marker matched. That is exactly the
    # "server(s) never became ready: bukkit" this harness produced with the log
    # already containing `Done (176.943s)!`.
    $pendingReady = @()
    foreach ($name in $serverProcs.Keys) {
      $def = $PlatformDefs[$name]
      $dir = Join-Path $roundDir $name
      # Some engines do not write their readiness line to stdout. Paper-family
      # servers (Purpur/Folia) install their own log4j appender and send almost
      # everything to logs/latest.log; the stdout our launcher captures ends up
      # with the paperclip bootstrap lines and little else. Watch BOTH files and
      # treat a match in either as ready.
      $logs = @()
      if ($def.Kind -eq 'proxy') {
        $logs += (Join-Path $dir "proxy.stdout.log")
      } else {
        $logs += (Join-Path $dir "server.stdout.log")
        $logs += (Join-Path $dir "logs/latest.log")
      }
      $pendingReady += [PSCustomObject]@{
        Name = $name
        Pattern = $def.ReadyPattern
        Logs = $logs
        Log = $logs[0]      # diagnostics: the file we would tail on failure
        Ready = $false
      }
    }
    $readyDeadline = (Get-Date).AddSeconds($ServerReadySec)
    while ((Get-Date) -lt $readyDeadline -and @($pendingReady | Where-Object { -not $_.Ready }).Count -gt 0) {
      foreach ($entry in $pendingReady) {
        if ($entry.Ready) { continue }
        foreach ($logFile in $entry.Logs) {
          $text = Read-LogText $logFile
          if ($text -and ($text -match $entry.Pattern)) {
            $entry.Ready = $true
            Log "  $($entry.Name) ready (matched in $(Split-Path -Leaf $logFile))"
            break
          }
        }
      }
      if (@($pendingReady | Where-Object { -not $_.Ready }).Count -gt 0) { Start-Sleep -Seconds 2 }
    }

    $notReady = @($pendingReady | Where-Object { -not $_.Ready })
    if ($notReady.Count -gt 0) {
      $result.status = 'FAIL'
      $result.notes = "server(s) never became ready within ${ServerReadySec}s: $(($notReady | ForEach-Object { $_.Name }) -join ', ')"
      foreach ($entry in $notReady) { Write-LogTail $entry.Log 40 "$($entry.Name) stdout" }
      return $result
    }
    Start-Sleep -Seconds 3

    # ---- bots ----
    # Token naming: <runId>-<round>-<leg>-<n>, unique across rounds so a stale
    # recording from an earlier round can never satisfy a later assertion.
    $tokenPrefix = "$($script:RunId)-r$($round.Index)"
    $botWork = Join-Path $roundDir "bots"
    New-Item -ItemType Directory -Force -Path $botWork | Out-Null
    $handoffDir = Join-Path $botWork "handoff"
    New-Item -ItemType Directory -Force -Path $handoffDir | Out-Null

    # Bot target: the proxy when one exists (that is the topology under test),
    # otherwise the Java leg directly.
    $javaTargetPort = if ($round.Proxy) { $ProxyPort } else { $ports.Java }
    $javaKind = 'java'
    $javaMc = if ($round.Java) { (Get-LockedServer -Name $PlatformDefs[$round.Java].LockName).BotMcVersion } else { '1.21.11' }
    if (-not $javaMc) { $javaMc = '1.21.11' }

    # Bedrock protocol version the bot speaks.
    #
    # Order: the platform's explicit BotMcVersion (PlatformDefs), then the
    # lock's BotMcVersion, then a safe default.
    #
    # The lock's MC field is NEVER used here. For Bedrock engines it holds the
    # SERVER release line, not a protocol version: `nukkit` has MC
    # "1.0-SNAPSHOT", which bedrock-protocol rejects outright with
    # "Unsupported version 1.0-SNAPSHOT". Every Bedrock bot died before login
    # and each cross-server assertion failed for the wrong reason.
    #
    # The value must match the server's protocol: Nukkit 1.26.40 answers
    # "disconnectionScreen.outdatedClient" to a 1.26.30 client, so the default
    # tracks the newest protocol minecraft-data ships for this line.
    $bedrockMc = '1.26.40'
    if ($round.Bedrock) {
      $bDef = $PlatformDefs[$round.Bedrock]
      if ($bDef.BotMcVersion) {
        $bedrockMc = $bDef.BotMcVersion
      } else {
        $bLock = Get-LockedServer -Name $bDef.LockName
        if ($bLock -and $bLock.BotMcVersion) {
          $bedrockMc = $bLock.BotMcVersion
        } else {
          Log "  NOTE: no BotMcVersion for $($round.Bedrock); using protocol $bedrockMc"
        }
      }
    }

    # Bedrock player names must be alphanumeric (no underscores): Nukkit answers
    # `disconnectionScreen.invalidName` and closes the connection during login,
    # so the bot never spawns and writes no messages. Java allows underscores,
    # but both names stay underscore-free here so one naming rule covers both.
    $javaBotUser = 'E2EJointJava'
    $bedBotUser = 'E2EJointBedrock'
    $chatToken = "$tokenPrefix-java-to-bedrock"
    $replyToken = "$tokenPrefix-bedrock-to-java"

    $javaSteps = @(
      @{ id = 'join-global'; action = 'cmd'; text = '/nc join global' },
      @{ id = 'settle'; action = 'wait'; ms = 4000 },
      @{ id = 'send-cross'; action = 'chat'; text = "joint-java-to-bedrock $chatToken" },
      @{ id = 'expect-reply'; action = 'expect'; token = $replyToken; timeoutMs = 45000 },
      @{ id = 'quit'; action = 'quit' }
    )
    $bedSteps = @(
      @{ id = 'join-global'; action = 'cmd'; text = '/nc join global' },
      @{ id = 'settle'; action = 'wait'; ms = 4000 },
      @{ id = 'expect-java'; action = 'expect'; token = $chatToken; timeoutMs = 45000 },
      @{ id = 'send-reply'; action = 'chat'; text = "joint-bedrock-to-java $replyToken" },
      @{ id = 'settle2'; action = 'wait'; ms = 3000 },
      @{ id = 'quit'; action = 'quit' }
    )

    # Plans are written WITHOUT a BOM: Node's fs.readFileSync(path,'utf8') keeps
    # a leading U+FEFF and JSON.parse rejects it, so a Set-Content -Encoding utf8
    # plan would kill every bot at startup on PowerShell 5.1.
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    $javaPlanPath = Join-Path $botWork "plan-java.json"
    $bedPlanPath = Join-Path $botWork "plan-bedrock.json"
    $javaPlanJson = New-CrossServerPlan -role 'java' -kind $javaKind -username $javaBotUser -port $javaTargetPort `
      -mcVersion $javaMc -resultsFile (Join-Path $botWork "java.results.json") -handoffDir $handoffDir `
      -timeoutMs ($BotTimeoutSec * 1000) -runId "$($script:RunId)-r$($round.Index)" -steps $javaSteps
    $bedPlanJson = New-CrossServerPlan -role 'bedrock' -kind 'bedrock' -username $bedBotUser -port $ports.Bedrock `
      -mcVersion $bedrockMc -resultsFile (Join-Path $botWork "bedrock.results.json") -handoffDir $handoffDir `
      -timeoutMs ($BotTimeoutSec * 1000) -runId "$($script:RunId)-r$($round.Index)" -steps $bedSteps
    [System.IO.File]::WriteAllText($javaPlanPath, $javaPlanJson, $utf8NoBom)
    [System.IO.File]::WriteAllText($bedPlanPath, $bedPlanJson, $utf8NoBom)

    $bots += [PSCustomObject]@{ Role = 'java'; Process = (Start-Bot 'java' $javaPlanPath $botWork `
      (Join-Path $botWork "java.stdout.log") (Join-Path $botWork "java.stderr.log")) }
    $bots += [PSCustomObject]@{ Role = 'bedrock'; Process = (Start-Bot 'bedrock' $bedPlanPath $botWork `
      (Join-Path $botWork "bedrock.stdout.log") (Join-Path $botWork "bedrock.stderr.log")) }

    Wait-Bots $bots $BotTimeoutSec

    # ---- cross-process assertions ----
    $javaRes = Read-BotResult (Join-Path $botWork "java.results.json")
    $bedRes = Read-BotResult (Join-Path $botWork "bedrock.results.json")

    $assertions += Assert-Delivered "round$($round.Index)/java-to-bedrock" $chatToken $bedRes
    $assertions += Assert-Delivered "round$($round.Index)/bedrock-to-java" $replyToken $javaRes
    $assertions += Assert-BotExpectations 'java' $javaRes
    $assertions += Assert-BotExpectations 'bedrock' $bedRes
    if ($round.Proxy) {
      $assertions += Assert-ProxyPluginLoaded "round$($round.Index)/proxy-plugin-loaded" $round.Proxy `
        (Join-Path (Join-Path $roundDir $round.Proxy) "proxy.stdout.log")
    }

    # ---- relay: per-server backend identities (commit 95c9a85) ----
    # Gating, but only meaningful with at least two downstream servers behind
    # the proxy: with one, "one dedicated link" and "the old shared link" are
    # the same number of connections, so the topology assertion says so
    # explicitly instead of reporting a vacuous pass.
    if ($ProxyMode -eq 'relay') {
      $assertions += Assert-RelayIdentitiesCoverage "round$($round.Index)/relay-identities-topology" `
        $relayDownstreams
      if ($relayDownstreams.Count -gt 0) {
        $backendLog = Join-Path $roundDir "backend/stdout.log"
        $proxyLog = Join-Path (Join-Path $roundDir $round.Proxy) "proxy.stdout.log"
        $expectedIds = @($relayDownstreams | ForEach-Object { $relayIdentities[$_] })
        # The fallback identity would be the harness's own proxy client id; it
        # must NOT authenticate in this run.
        $proxyOwnedIds = @('E2E_JointProxy')
        $assertions += Assert-RelayLinksCreated "round$($round.Index)/relay-links-created" `
          $proxyLog $relayDownstreams
        $assertions += Assert-RelayDistinctIdentities "round$($round.Index)/relay-distinct-backend-identities" `
          $backendLog $expectedIds $proxyOwnedIds
        # Per-server attribution, from the backend's own persistence: the
        # message record the backend wrote for this run's token names the
        # clientId of the CONNECTION that carried it. That is the exact
        # property commit 95c9a85 changes.
        #
        # The chat bot connects through the PROXY (see $javaTargetPort above),
        # and the proxy has no fallback link here, so the line the backend
        # received must have ridden the Java leg's dedicated link.
        $chatSourceServer = if ($round.Java) { $round.Java } elseif ($round.Mod) { $round.Mod } else { $null }
        if ($chatSourceServer) {
          $assertions += Assert-RelayAttributionAndDelivery "round$($round.Index)/relay-chat-attribution-and-delivery" `
            $chatToken $relayIdentities[$chatSourceServer] $script:RelayPanelJwt $bedRes
          # Proxy-log corroboration (diagnostic: relay debug is off in relay,
          # so the decoded-packet line that prints clientId is not emitted).
          $assertions += Assert-RelayAttribution "round$($round.Index)/relay-chat-attribution-proxy-log" `
            $chatToken $relayIdentities[$chatSourceServer] $proxyLog
        }
        # No cross-server bleed: each receiver must see its line exactly once.
        # Both directions are checked -- a duplicate is most likely to appear
        # on the reply path (a sub-server broadcasting what the backend already
        # fanned out on the same link), which the forward direction misses.
        $assertions += Assert-NoDuplicateDelivery "round$($round.Index)/relay-no-duplicate-delivery-java-to-bedrock" `
          $chatToken $bedRes
        $assertions += Assert-NoDuplicateDelivery "round$($round.Index)/relay-no-duplicate-delivery-bedrock-to-java" `
          $replyToken $javaRes
      }
    }

    if (-not $javaRes) { $result.notes = 'java bot wrote no results.json' }
    if (-not $bedRes) {
      $result.notes = (($result.notes, 'bedrock bot wrote no results.json') | Where-Object { $_ }) -join '; '
    }

    # `diagnostic` observations are recorded, never gated -- same rule as the
    # lite round. Without the kind filter an intentionally-failed diagnostic
    # (e.g. relay-identities-topology on a one-downstream topology) would fail
    # the round while the summary printed it as INFO.
    $failed = @($assertions | Where-Object { -not $_.pass -and $_.kind -ne 'diagnostic' })
    if ($failed.Count -gt 0) {
      $result.status = 'FAIL'
      $result.notes = (($result.notes, "failed: $((($failed | ForEach-Object { $_.id }) -join ', '))") |
        Where-Object { $_ }) -join '; '
    } else {
      $result.status = 'PASS'
    }
  } catch {
    $result.status = 'FAIL'
    $result.notes = "round threw: $($_.Exception.Message)"
    Log "  ROUND ERROR: $($_.Exception.Message)"
  } finally {
    # Dump diagnostics before teardown so the log files are still being written,
    # then stop every process this round started (in reverse order).
    if ($result.status -ne 'PASS') {
      foreach ($name in @($round.Java, $round.Mod, $round.Bedrock, $round.Proxy)) {
        if (-not $name) { continue }
        $dir = Join-Path $roundDir $name
        Write-LogTail (Join-Path $dir "server.stdout.log") 30 "$name stdout"
        Write-LogTail (Join-Path $dir "proxy.stdout.log") 30 "$name proxy stdout"
      }
      Write-LogTail (Join-Path $roundDir "backend/stdout.log") 30 "backend stdout"
      foreach ($b in $bots) {
        Write-LogTail (Join-Path (Join-Path $roundDir "bots") "$($b.Role).stdout.log") 40 "bot $($b.Role) stdout"
      }
    }
    Teardown-All

    # Cache any world this round generated so the NEXT round (and the next run)
    # can skip chunk generation. Only Java-style engines are cached: their
    # world format is stable across platforms in this harness, and the Bedrock
    # engines use a different layout entirely.
    foreach ($pf in @($round.Java, $round.Mod)) {
      if (-not $pf) { continue }
      $generated = Join-Path (Join-Path $roundDir $pf) "world"
      $cacheDir = Join-Path (Join-Path $JointRoot "seed") $pf
      # Only cache a world that actually finished generating. A round whose
      # server never reached "Done (" leaves a world dir with no level.dat and
      # an empty region/; caching that poisons every later round (see the
      # completeness check in Start-PlatformServer).
      $worldComplete = (Test-Path -LiteralPath (Join-Path $generated "level.dat")) -and
                       (Test-Path -LiteralPath (Join-Path $generated "region")) -and
                       @(Get-ChildItem -LiteralPath (Join-Path $generated "region") -Filter "*.mca" -ErrorAction SilentlyContinue).Count -gt 0
      if ($worldComplete -and -not (Test-Path -LiteralPath (Join-Path $cacheDir "world"))) {
        try {
          New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
          Copy-Item $generated (Join-Path $cacheDir "world") -Recurse -Force
          Log "  cached generated world for $pf"
        } catch {
          Log "  WARN: could not cache world for ${pf}: $($_.Exception.Message)"
        }
      } elseif (-not $worldComplete) {
        Log "  not caching world for ${pf}: no complete world was generated"
      }
    }
    Start-Sleep -Seconds 2
  }

  $result.assertions = $assertions
  return $result
}

# --- prerequisite checks -----------------------------------------------------
function Test-Prereqs([string[]]$requestedPlatforms) {
  $issues = @()
  $harnessFiles = @(
    (Join-Path $RepoRoot "test/versions.lock.ps1"),
    (Join-Path $binDir "fetch-server.ps1"),
    (Join-Path $BotDir "package.json"),
    (Join-Path $BotDir "package-lock.json"),
    $jointBotScript
  )
  foreach ($f in $harnessFiles) {
    if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { $issues += "required file missing: $f" }
  }

  # JDKs: every platform leg's runtime JDK plus the Gradle launcher.
  $needed = @{ 'Jdk21' = $true }
  foreach ($pf in $requestedPlatforms) {
    $name = Resolve-PlatformName $pf
    if (-not $name -or -not $PlatformDefs.ContainsKey($name)) { continue }
    if ($PlatformDefs[$name].Jdk) { $needed[$PlatformDefs[$name].Jdk] = $true }
    if ($PlatformDefs[$name].BuildJdk) { $needed[$PlatformDefs[$name].BuildJdk] = $true }
  }
  foreach ($jdkVar in @('Jdk17', 'Jdk21', 'Jdk25')) {
    if (-not $needed.ContainsKey($jdkVar)) { continue }
    $path = Get-JdkPath $jdkVar
    $exe = if ($path) { Join-Path $path "bin/java.exe" } else { "" }
    if (-not $exe -or -not (Test-Path -LiteralPath $exe)) {
      $num = $jdkVar -replace '^Jdk', ''
      $issues += "JDK $num not found (pass -$jdkVar or set JAVA_HOME_${num}_X64); required by a requested platform"
    }
  }

  if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    $issues += "Node.js (node) not found on PATH -- required for the mineflayer / bedrock-protocol bots"
  }
  if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    $issues += "npm not found on PATH -- required to install bot dependencies"
  }
  return $issues
}

function Install-BotDeps {
  $nodeModules = Join-Path $BotDir "node_modules"
  if ((Test-Path (Join-Path $nodeModules "mineflayer")) -and
      (Test-Path (Join-Path $nodeModules "bedrock-protocol"))) {
    Log "bot dependencies already installed"
    return $true
  }
  Log "installing bot dependencies (npm ci) in $BotDir"
  $out = Join-Path $JointRoot "npm-ci.log"
  $err = Join-Path $JointRoot "npm-ci.err.log"
  # npm ci, not install: the committed lock is the contract, and a silent lock
  # drift would make a joint run unreproducible.
  $proc = Start-Process -FilePath "npm" -ArgumentList @("ci", "--no-audit", "--no-fund") `
    -WorkingDirectory $BotDir -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -NoNewWindow -Wait
  if ($proc.ExitCode -ne 0) {
    Log "ERROR: npm ci failed (exit=$($proc.ExitCode))"
    Write-LogTail $err 20 "npm stderr"
    return $false
  }
  Log "bot dependencies installed"
  return $true
}

# --- bot self-test -----------------------------------------------------------
# Runs before any platform resolution: it needs no server, no JDK, no port and
# no topology, so it stays usable on a machine where none of those exist.
if ($SelfTest) {
  Log "=== JOINT BOT SELF-TEST (no servers) ==="
  $botScript = Join-Path (Join-Path $RepoRoot "test/joint/bots") "joint-bot.js"
  if (-not (Test-Path -LiteralPath $botScript)) {
    Log "FATAL: joint-bot.js not found at $botScript"
    exit 2
  }
  & node $botScript --selftest
  $selftestExit = $LASTEXITCODE
  if ($selftestExit -eq 0) {
    Log "=== JOINT BOT SELF-TEST PASSED ==="
    exit 0
  }
  Log "=== JOINT BOT SELF-TEST FAILED (exit $selftestExit) ==="
  exit 1
}

# --- main --------------------------------------------------------------------
Log "=== NovaChat JOINT E2E START (run $($script:RunId)) ==="
Log "RepoRoot: $RepoRoot"
Log "Platforms: $Platforms"
Log "ProxyMode: $ProxyMode"
Log "JDK21: $(if ($Jdk21) { $Jdk21 } else { '<unresolved>' })"
Log "JDK25: $(if ($Jdk25) { $Jdk25 } else { '<unresolved>' })"
Log "JDK17: $(if ($Jdk17) { $Jdk17 } else { '<unresolved>' })"
Log "Max concurrent servers per round: $MaxConcurrentServers"

$requested = $Platforms -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
if ($requested.Count -eq 0) { Log "FATAL: -Platforms is empty"; exit 2 }

$split = Split-PlatformLegs $requested
if ($split.Unknown.Count -gt 0) {
  Log "FATAL: unknown platform(s): $($split.Unknown -join ', ')"
  Log "  valid: $($PlatformDefs.Keys -join ', ')  (aliases: $($PlatformAliases.Keys -join ', '))"
  exit 2
}
$legs = $split.Legs
if (-not $legs.Java -and -not $legs.Mod -and -not $legs.Bedrock) {
  Log "FATAL: -Platforms must name at least one server leg (a proxy alone has nothing to front)"
  exit 2
}
Log "Legs: proxy=$(if ($legs.Proxy) { $legs.Proxy } else { '-' }) java=$($legs.Java -join ',') mod=$($legs.Mod -join ',') bedrock=$($legs.Bedrock -join ',')"

# @() around the pipeline matters: an empty -BedrockRotation splits to one empty
# string, the Where-Object drops it, and the result is $null rather than an empty
# array. On PowerShell 5.1 `$null.Count` is a hard error under StrictMode and an
# inconsistent 0 elsewhere, so normalise it here.
$bedrockSweep = @()
$javaSweep = @()
if (-not [string]::IsNullOrWhiteSpace($BedrockRotation)) {
  $bedrockSweep = @($BedrockRotation -split ',' |
    ForEach-Object { Resolve-PlatformName $_.Trim() } | Where-Object { $_ })
}
if (-not [string]::IsNullOrWhiteSpace($JavaRotation)) {
  $javaSweep = @($JavaRotation -split ',' |
    ForEach-Object { Resolve-PlatformName $_.Trim() } | Where-Object { $_ })
}

# Default rotation lists when the caller did not name one. The intent of the
# round strategy is to CHANGE a leg, and a rotation list of one entry cannot do
# that, so the default sweeps every executable platform of that kind: Bedrock
# legs are cheap to swap (nukkit <-> pmmp <-> pnx) and Java legs are the two
# plugin platforms (bukkit <-> folia).
#
# Note the asymmetry: a caller who names ONE bedrock platform in -Platforms still
# gets the full default rotation. That is intentional for -Rounds > 1 -- the
# point of extra rounds is to move a leg. Pass -BedrockRotation / -JavaRotation
# explicitly to pin it, or -Rounds 1 to stay on a single topology.
if ($bedrockSweep.Count -eq 0 -and $legs.Bedrock.Count -gt 0) {
  $bedrockSweep = @($legs.Bedrock[0])
  foreach ($candidate in @('nukkit', 'pmmp', 'pnx')) {
    if ($candidate -ne $legs.Bedrock[0] -and ($bedrockSweep -notcontains $candidate)) {
      $bedrockSweep += $candidate
    }
  }
  Log "Bedrock rotation (default): $($bedrockSweep -join ' -> ')"
}
if ($javaSweep.Count -eq 0 -and $legs.Java.Count -gt 0) {
  $javaSweep = @($legs.Java[0])
  foreach ($candidate in @('bukkit', 'folia')) {
    if ($candidate -ne $legs.Java[0] -and ($javaSweep -notcontains $candidate)) {
      $javaSweep += $candidate
    }
  }
  Log "Java rotation (default): $($javaSweep -join ' -> ')"
}

$roundList = Build-Rounds -legs $legs -bedrockSweep $bedrockSweep -javaSweep $javaSweep -count $Rounds

# Enforce the concurrency ceiling before anything starts. The round builder is
# the only place topologies are constructed, so checking here is enough.
foreach ($r in $roundList) {
  if ($r.ServerCount -gt $MaxConcurrentServers) {
    Log "FATAL: round $($r.Index) needs $($r.ServerCount) server processes, ceiling is $MaxConcurrentServers"
    exit 2
  }
}
Log "Planned rounds:"
foreach ($r in $roundList) { Log "  round $($r.Index): $(Format-RoundLabel $r)" }

$prereqIssues = Test-Prereqs $requested
if ($prereqIssues.Count -gt 0) {
  Log "PREREQ FAILURES:"
  foreach ($i in $prereqIssues) { Log "  - $i" }
  exit 2
}

# Port preflight. A stale process from an earlier run (or another harness on the
# same box) makes the backend die with BindException and the round reports only
# "backend did not become ready", which sends the reader looking at the backend
# config instead of at the port. Name the conflict here.
# Lite starts no backend, so its ports are not required (and must not block a
# lite probe just because something unrelated holds the relay backend port).
$requiredPorts = @()
if ($ProxyMode -eq 'relay') {
  $requiredPorts += @{ Name = 'backend tcp'; Port = $BackendPort }
  $requiredPorts += @{ Name = 'backend ws'; Port = $BackendWsPort }
}
foreach ($r in $roundList) {
  if ($r.Proxy) { $requiredPorts += @{ Name = "$($r.Proxy) proxy"; Port = $ProxyPort } }
  if ($r.Java) { $requiredPorts += @{ Name = "$($r.Java) game"; Port = $JavaPort }; $requiredPorts += @{ Name = "$($r.Java) rcon"; Port = $JavaRconPort } }
  if ($r.Mod) { $requiredPorts += @{ Name = "$($r.Mod) game"; Port = $ModPort }; $requiredPorts += @{ Name = "$($r.Mod) rcon"; Port = $ModRconPort } }
  if ($r.Bedrock) {
    if ($r.Bedrock -eq 'pmmp') {
      $requiredPorts += @{ Name = "$($r.Bedrock) game"; Port = $BedrockAPort }
    } else {
      $requiredPorts += @{ Name = "$($r.Bedrock) game"; Port = $BedrockAPort }
      $requiredPorts += @{ Name = "$($r.Bedrock) rcon"; Port = $BedrockARconPort }
    }
  }
}
$busyPorts = @()
$seenPorts = @{}
foreach ($p in $requiredPorts) {
  # Dedupe by PORT, not by name: several roles can legitimately share a name
  # when two rounds use the same leg, and probing a port twice is just noise.
  if ($seenPorts.ContainsKey($p.Port)) { continue }
  $seenPorts[$p.Port] = $true
  if (Test-PortOpen $p.Port) { $busyPorts += "$($p.Name) on $($p.Port)" }
}
if ($busyPorts.Count -gt 0) {
  Log "PREREQ FAILURE: port(s) already in use:"
  foreach ($b in $busyPorts) { Log "  - $b" }
  Log "  A previous run may have left a process behind; stop it or pick different -BackendPort/-JavaPort/... values."
  exit 2
}

if (-not (Resolve-PlatformJars (@($legs.Proxy) + $legs.Java + $legs.Mod + $legs.Bedrock | Where-Object { $_ }))) {
  exit 2
}

if ($PlanOnly) {
  Log ""
  Log "=== PLAN ONLY: no process started ==="
  foreach ($r in $roundList) {
    Log "  round $($r.Index): $(Format-RoundLabel $r)  (servers: $($r.ServerCount))"
    if ($r.Java) { Log "    java     : $($r.Java)  jar=$(if ($script:DefJarName[$r.Java]) { $script:DefJarName[$r.Java] } else { '<none>' })  jdk=$($PlatformDefs[$r.Java].Jdk)" }
    if ($r.Mod) { Log "    mod      : $($r.Mod)  jar=$(if ($script:DefJarName[$r.Mod]) { $script:DefJarName[$r.Mod] } else { '<none>' })  jdk=$($PlatformDefs[$r.Mod].Jdk)" }
    if ($r.Bedrock) { Log "    bedrock  : $($r.Bedrock)  jar=$(if ($script:DefJarName[$r.Bedrock]) { $script:DefJarName[$r.Bedrock] } else { '<none>' })  jdk=$($PlatformDefs[$r.Bedrock].Jdk)" }
    if ($r.Proxy) { Log "    proxy    : $($r.Proxy)  jar=$(if ($script:DefJarName[$r.Proxy]) { $script:DefJarName[$r.Proxy] } else { '<none>' })  jdk=$($PlatformDefs[$r.Proxy].Jdk)" }
  }
  Log "=== PLAN OK ==="
  exit 0
}

# Every server binary the planned rounds touch.
if (-not $SkipBuild) {
  Log ""
  Log "=== building platform artifacts ==="
  if (-not (Invoke-GradleBuild $roundList $requested)) { Log "FATAL: build failed"; exit 2 }

  # Fabric's E2E jar is produced through an init script, so it needs its own
  # invocation with the extra properties the script reads.
  if ($legs.Mod -contains 'fabric') {
    Log "  building fabric E2E mod jar"
    $fabInit = Join-Path $binDir "fabric-fatjar.init.gradle"
    $isWin = $IsWindows -or $env:OS -eq "Windows_NT"
    $gradleW = if ($isWin) { Join-Path $RepoRoot "gradlew.bat" } else { Join-Path $RepoRoot "gradlew" }
    $launcher = if ($Jdk21) { $Jdk21 } else { $Jdk25 }
    $out = Join-Path $JointRoot "gradle-fabric.out.log"
    $err = Join-Path $JointRoot "gradle-fabric.err.log"
    $savedJavaHome = $env:JAVA_HOME
    $savedEap = $ErrorActionPreference
    try {
      $env:JAVA_HOME = $launcher
      $ErrorActionPreference = "Continue"
      Push-Location $RepoRoot
      try {
        & $gradleW ":NovaChat:MOD:fabric:fabricE2eJar" "--init-script" $fabInit "-x" "test" "--console=plain" `
          "-Porg.gradle.java.installations.paths=" "-Dorg.gradle.java.installations.paths=" 1>$out 2>$err
        $fabExit = $LASTEXITCODE
      } finally { Pop-Location }
    } finally {
      $ErrorActionPreference = $savedEap
      $env:JAVA_HOME = $savedJavaHome
    }
    if ($fabExit -ne 0) {
      Log "FATAL: fabric E2E jar build failed (exit=$fabExit)"
      Write-LogTail $err 25 "fabric build stderr"
      exit 2
    }
  }
}

Log ""
Log "=== fetching server binaries ==="
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
# Provision ONLY the binaries the planned rounds actually start -- every round's
# platform, not the whole rotation candidate pool. A rotation list naming pmmp
# and pnx must not make a single-round bukkit+nukkit run download both: that
# turned a 2-minute first run into a 10-minute one and made the harness look
# broken whenever a candidate's CDN was slow.
$fetchTargets = @()
foreach ($r in $roundList) {
  foreach ($pf in @($r.Proxy, $r.Java, $r.Mod, $r.Bedrock)) {
    if (-not $pf) { continue }
    # Fabric has no versions.lock.ps1 entry: its launcher jar and fabric-api are
    # runtime pieces pinned in $FabricRuntime and fetched by
    # Invoke-FetchFabricRuntime below. Its LockName exists for the artifact
    # resolver's bookkeeping only, so feeding it to fetch-server.ps1 aborted the
    # whole run with "No version lock entry for server 'fabric'" before the
    # Fabric leg could ever start (observed: run 20261020-170812 died at
    # provisioning with exit 2).
    if ($pf -eq 'fabric') { continue }
    $lockName = $PlatformDefs[$pf].LockName
    if ($lockName -and ($fetchTargets -notcontains $lockName)) { $fetchTargets += $lockName }
  }
}
foreach ($lockName in $fetchTargets) {
  if (-not (Invoke-FetchServer $lockName)) { Log "FATAL: could not provision '$lockName'"; exit 2 }
}
if ($legs.Mod -contains 'fabric') {
  if (-not (Invoke-FetchFabricRuntime)) { Log "FATAL: could not provision the Fabric runtime"; exit 2 }
}

if (-not (Install-BotDeps)) { exit 2 }

# Super-admin identity: the backend keys super-admins by UUID, and in offline
# mode a player's UUID is the MD5-based offline UUID of the username. Computing
# Java's UUID.nameUUIDFromBytes("OfflinePlayer:<name>") is what lets the
# announce/title scenarios exercise the REAL super-admin path instead of
# reproducing the old "NC-403 by design" result.
$superAdminName = 'E2EJointJava'
$offlineSeed = [System.Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$superAdminName")
$md5 = [System.Security.Cryptography.MD5]::Create()
$hash = $md5.ComputeHash($offlineSeed)
$hash[6] = [byte](($hash[6] -band 0x0F) -bor 0x30)
$hash[8] = [byte](($hash[8] -band 0x3F) -bor 0x80)
$script:SuperAdminUuid = ([System.Guid]::new($hash)).ToString()
Log "Super-admin identity: $superAdminName / $($script:SuperAdminUuid)"

$roundResults = @()
$overallPass = $true
foreach ($r in $roundList) {
  $roundDir = Join-Path (Join-Path $JointRoot $script:RunId) "round-$($r.Index)"
  # Lite and relay are separate rounds: lite starts no backend, its proxy-level
  # assertions come from the authority's own log, and its downstream players are
  # recorded as diagnostics rather than gating evidence.
  $res = if ($ProxyMode -eq 'lite') {
    Invoke-LiteRound -round $r -roundDir $roundDir
  } else {
    Invoke-Round -round $r -roundDir $roundDir
  }
  $roundResults += $res
  if ($res.status -ne 'PASS') { $overallPass = $false }
  Log "=== ROUND $($r.Index) RESULT: $($res.status) ==="
  if ($res.notes) { Log "    notes: $($res.notes)" }
  foreach ($a in $res.assertions) {
    # Diagnostics are reported but never claimed as PASS/FAIL evidence.
    $verdict = if ($a.kind -eq 'diagnostic') { 'INFO' }
               elseif ($a.pass) { 'PASS' } else { 'FAIL' }
    Log ("    {0,-6} {1,-52} {2}" -f $verdict, $a.id, $a.token)
    if ($a.evidence) { Log "           evidence: $($a.evidence)" }
  }
  Start-Sleep -Seconds 2
}

# --- summary -----------------------------------------------------------------
$summaryDir = Join-Path $JointRoot $script:RunId
New-Item -ItemType Directory -Force -Path $summaryDir | Out-Null
$summaryFile = Join-Path $summaryDir "joint-summary.json"
$summary = [PSCustomObject]@{
  runId = $script:RunId
  platforms = $Platforms
  proxyMode = $ProxyMode
  rounds = $roundResults
  overallPass = $overallPass
}
[System.IO.File]::WriteAllText($summaryFile,
  ($summary | ConvertTo-Json -Depth 12),
  (New-Object System.Text.UTF8Encoding $false))

Log ""
Log "=== JOINT E2E SUMMARY ==="
foreach ($res in $roundResults) {
  $passCount = @($res.assertions | Where-Object { $_.pass }).Count
  $total = @($res.assertions).Count
  Log ("  round {0} [{1}] {2}  ({3}/{4} assertions)" -f $res.round, $res.topology, $res.status, $passCount, $total)
  if ($res.notes) { Log "         $($res.notes)" }
}
Log "Summary: $summaryFile"

if (-not $KeepArtifacts -and $overallPass) {
  Log "Run artifacts kept for inspection: $summaryDir"
}

if ($overallPass) {
  Log "=== JOINT E2E PASSED ==="
  exit 0
}
Log "=== JOINT E2E FAILED ==="
exit 1

