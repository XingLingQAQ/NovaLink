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
#   3 = SKIPPED (a required binary is unavailable, so NO test ran)
#
# Exit 3 is deliberately distinct from 0: a platform that ran zero assertions
# must never be reported as PASS. run-e2e-orchestrator.ps1 maps 3 to SKIPPED.
# ============================================================================
[CmdletBinding()]
param(
    # Resolved in the body below -- see run-pmmp-e2e.ps1 for why a
    # $MyInvocation default is $null under [CmdletBinding()].
    [string]$RepoRoot      = "",
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
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)) }
if (-not $DistDir) { $DistDir  = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $RunsDir) { $RunsDir  = Join-Path $RepoRoot ".e2e-artifacts/runs/levilamina" }
$binDir   = Join-Path $RepoRoot "test/bin"
$botDir   = Join-Path $RepoRoot "test/bot"
$confDir  = Join-Path $RepoRoot "test/conf"
$levilaminaSrc = Join-Path $RepoRoot "NovaChat/Bedrock/levilamina"
# The bot's protocol version has to agree with the BDS build that fetch-server.ps1
# downloads, so it is read from versions.lock.ps1 rather than repeated as a
# literal here. The literal used to be "1.26.30"; the pinned BDS is 1.26.52,
# which speaks protocol 2193 and kicks a 1.26.30 client (protocol 1001) as
# disconnectionScreen.outdatedClient. See the resolution below the dot-source.
. (Join-Path (Split-Path -Parent $binDir) 'versions.lock.ps1')
. (Join-Path $binDir 'e2e-assertions.ps1')

function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg)
}

# The version string handed to bedrock-protocol. It comes from the bds lock
# entry's BotMcVersion, NOT from its MC field: 1.26.52 is a hotfix that did not
# bump the wire protocol, and minecraft-data 3.117.0 has no "1.26.52" entry at
# all, so passing MC would throw "Unsupported version" inside the bot. BotMcVersion
# is "1.26.51", the newest string the table knows, and it resolves to protocol
# 2193 -- the number BDS 1.26.52 advertises and accepts. The fallback only fires
# against a lock revision older than the BotMcVersion field.
$bdsLock = Get-LockedServer -Name 'bds'
$botMcVersion = if ($bdsLock -and $bdsLock.BotMcVersion) {
    $bdsLock.BotMcVersion
} else {
    Log "WARN: no BotMcVersion on the 'bds' lock entry; falling back to 1.26.51"
    '1.26.51'
}

# --- failure diagnostics -----------------------------------------------------
# The backend, BDS/LeviLamina and bot all write to files under $RunsDir, and a
# failing run used to report only a one-line reason while the actual cause sat
# unread on disk. Print the tail of each log, labelled with its path, so the CI
# job log alone is enough to diagnose a failure.
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
# A fat jar is only usable when it is NOT STALE. Measured on this host
# (2026-10-23): core-1.0.0-SNAPSHOT-all.jar was dated 2026-10-17 18:51 while 42
# .java files under StarLink/core/src were newer. That jar is a pre-refactor
# build (NovaLinkMain.class 67,124 bytes, zero "daemon" strings, no NovaLinkCore
# class), so launching from it passes --daemon to a jar that ignores the flag and
# the backend dies on stdin EOF -- making a good fix look broken. CI never sees
# this (the workflow runs only `:StarLink:core:jar`, never shadowJar, so no
# *-all.jar exists there and CI always takes the gradle branch); it is a
# local-only trap, and that is exactly why local and CI must not diverge.
# Compare LastWriteTimeUtc against the newest source file and refuse an older
# jar; "no source files found" cannot prove freshness, so it refuses too.
function Get-FreshFatJar([string]$RepoRoot) {
    # Same selection order as before (shadow output first, then a plain -fat
    # jar), but the chosen jar is then VALIDATED as a whole. Deliberately not a
    # per-glob "reject then try the next glob" loop: that would quietly swap a
    # stale shadow jar for some other stale artifact instead of falling through
    # to the classpath, which is the branch that is always correct.
    $jar = Get-ChildItem (Join-Path $RepoRoot "StarLink/core/build/libs/*-all.jar") -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $jar) {
        $jar = Get-ChildItem (Join-Path $RepoRoot "StarLink/core/build/libs/*-fat.jar") -ErrorAction SilentlyContinue |
            Select-Object -First 1
    }
    if (-not $jar) { return $null }

    $srcDir = Join-Path $RepoRoot "StarLink/core/src"
    $newestSource = Get-ChildItem -LiteralPath $srcDir -Recurse -File -Filter *.java -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if (-not $newestSource) {
        Log "  rejecting fat jar $($jar.Name): no .java under $srcDir, so freshness cannot be proven -- using classpath"
        return $null
    }
    if ($jar.LastWriteTimeUtc -lt $newestSource.LastWriteTimeUtc) {
        Log ("  rejecting stale fat jar {0} (jar {1:yyyy-MM-dd HH:mm} UTC, newest source {2:yyyy-MM-dd HH:mm} UTC) -- using classpath" -f `
            $jar.Name, $jar.LastWriteTimeUtc, $newestSource.LastWriteTimeUtc)
        return $null
    }
    return $jar
}
$fatJar = Get-FreshFatJar $RepoRoot
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
# Pass -Auto to match the orchestrator's declared ServerScripts for levilamina
# (run-e2e-orchestrator.ps1 levilamina.PlatformDef). With -Auto, fetch-server.ps1
# queries the Minecraft services links API for the current BDS zip (see its
# Resolve-BdsLatest) and downloads + SHA-256-verifies it. Only a genuine API
# failure returns @{ Skip = $true } and exits 0 WITHOUT downloading; the pinned
# bds URL in versions.lock.ps1 is a real artifact, not a placeholder, so even a
# non-Auto run downloads that instead. Any other fetch-server failure exits
# non-zero and this script turns it into exit 2 (a real prereq error) -- not a
# SKIP, because a failed download is a bug, not an unavailable environment.
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $binDir "fetch-server.ps1") -Name "bds" -Auto -DistDir $DistDir
if ($LASTEXITCODE -ne 0) { Log "ERROR: fetch-server failed for bds"; exit 2 }
# The BDS zip lands in $DistDir (freshly downloaded by fetch-server, a locally-
# cached zip per versions.lock.ps1 bds notes, or a cache hit from a prior run).
# Unpack it into the run directory. When fetch-server exits 0 but no BDS zip is
# present, Resolve-BdsLatest reported a genuine skip and logged its reason on a
# [fetch] line above; record that as the machine-readable reason and exit 3.
# Exiting 0 here made CI report "levilamina PASS" for a platform that never
# started a server or ran the bot (run 39056124289).
$bdsZip = Get-ChildItem (Join-Path $DistDir "bedrock-server*.zip") -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $bdsZip) {
    Log "SKIP: BDS server binary unavailable - levilamina E2E not run (fetch-server exited 0 without downloading; see the [fetch] line above for the reason, e.g. the Minecraft services links API was unreachable)"
    Set-Content -Path (Join-Path $RunsDir "skip-reason.txt") -Value "BDS unavailable"
    exit 3
}
$serverDir = Join-Path $RunsDir "bds"
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
Expand-Archive -Path $bdsZip.FullName -DestinationPath $serverDir -Force
Log "  BDS unpacked: $serverDir"

# Bedrock server.properties. This file is written WHOLESALE, replacing the one
# in the BDS zip, so every key the run depends on must appear here.
#
# transport=nethernet is what the pristine zip ships and what the 1.26.52 how-to
# documents as the default. Omitting the key is NOT fatal on 1.26.52 -- the
# server falls back to RakNet and an offline bot still logs in (verified) -- but
# it prints a "TRANSPORT TYPE ERROR ... NetherNet is the only supported transport
# type" banner, and NetherNet is the direction Mojang is moving. Keep the key so
# the run exercises the documented path and stays correct if a later BDS drops
# the RakNet fallback.
#
# online-mode=false is REQUIRED, and it is not the same thing as xbox-auth=off:
# the offline bot (bedrock-protocol offline:true) is kicked with
# reason=not_authenticated under the zip's default online-mode=true -- verified
# against 1.26.52 in both states. xbox-auth is not a documented property in
# 1.26.52 at all; it is kept only because PocketMine (run-pmmp-e2e.ps1) reads it
# and this block should stay usable on older BDS builds.
$serverProps = Join-Path $serverDir "server.properties"
@"
server-name=NovaChat-LeviLamina-E2E
server-port=$McPort
server-ip=127.0.0.1
transport=nethernet
view-distance=4
white-list=off
online-mode=false
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
# --daemon is REQUIRED here. Start-Process gives the JVM a redirected stdin
# handle inherited from this PowerShell process; NovaLinkMain's default mode
# runs the JLine console loop on the main thread, which reads EOF from that
# handle immediately, prints "EOF received, shutting down..." and exits about a
# second after "started successfully" -- long before BDS and the bot can
# connect. The plugin then only ever logs "Failed to connect ... Connection
# timeout" and never authenticates, so backendOk can never go true. In daemon
# mode NovaLinkMain never touches stdin and blocks on a latch released by the
# JVM shutdown hook, so the process stays up until Teardown-All stops it
# (Stop-Process -> terminate -> shutdown hook releases the latch). Flags are
# order-independent in NovaLinkMain::hasFlag, so the positional yml path and
# --daemon can sit in either order.
if ($fatJar) {
    $novaArgs = @("-Xmx512m", "-jar", $fatJar.FullName, $novaYml, "--daemon")
} else {
    $cp = (Get-Content $classpathFile -Raw).Trim()
    $novaArgs = @("-Xmx512m", "-cp", $cp, "com.nova.link.NovaLinkMain", $novaYml, "--daemon")
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
        # The backend logs fatal startup errors to stdout (its logger writes
        # there), so dumping only stderr showed just the JAVA_TOOL_OPTIONS
        # banner and hid the actual cause. Show both.
        Log "ERROR: backend exited early; stdout (tail):"
        Get-Content $novaStdout -ErrorAction SilentlyContinue | Select-Object -Last 20 | ForEach-Object { Log "  $_" }
        Log "ERROR: backend exited early; stderr:"
        Get-Content $novaStderr -ErrorAction SilentlyContinue | Select-Object -First 10 | ForEach-Object { Log "  $_" }
        Teardown-All; Show-FailureLogs "backend exited early"; exit 1
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
        Teardown-All; Show-FailureLogs "BDS/LeviLamina exited early"; exit 1
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
    Teardown-All; Show-FailureLogs "BDS/LeviLamina did not reach ready within ${ServerReadySec}s"; exit 1
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
# Test-BotDepsCurrent (e2e-assertions.ps1), not a Test-Path on one package dir.
# The old existence-only guard was satisfied by ANY installed copy: a tree left
# over from an earlier checkout (bedrock-protocol 3.58.1 against a package.json
# asking for 3.60.1) skipped the install and then failed at runtime, because
# 3.58.x has no NetherNet transport and cannot reach a 1.26.52 BDS. A stale tree
# must therefore trigger `npm ci`, not suppress it.
if (-not (Test-BotDepsCurrent $botDir)) {
    Push-Location $botDir
    try { npm ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { Log "ERROR: npm ci failed"; Teardown-All; Show-FailureLogs "npm ci failed"; exit 2 } }
    finally { Pop-Location }
}
$resultsFile = Join-Path $RunsDir "bot-results.json"
$env:SERVER_HOST = "127.0.0.1"
$env:SERVER_PORT = "$McPort"
# BDS caps usernames at 16 characters. "E2E_Bot_LeviLamina" is 18 and is kicked
# at login with reason=invalid_name -- verified against a real BDS 1.26.52, where
# 18 chars fails and this 15-char form spawns. Kept in step with the chatOk
# regex in step 7.
$env:BOT_NAME = "E2E_Bot_LeviLam"
$env:PLATFORM = "levilamina"
$env:MC_VERSION = $botMcVersion
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
# 7. Assert results.json.
# ---------------------------------------------------------------------------
Log "step 7: asserting L1 results"
$pass = $false
if (Test-Path $resultsFile) {
    # ReadAllText + UTF8, not Get-Content -Raw: on Windows PowerShell 5.1 the
    # latter decodes with the ANSI code page, and the bot writes UTF-8. The
    # channel-format envelope carries non-ASCII text ("[全服] ..."), which
    # makes ConvertFrom-Json throw outright -- verified against a recorded
    # bedrock payload, where the ANSI read fails and the UTF-8 read parses.
    $resultsJson = [System.IO.File]::ReadAllText($resultsFile, [System.Text.Encoding]::UTF8)
    $results = $resultsJson | ConvertFrom-Json
    # Get-BotReceivedRows drops the bot's own outgoing lines. sendCommand() in
    # run-e2e-bedrock.js records every command into the SAME received[] list
    # with kind='sent', so the old $msgs contained "/nc join global" (matched
    # the `join` regex) and "hello from e2e bot" (matched the chat regex).
    # Both assertions were satisfied by the bot's own input and could not fail
    # on any plugin state (see e2e-assertions.ps1).
    $rows = Get-BotReceivedRows $results
    $msgs = Get-BotReceivedTexts $rows
    # joinOk: CommandHandler::handleJoin prints "已加入频道 / Joined channel"
    # from the plugin, but it does so on the packet-send path regardless of a
    # backend reply, so it is not a backend proof. The bare `join` regex it
    # replaces also matched the vanilla "E2E_Bot_X joined the game" banner.
    $joinOk   = [bool]($msgs -match 'Joined channel|joined channel|已加入频道')
    # chatOk: the phrase must come back inside NovaChat's channel-format
    # envelope ("[<channel>] <player>: <text>"), as a plugin-emitted raw text
    # row. The envelope is the load-bearing part: vanilla's own re-broadcast of
    # the sender's line also reaches the bot as a text row, so matching the
    # phrase alone still passed on a bare server. Colour codes are stripped
    # first because bedrock-protocol records them ("<sect>c[全服] ...").
    #
    # Caveat: no recorded LeviLamina payload was inspected for this change the
    # way endstone's was (.e2e-artifacts/joint/20261021-111749). LeviLamina uses
    # the same format templates and the same bot, so the envelope shape is
    # inferred from that evidence rather than observed on this platform.
    $chatOk   = (@(Select-BotRowsByPlainText $rows '\[[^\]]+\]\s*E2E_Bot_LeviLam\s*:\s*.*hello from e2e bot' |
        Where-Object { $_.kind -eq 'text:raw' })).Count -ge 1
    # pluginOk: a line only NovaChat can emit reached the bot.
    $pluginOk = Test-PluginEvidence $rows
    # backendOk: the /nc who prompt, which handleWho prints only after
    # NetworkClient::isConnected() passes (chat.who.unavailable otherwise).
    $backendOk = Test-BackendEvidence $rows
    # Fatal check reads the SERVER's log. Plugin errors are logged to the BDS
    # console, never to the bot's chat transcript, so the old $msgs scan looked
    # in the wrong place and was a constant true.
    $fatal = Test-ServerLogFatal @($bdsStdout, $bdsStderr)
    $noFatal = ($null -eq $fatal)
    Log ("  join={0} chat={1} plugin={2} backend={3} noFatal={4}" -f $joinOk, $chatOk, $pluginOk, $backendOk, $noFatal)
    if (-not $noFatal) { Log ("  FATAL in server log: {0}:{1}: {2}" -f $fatal.Path, $fatal.LineNumber, $fatal.Line.Trim()) }
    $pass = $joinOk -and $chatOk -and $pluginOk -and $backendOk -and $noFatal
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
    Show-FailureLogs "L1 assertion failed"
    exit 1
}
