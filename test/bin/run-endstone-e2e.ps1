# ============================================================================
# run-endstone-e2e.ps1 -- portable Endstone/BDS L1 E2E orchestrator.
#
# Runs the full Layer-1 flow for the endstone platform:
#   1. Build the NovaLink backend (StarLink:core classpath via init-script, or
#      a fat jar if shadow landed it).
#   2. Install the NovaChat endstone plugin (python package, editable) into a
#      venv that Endstone's BDS host will import.
#   3. Fetch + unpack the Bedrock Dedicated Server (BDS) via fetch-server.ps1
#      -Name bds, paired with the locked Endstone package.
#   4. Generate backend config + start the NovaLink backend.
#   5. Start BDS+Endstone, wait for the "Done" log line + plugin enable.
#   6. Run the bedrock-protocol bot (test/bot/run-e2e-bedrock.js).
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
    [string]$RunsDir       = "",     # per-run workspace; defaults to <repo>/.e2e-artifacts/runs/endstone
    [int]$McPort           = 19132,  # Bedrock default UDP port
    [int]$NovaPort         = 27906,
    [int]$NovaWsPort       = 34574,
    [int]$ServerReadySec   = 240,
    [int]$BotTimeoutSec    = 360
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)) }
if (-not $DistDir) { $DistDir  = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $RunsDir) { $RunsDir  = Join-Path $RepoRoot ".e2e-artifacts/runs/endstone" }
$binDir   = Join-Path $RepoRoot "test/bin"
$botDir   = Join-Path $RepoRoot "test/bot"
$confDir  = Join-Path $RepoRoot "test/conf"
$endstoneSrc = Join-Path $RepoRoot "NovaChat/Bedrock/endstone"
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
if (-not $bdsLock -or -not $bdsLock.EndstoneVersion) {
    Log "ERROR: the bds lock must include its compatible EndstoneVersion"
    exit 2
}
$endstoneVersion = $bdsLock.EndstoneVersion
$botMcVersion = if ($bdsLock -and $bdsLock.BotMcVersion) {
    $bdsLock.BotMcVersion
} else {
    Log "WARN: no BotMcVersion on the 'bds' lock entry; falling back to 1.26.51"
    '1.26.51'
}

# --- failure diagnostics -----------------------------------------------------
# The backend, BDS/Endstone and bot all write to files under $RunsDir, and a
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
#
# pip writes progress and dependency warnings to stderr. With
# $ErrorActionPreference = "Stop" (set at the top of this script), a native
# command's stderr becomes a TERMINATING error, so these `2>$buildErr`
# redirects aborted the script before the install even completed. Drop to
# Continue around the pip calls only -- the exit code is still checked
# explicitly on the next line, so a real failure is not swallowed.
$savedEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& $venvPy -m pip install --upgrade pip 1>$buildOut 2>$buildErr
& $venvPy -m pip install "endstone==$endstoneVersion" 1>>$buildOut 2>>$buildErr
$pipExit = $LASTEXITCODE
if ($pipExit -ne 0) { $ErrorActionPreference = $savedEap; Log "ERROR: pip install endstone failed (see $buildErr)"; exit 2 }
& $venvPy -m pip install -e $endstoneSrc 1>>$buildOut 2>>$buildErr
$pipExit = $LASTEXITCODE
$ErrorActionPreference = $savedEap
if ($pipExit -ne 0) { Log "ERROR: pip install novachat_endstone failed (see $buildErr)"; exit 2 }
Log "  endstone venv: $endstoneVenv"
$installedMcVersion = (& $venvPy -c 'import endstone; print(endstone.__minecraft_version__)').Trim()
$expectedMcVersion = $bdsLock.MC -replace '^1\.', ''
if ($LASTEXITCODE -ne 0 -or $installedMcVersion -ne $expectedMcVersion) {
    Log "ERROR: Endstone $endstoneVersion requires BDS $installedMcVersion, but the lock specifies $expectedMcVersion"
    exit 2
}

# Locate the endstone launcher entry point. Endstone ships a console script
# `endstone` in the venv's Scripts/ that launches BDS with the plugin host.
$endstoneExe = Join-Path $endstoneVenv "Scripts/endstone.exe"
if (-not (Test-Path $endstoneExe)) {
    # Fallback: invoke the python module. An array, so each argument is explicit
    # and Start-Process joins them into `-m endstone`. Not a bug fix -- the old
    # single string "-m endstone" also reached python as two argv tokens (5.1
    # passes the string verbatim to the command line and python's CRT splits it);
    # the array just states the intent without relying on that split.
    $endstoneExe = $venvPy
    $endstoneModule = @("-m", "endstone")
} else {
    $endstoneModule = $null
}
Log "  endstone launcher: $endstoneExe"

# ---------------------------------------------------------------------------
# 3. Fetch BDS + set up the Endstone run directory.
# ---------------------------------------------------------------------------
Log "step 3: fetching Bedrock Dedicated Server (BDS)"
# Endstone and BDS must stay on the same locked Minecraft release.
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $binDir "fetch-server.ps1") -Name "bds" -DistDir $DistDir
if ($LASTEXITCODE -ne 0) { Log "ERROR: fetch-server failed for bds"; exit 2 }
$bdsZip = Get-Item -LiteralPath (Join-Path $DistDir $bdsLock.File) -ErrorAction SilentlyContinue
if (-not $bdsZip) {
    Log "SKIP: the pinned BDS archive $($bdsLock.File) is unavailable; endstone E2E did not run"
    Set-Content -Path (Join-Path $RunsDir "skip-reason.txt") -Value "BDS unavailable"
    exit 3
}
$serverDir = Join-Path $RunsDir "bds"
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
Expand-Archive -Path $bdsZip.FullName -DestinationPath $serverDir -Force
Log "  BDS unpacked: $serverDir"

# Tell the endstone CLI which BDS version this tree already contains.
#
# The BDS zip ships NO version.txt -- endstone writes that file itself, but only
# on the code path that DOWNLOADS the server. We unpack the zip ourselves (to
# use the pinned, SHA-256-verified artifact from versions.lock.ps1), so the file
# is absent. Bootstrap._update() then reads it, gets the 0.0.0 fallback, decides
# the tree is "an older version", and asks via click.confirm() whether to
# update. Start-Process gives the child an immediately-EOF stdin, click.confirm
# raises Abort().
#
# This covers the SECOND prompt, not the one in the CI failure (run 40077636813).
# That Abort came from _install()'s "Bedrock Dedicated Server (v26.52) is not
# found in ...\bds\bedrock_server" branch: the launcher was started with no
# server folder, so it looked in a "bedrock_server" SUBDIR of the BDS tree
# instead of the tree itself. _update() was never reached. See the -s argument
# at the Start-Process below. Once -s points at the tree, _install() finds
# bedrock_server.exe and calls _update(), which is the path this file silences.
#
# Verified locally against endstone 0.11.9 (the installed source) with an EOF
# stdin: _install() -> click.confirm -> Abort; _update() with a version.txt
# equal to __minecraft_version__ returns without prompting.
#
# So write the version endstone expects -- str(packaging.version.Version(
# endstone.__minecraft_version__)), which round-trips "26.52" unchanged. Do NOT
# "fix" this by passing -y: that makes endstone re-download BDS itself and
# throws away the locked artifact. The value must match __minecraft_version__
# (the bds lock entry's MC, minus its leading "1.") or _update() prompts again.
$endstoneMcVersion = ($bdsLock.MC -replace '^1\.', '')
# -Encoding ASCII matters: endstone reads this with encoding="utf-8" and feeds it
# to packaging.version.Version. A BOM would arrive as '﻿26.52' and raise
# InvalidVersion. PS 5.1's default "Default" encoding happens to be BOM-less for
# ASCII text, but stating ASCII makes that independent of the host's ANSI codepage.
Set-Content -Path (Join-Path $serverDir "version.txt") -Value $endstoneMcVersion -NoNewline -Encoding ASCII
Log "  BDS version.txt: $endstoneMcVersion (skips the endstone update prompt)"

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
server-name=NovaChat-Endstone-E2E
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
if (-not $ready) { Log "WARN: backend readiness not confirmed in logs, proceeding (Endstone will retry connect)" }

# ---------------------------------------------------------------------------
# 5. Start BDS+Endstone + wait for "Done" / plugin enable.
# ---------------------------------------------------------------------------
Log "step 5: starting BDS+Endstone on port $McPort"
$bdsStdout = Join-Path $serverDir "bds.stdout.log"
$bdsStderr = Join-Path $serverDir "bds.stderr.log"
$bdsPidFile = Join-Path $serverDir "bds.pid"
# Start-Process -ArgumentList REFUSES both an empty collection and an explicit
# $null on Windows PowerShell 5.1: "Cannot validate argument on parameter
# 'ArgumentList'. The argument is null or empty". So each branch below MUST pass
# a non-empty argument array -- never @() and never $null.
#
# The launcher needs an explicit server folder. endstone's -s/--server-folder
# defaults to the literal "bedrock_server", a RELATIVE path that Bootstrap
# resolves as Path("bedrock_server").absolute() -- i.e. against the child's cwd,
# not against the BDS tree. With -WorkingDirectory $serverDir and no argument,
# it therefore looked for $serverDir\bedrock_server\bedrock_server.exe, which
# does not exist (the zip unpacks bedrock_server.exe directly into $serverDir).
# _install() then hit its "Bedrock Dedicated Server (v26.52) is not found in
# ...\bds\bedrock_server" click.confirm() and Aborted on the EOF stdin that
# Start-Process hands the child. That is the step 5 failure in run 40077636813;
# bds.stdout.log held the prompt and no server ever started.
#
# Pass the ABSOLUTE $serverDir rather than "." so the intent does not depend on
# cwd semantics. Both halves matter: _install() finds bedrock_server.exe there
# and calls _update(), and _prepare() creates endstone.toml + plugins/ there and
# _run() launches BDS with cwd=$serverDir -- so server.properties is picked up
# from the same tree we unpacked into.
#
# The argument must come AFTER the module flags: `python -m endstone -s <dir>`,
# not `python -s -m endstone ...` (-s would be python's own flag, and the -m
# target must precede the module's argv).
#
# The launcher must be started with its STDIN PIPE HELD OPEN. That is the real
# fix for the server stopping itself right after "Server started.":
#
#   [04:29:08 INFO]: Server started.
#   [04:29:08 INFO]: [Novachat] Enabling novachat v1.0.0
#   [04:29:08 INFO]: Server stop requested.
#
# endstone's console reader treats stdin EOF as a shutdown request
# (src/endstone/runtime/bedrock_hooks/console_input_reader.cpp):
#
#   auto line = EndstoneConsole::getInstance().readLine("> ");
#   if (line.has_value()) { ... }
#   else {
#       // Ctrl + C, EOF
#       console_input_.try_enqueue("stop");
#       break;
#   }
#
# and console.cpp reports EOF in BOTH branches -- replxx's input() returns nullptr
# when ENDSTONE_USE_INTERACTIVE_CONSOLE is truthy, and std::getline fails
# cin.good() otherwise. So the replxx console is NOT the cause and
# --no-interactive does NOT avoid it; EndstoneServer::setLevel() calls
# startEndstone() unconditionally. This is why run 40161731209 still stopped with
# --no-interactive in place, and why run 40106019993 stopped even though the
# plugin was rejected at load time and never ran: the stop never depended on our
# plugin.
#
# Start-Process cannot express "stdin open but unused". It either lets the child
# inherit this script's stdin -- already at EOF in CI -- or redirects from a FILE,
# which reads EOF at its end. Either way the child reads EOF within milliseconds
# and stops itself. hold-stdin-run.ps1 starts the child via .NET's Process with
# RedirectStandardInput = $true and never writes to or closes that pipe, so
# readLine() blocks and no "stop" is ever enqueued.
#
# Verified locally on endstone 0.11.14 + BDS 1.26.52.3 with NO plugin installed,
# so the plugin cannot be a confounder: with stdin at EOF the server logged
# "Server stop requested." in the same second as "Server started.", and with the
# pipe held open it stayed up. Plain BDS on the same tree never stops for any
# stdin shape, which is what isolates endstone's console reader as the cause.
$endstoneArgs = if ($endstoneModule) { $endstoneModule + @("-s", $serverDir, "--no-interactive") } else { @("-s", $serverDir, "--no-interactive") }
# The wrapper takes everything from the environment: Start-Process joins
# -ArgumentList with spaces and quotes nothing, so a $serverDir containing a
# space (or the child's own argv) would split if it were passed as a parameter.
# Verified that an argument line containing spaces and embedded quotes arrives
# intact through both layers this way.
$env:NOVACHAT_HOLD_STDIN_EXE = $endstoneExe
$env:NOVACHAT_HOLD_STDIN_ARGS = ($endstoneArgs | ForEach-Object {
    if ($_ -match '[\s"]') { '"' + ($_ -replace '(\\*)"', '$1$1\"') + '"' } else { $_ }
}) -join ' '
$env:NOVACHAT_HOLD_STDIN_CWD = $serverDir
$env:NOVACHAT_HOLD_STDIN_PIDFILE = $bdsPidFile
$endstoneProc = Start-Process -FilePath "powershell" -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $binDir "hold-stdin-run.ps1")
) -WorkingDirectory $serverDir -RedirectStandardOutput $bdsStdout -RedirectStandardError $bdsStderr -PassThru -NoNewWindow
Track-Pid $endstoneProc.Id
# bds.pid must hold the SERVER's pid, not this wrapper's, or teardown would kill
# the wrapper and orphan the server. The wrapper writes the real pid itself; fall
# back to the wrapper's own pid only if it has not appeared yet.
$serverPid = $null
for ($i = 0; $i -lt 20; $i++) {
    if (Test-Path $bdsPidFile) {
        $candidate = (Get-Content $bdsPidFile -Raw -ErrorAction SilentlyContinue)
        if ($candidate -and $candidate.Trim() -match '^\d+$') { $serverPid = [int]$candidate.Trim(); break }
    }
    Start-Sleep -Milliseconds 250
}
if (-not $serverPid) {
    Log "WARN: the wrapper did not publish the server pid; tracking the wrapper instead"
    $serverPid = $endstoneProc.Id
    $serverPid | Set-Content $bdsPidFile
}
Track-Pid $serverPid
Log "  endstone pid=$serverPid (wrapper pid=$($endstoneProc.Id))"

# BDS readiness: "Done" or "Server started" + plugin enable marker.
$serverReady = $false
for ($i = 0; $i -lt ($ServerReadySec / 5); $i++) {
    Start-Sleep -Seconds 5
    if ($endstoneProc.HasExited) {
        Log "ERROR: BDS/Endstone exited early; stderr:"; Get-Content $bdsStderr -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Log "  $_" }
        Teardown-All; Show-FailureLogs "BDS/Endstone exited early"; exit 1
    }
    if (Test-Path $bdsStdout) {
        $logContent = Get-Content $bdsStdout -Raw -ErrorAction SilentlyContinue
        # Two server families, two startup lines:
        #   BDS/Bedrock:  "Server started in (1.8s)! For help, type ..."
        #   Java family:  "Done (12.345s)! For help, type ..."
        # The BDS line has NO period after "started" -- "Server started." is not
        # what 1.26.x prints, so the old 'Server started\.|Done \(' alternative
        # could never match a real BDS log and the loop always burned the full
        # ServerReadySec and then failed. Match the bare phrase, not "started.".
        if ($logContent -match 'Server started|Done \(') { $serverReady = $true; break }
    }
}
if (-not $serverReady) {
    Log "ERROR: BDS/Endstone did not reach ready within ${ServerReadySec}s"
    Get-Content $bdsStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    Teardown-All; Show-FailureLogs "BDS/Endstone did not reach ready within ${ServerReadySec}s"; exit 1
}
Log "  BDS/Endstone ready"

# Confirm plugin enable + backend auth (best-effort, not a hard gate -- the bot
# assertions are the real gate).
$bdsConsole = @($bdsStdout, $bdsStderr) | Where-Object { Test-Path $_ }
if ($bdsConsole.Count -gt 0) {
    # The plugin is Python and logs to stderr, so $bdsStdout alone never matched.
    $logContent = ($bdsConsole | ForEach-Object { Get-Content $_ -Raw -ErrorAction SilentlyContinue }) -join [Environment]::NewLine
    if ($logContent -match 'NovaChat plugin enabled successfully!') { Log "  plugin enabled (confirmed)" }
    if ($logContent -match 'Successfully connected and authenticated!|Connected to NovaLink backend') { Log "  backend auth (confirmed)" }
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
$botRunId = Initialize-BotRun $resultsFile
$env:SERVER_HOST = "127.0.0.1"
$env:SERVER_PORT = "$McPort"
$env:BOT_NAME = "E2E_Bot_Endstone"
$env:PLATFORM = "endstone"
$env:MC_VERSION = $botMcVersion
$env:BACKEND_CHAT_PHRASE = "hello from e2e bot"
$env:RESULTS_FILE = $resultsFile
$env:TIMEOUT_MS = "$($BotTimeoutSec * 1000)"
$botStdout = Join-Path $RunsDir "bot.stdout.log"
$botStderr = Join-Path $RunsDir "bot.stderr.log"
$botProc = Start-Process -FilePath "node" -ArgumentList @("run-e2e-bedrock.js") -WorkingDirectory $botDir `
    -RedirectStandardOutput $botStdout -RedirectStandardError $botStderr -PassThru -NoNewWindow
# Keep the native process handle; PowerShell 5.1 otherwise loses ExitCode.
$null = $botProc.Handle
Track-Pid $botProc.Id
$botTimedOut = -not $botProc.WaitForExit($BotTimeoutSec * 1000)
if ($botTimedOut) { Stop-Pid $botProc.Id }
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
    # run-bukkit-e2e.ps1 already reads this way for the same reason.
    $resultsJson = [System.IO.File]::ReadAllText($resultsFile, [System.Text.Encoding]::UTF8)
    $results = $resultsJson | ConvertFrom-Json
    $runFailure = Get-BotRunFailure -Results $results -ExitCode $botProc.ExitCode `
        -TimedOut $botTimedOut -ExpectedRunId $botRunId -ServerLogPaths @($bdsStdout,$bdsStderr,$novaStdout,$novaStderr)
    if ($runFailure) { Log "  RUN FAILED: $runFailure" }
    # Get-BotReceivedRows drops the bot's own outgoing lines. sendCommand() in
    # run-e2e-bedrock.js records every command into the SAME received[] list
    # with kind='sent', so the old $msgs contained "/nc join global" (matched
    # the `join` regex) and "hello from e2e bot" (matched the chat regex).
    # Both assertions were satisfied by the bot's own input and could not fail
    # on any plugin state (see e2e-assertions.ps1).
    $rows = Get-BotReceivedRows $results
    $msgs = Get-BotReceivedTexts $rows
    # joinOk: Endstone's _cmd_join prints "正在加入频道 / Joining channel"
    # immediately. With a backend it then sends the JOIN and prints nothing
    # else; without one it takes the local-fallback branch and prints
    # "已加入频道 / Joined channel". So the joined line is NOT a backend proof
    # here, and the bare `join` regex matched the vanilla
    # "E2E_Bot_X joined the game" banner anyway. What is required is NovaChat's
    # own text, in either locale.
    $joinOk   = [bool]($msgs -match 'Joining channel|joined channel|正在加入频道|已加入频道')
    # chatOk: the phrase must come back inside NovaChat's channel-format
    # envelope ("[<channel>] <player>: <text>"), as a plugin-emitted raw text
    # row rather than vanilla's own re-broadcast of the sender's line.
    #
    # The envelope is the load-bearing part. On this platform the bot's own
    # chat line comes back as a 'text:translation' row (%multiplayer.player...
    # style) while plugin-routed traffic lands as 'text:raw' with the configured
    # format applied -- both verified against the recorded payload in
    # .e2e-artifacts/joint/20261021-111749. Matching the phrase alone would
    # still pass on a bare server. Colour codes are stripped first because
    # bedrock-protocol records them ("<sect>c[全服] <sect>7Name<sect>f: ...");
    # that is what Select-BotRowsByPlainText does.
    $chatOk   = (@(Select-BotRowsByPlainText $rows '\[[^\]]+\]\s*E2E_Bot_Endstone\s*:\s*.*hello from e2e bot' |
        Where-Object { $_.kind -eq 'text:raw' })).Count -ge 1
    # The bot now sends this phrase in REPLACE mode (one toggle, where it used
    # to send two). Endstone forwards in HYBRID as well (only
    # 'replace_vanilla: true' cancels the vanilla event), so this is not what
    # makes the row appear here -- but it removes the mode dependence and
    # matches PMMP, which does NOT forward in HYBRID.
    # pluginOk: a line only NovaChat can emit reached the bot.
    $pluginOk = Test-PluginEvidence $rows
    # backendOk: the /nc who prompt, printed only when the network client is
    # connected (chat.network.not_connected_retry is the alternative).
    $backendOk = Test-BackendEvidence $rows
    # Fatal check reads the SERVER's log. Plugin errors are logged to the
    # Endstone/BDS console, never to the bot's chat transcript, so the old
    # $msgs scan looked in the wrong place and was a constant true.
    $fatal = Test-ServerLogFatal @($bdsStdout, $bdsStderr)
    $noFatal = ($null -eq $fatal)
    Log ("  join={0} chat={1} plugin={2} backend={3} noFatal={4}" -f $joinOk, $chatOk, $pluginOk, $backendOk, $noFatal)
    if (-not $noFatal) { Log ("  FATAL in server log: {0}:{1}: {2}" -f $fatal.Path, $fatal.LineNumber, $fatal.Line.Trim()) }
    $pass = $joinOk -and $chatOk -and $pluginOk -and $backendOk -and $noFatal -and (-not $runFailure)
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
    Show-FailureLogs "L1 assertion failed"
    exit 1
}
