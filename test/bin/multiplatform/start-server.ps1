# ============================================================================
# start-server.ps1 -- portable, parameterized server launcher for the Java
# platform E2E matrix. One script serves ALL Java platforms (bukkit/folia/
# velocity/bungee/sponge/nukkit/pnx) by switching on -Platform.
#
# This is a CI-friendly port of the split start-*.ps1 scripts that live under
# .e2e/bin/ (gitignored, machine-specific). It takes -RepoRoot + -RunsDir as
# parameters instead of hardcoding D:\Project\NovaLink, resolves the JDK via
# -JdkHome or $env:JAVA_HOME, fetches the server jar via fetch-server.ps1, lays
# down the run directory, and starts the java process in the background.
#
# It does NOT start the NovaLink backend or the bot. The per-platform
# orchestrator (run-<platform>-e2e.ps1) is responsible for the full
# build->backend->server->bot->assert->teardown flow. This script is the
# shared "start ONE server process" primitive the orchestrators call.
#
# Platforms + their pinned server jars (see test/versions.lock.ps1).
# The JDK column is the RUNTIME requirement, verified against each jar on
# 2026-10-18 by booting it:
#   bukkit   -> purpur    (JDK 21, EULA required)
#   folia    -> folia     (JDK 25, EULA required -- 26.1+ refuses < 25)
#   velocity -> velocity  (JDK 25, no EULA -- 4.x classes are class-file 69.0)
#   bungee   -> waterfall (JDK 21, no EULA, backgrounded; original
#                          .e2e/bin/start-waterfall.ps1 ran in foreground)
#   sponge   -> spongevanilla (JDK 21, EULA required -- SpongeVanilla derives
#                          from the vanilla server, which refuses to boot without
#                          an accepted eula.txt just like Purpur/Folia)
#   nukkit   -> nukkit    (JDK 21, no EULA)
#   pnx      -> powernukkitx (JDK 21, native PNX 3.x)
# The caller (run-multiplatform-e2e.ps1) passes -JdkHome, so these are docs,
# not dispatch: this script just runs whatever java it is handed.
#
# Exit codes:
#   0 = server started, PID written to $PidFile
#   1 = prereq error (jar/java/dir missing)
#
# Usage (called by run-<platform>-e2e.ps1, not directly by users):
#   .\start-server.ps1 -Platform folia -RepoRoot $RepoRoot -RunsDir $RunsDir `
#                       -JdkHome $Jdk21 -McPort 25566
# ============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Platform,
    # Resolved in the body below -- see test/bin/run-pmmp-e2e.ps1 for why a
    # $MyInvocation default is $null under [CmdletBinding()].
    [string]$RepoRoot  = "",
    [string]$RunsDir   = "",     # per-platform run workspace; e.g. <repo>/.e2e-artifacts/runs/folia
    [string]$DistDir   = "",     # server jar cache; defaults to <repo>/.e2e-artifacts/dist
    [string]$JdkHome   = $env:JAVA_HOME,
    [int]$McPort       = 25565,
    [int]$MemoryMb     = 1024,
    [int]$RconPort     = 0,      # 0 = don't enable RCON in server.properties
    [string]$RconPassword = ""
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))) }
if (-not $DistDir) { $DistDir = Join-Path $RepoRoot ".e2e-artifacts/dist" }
$binDir = Join-Path $RepoRoot "test/bin"

function Log([string]$m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }

# --- platform -> (lock name, jar file, jdk, eula, server dir name) -----------
$PlatformMap = @{
    bukkit   = @{ LockName='purpur';    SubDir='paper';   Eula=$true;  ReadyPattern='Done \(' }
    folia    = @{ LockName='folia';    SubDir='folia';   Eula=$true;  ReadyPattern='Done \(' }
    velocity = @{ LockName='velocity'; SubDir='velocity'; Eula=$false; ReadyPattern='Listening|Booting|Started|Done' }
    bungee   = @{ LockName='waterfall'; SubDir='bungee'; Eula=$false; ReadyPattern='Listening' }
    sponge   = @{ LockName='sponge';    SubDir='sponge';  Eula=$true;  ReadyPattern='Sponge.*started|Done \(' }
    # Nukkit/PNX: the ONLY real ready line is lang.ini's startFinished,
    # "Done (<secs>s)! For help, ...". A bare "Done" is not an option: the
    # first-boot language menu prints "idn => Indonesia", and "Indonesia"
    # contains "done", so a loose pattern matches a server that never booted.
    # The "s" + ")" suffix keeps "Done (" from matching anything in the menu.
    nukkit   = @{ LockName='nukkit';   SubDir='nukkit';  Eula=$false; ReadyPattern='Done \([0-9.]+s\)!' }
    pnx      = @{ LockName='powernukkitx'; SubDir='pnx'; Eula=$false; ReadyPattern='Done \([0-9.]+s\)!' }
}

if (-not $PlatformMap.ContainsKey($Platform)) {
    Log "ERROR: unknown platform '$Platform'. Known: $($PlatformMap.Keys -join ', ')"
    exit 1
}
$pm = $PlatformMap[$Platform]

# --- resolve JDK -------------------------------------------------------------
if (-not $JdkHome) {
    Log "ERROR: -JdkHome not set and JAVA_HOME is empty"
    exit 1
}
$isWin = $IsWindows -or $env:OS -eq "Windows_NT"
$javaExe = if ($isWin) { "java.exe" } else { "java" }
$java = Join-Path $JdkHome "bin/$javaExe"
if (-not (Test-Path $java)) {
    Log "ERROR: java not found at $java (JdkHome=$JdkHome)"
    exit 1
}

# --- fetch the server jar via the shared fetch-server.ps1 ---------------------
# Dot-source versions.lock.ps1 to resolve the jar filename + EULA flag.
$lockFile = Join-Path $RepoRoot "test/versions.lock.ps1"
if (-not (Test-Path $lockFile)) { Log "ERROR: versions.lock.ps1 not found: $lockFile"; exit 1 }
. $lockFile
$lock = Get-LockedServer -Name $pm.LockName
if (-not $lock) { Log "ERROR: no lock entry for '$($pm.LockName)' in versions.lock.ps1"; exit 1 }

# Ensure the jar is present (pinned mode by default; -Auto passed by caller if desired).
$jarPath = Join-Path $DistDir $lock.File
if (-not (Test-Path $jarPath)) {
    Log "fetching $($pm.LockName) jar via fetch-server.ps1..."
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $binDir "fetch-server.ps1") -Name $pm.LockName -DistDir $DistDir
    if ($LASTEXITCODE -ne 0) { Log "ERROR: fetch-server failed for $($pm.LockName)"; exit 1 }
}
if (-not (Test-Path $jarPath)) { Log "ERROR: jar still not present after fetch: $jarPath"; exit 1 }

# --- lay down the run directory ----------------------------------------------
if (-not $RunsDir) { $RunsDir = Join-Path $RepoRoot ".e2e-artifacts/runs/$Platform" }
$serverDir = Join-Path $RunsDir $pm.SubDir
New-Item -ItemType Directory -Force -Path $serverDir | Out-Null

# EULA (CI policy: silently accept; see test/README.md)
if ($pm.Eula) {
    "eula=true" | Set-Content -Path (Join-Path $serverDir "eula.txt") -NoNewline
}

# server.properties (platform-aware). Nukkit/PNX use a different property format.
$propsFile = Join-Path $serverDir "server.properties"
if ($Platform -in @('nukkit','pnx')) {
    # Nukkit/PNX server.properties (Bedrock-style keys).
    $rconBlock = if ($RconPort -gt 0) {
        "enable-rcon=on`r`nrcon.port=$RconPort`r`nrcon.password=$RconPassword`r`n"
    } else { "" }
    @"
motd=NovaChat E2E ($Platform)
server-port=$McPort
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
$rconBlock
allow-nether=off
allow-the-end=off
"@ | Set-Content -Path $propsFile -NoNewline

    # nukkit.yml -- Nukkit's REAL config file (server.properties is only the
    # Minecraft-style subset; settings.language lives here). On first boot,
    # when nukkit.yml is absent, Nukkit prints "Welcome! Please choose a
    # language first!" plus the language menu and blocks on
    # NukkitConsole.readLine() waiting for a language code. Under Start-Process
    # in CI stdin is not a TTY (it is the null device), so that read never
    # returns: the server hangs before it opens UDP 19132 and the bot's RakNet
    # ping times out. Seeding nukkit.yml skips the prompt entirely -- the
    # file's existence is the branch condition (Server.<init> checks
    # File.exists() and only prompts when it is false), and settings.language
    # then selects the language without reading stdin. Verified 2026-10-23 on
    # nukkit 1.0-20260809.225630-1242: without this file the boot log is 1046
    # bytes of language menu and no port; with it the server reaches
    # "Done (39.693s)!" and binds 127.0.0.1:19132.
    # Keep the key set minimal: Nukkit falls back to its compiled-in defaults
    # for anything absent (level-settings.default-format defaults to leveldb).
    $nukkitYmlFile = Join-Path $serverDir "nukkit.yml"
    $pnxChunkSettings = if ($Platform -eq 'pnx') { "chunk-settings:`r`n  spawnThreshold: 16" } else { "" }
    @"
settings:
  language: "eng"
  force-language: false
$pnxChunkSettings
"@ | Set-Content -Path $nukkitYmlFile -NoNewline
} else {
    # Java edition (Purpur/Folia/Velocity/Waterfall/Sponge) server.properties.
    $rconBlock = if ($RconPort -gt 0) {
        "enable-rcon=true`r`nrcon.port=$RconPort`r`nrcon.password=$RconPassword`r`n"
    } else { "" }
    @"
server-port=$McPort
server-ip=127.0.0.1
online-mode=false
motd=NovaChat E2E ($Platform)
level-name=world
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
$rconBlock
"@ | Set-Content -Path $propsFile -NoNewline
}

# --- start the server --------------------------------------------------------
$stdout = Join-Path $serverDir "$Platform.stdout.log"
$stderr = Join-Path $serverDir "$Platform.stderr.log"
$pidFile = Join-Path $serverDir "$Platform.pid"
Remove-Item $stdout, $stderr, $pidFile -ErrorAction SilentlyContinue

$jvmArgs = @("-Xms${MemoryMb}M", "-Xmx${MemoryMb}M", "-jar", $jarPath, "nogui")
if ($Platform -eq 'pnx') {
    $jvmArgs = @('--add-opens=java.base/java.lang=ALL-UNNAMED') + $jvmArgs + @('--skip-setup', '--accept-license')
}
$proc = Start-Process -FilePath $java -ArgumentList $jvmArgs -WorkingDirectory $serverDir `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -NoNewWindow
$proc.Id | Set-Content -Encoding ascii $pidFile
Log "$Platform server started (pid=$($proc.Id), port=$McPort, jar=$($lock.File))"
Log "STDOUT: $stdout"
Log "PID file: $pidFile"
Log "READY_PATTERN: $($pm.ReadyPattern)"
Write-Output ("${Platform}_PID=" + $proc.Id)
