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
# LeviLamina is NOT downloaded by anything else: this script fetches all five
# pinned artifacts (bds-levilamina + the four levilamina-* entries in
# versions.lock.ps1) and performs the install lip would do:
#   plugins/LeviLamina/{LeviLamina.dll,manifest.json}
#   PreLoader.dll, bedrock_runtime_data   in the BDS root
#   PeEditor.exe -mb                      patches bedrock_server.exe
#                                         -> bedrock_server_mod.exe
# and then launches bedrock_server_mod.exe. Without the patch step BDS boots
# unpatched, the preloader never loads, and plugins/*.dll is never injected --
# which is what made pluginOk/chatOk unachievable against a bare BDS zip.
#
# BDS is pinned to 1.26.51 for this platform, NOT the endstone 1.26.52: LeviLamina
# v26.51.7 hard-depends on bds 1.26.51 and no v26.52.* tag exists. See the
# bds / bds-levilamina entries in versions.lock.ps1 for the full evidence.
#
# Exit codes:
#   0 = L1 pass
#   1 = L1 fail (assertion mismatch or a process crashed)
#   2 = prereq error (build/download failed)
#   3 = SKIPPED (a required binary is unavailable, so NO test ran)
#   4 = SKIPPED-BY-DESIGN (the platform structurally cannot run here)
#
# Exit 3 is deliberately distinct from 0: a platform that ran zero assertions
# must never be reported as PASS. run-e2e-orchestrator.ps1 maps 3 to SKIPPED
# (strict mode treats it as a failure) and 4 to SKIPPED-BY-DESIGN (strict mode
# passes it). The LeviLamina/BDS version split is NOT a by-design skip: it is
# handled by pinning the right BDS, so a failure to obtain these binaries is a
# real infrastructure problem and stays exit 3.
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
# The bot's protocol version has to agree with the BDS build this script runs,
# so it is read from versions.lock.ps1 rather than repeated as a literal here.
# The literal used to be "1.26.30"; a 1.26.x BDS speaks protocol 2193 and kicks
# a 1.26.30 client (protocol 1001) as disconnectionScreen.outdatedClient. See
# the resolution below the dot-source.
. (Join-Path (Split-Path -Parent $binDir) 'versions.lock.ps1')
. (Join-Path $binDir 'e2e-assertions.ps1')

function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg)
}

# This platform runs its OWN BDS (bds-levilamina), not the shared endstone one,
# so every BDS-derived value comes from that entry.
$bdsLock = Get-LockedServer -Name 'bds-levilamina'
if (-not $bdsLock) {
    Log "ERROR: no 'bds-levilamina' lock entry in test/versions.lock.ps1"
    exit 2
}

# The version string handed to bedrock-protocol. It comes from the lock entry's
# BotMcVersion, NOT from its MC field: minecraft-data maps 1.26.51 to protocol
# 2193 and has no entry for the four-part BDS build strings. The fallback only
# fires against a lock revision older than the BotMcVersion field.
$botMcVersion = if ($bdsLock.BotMcVersion) {
    $bdsLock.BotMcVersion
} else {
    Log "WARN: no BotMcVersion on the 'bds-levilamina' lock entry; falling back to 1.26.51"
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
# A prebuilt bin/ dir is gitignored and REUSED without rebuilding when both the
# dll and its manifest are present. That copy is generated from the source
# manifest at build time, so a bin/ left over from an older checkout keeps the
# manifest it was packed with and silently overrides the source one. The exact
# trap this guards: a stale bin/ manifest still declaring a "LeviLamina"
# dependency makes LeviLamina reject the mod with "will not be loaded because
# dependency LeviLamina is missing" and load 0 mods -- which then surfaces as a
# misleading readiness timeout. Compare against the source manifest and prefer
# the source one on any drift rather than shipping a known-bad copy.
$srcManifest = Join-Path $levilaminaSrc "manifest.json"
if ((Test-Path $dllManifest) -and (Test-Path $srcManifest)) {
    $packedManifest = Get-Content $dllManifest -Raw -ErrorAction SilentlyContinue
    $sourceManifest = Get-Content $srcManifest -Raw -ErrorAction SilentlyContinue
    if ($packedManifest -ne $sourceManifest) {
        Log "  WARN: packed manifest differs from the source manifest; using the source manifest"
        $dllManifest = $srcManifest
    }
}
Log "  dll: $dllPath"
Log "  manifest: $dllManifest"

# ---------------------------------------------------------------------------
# 3. Fetch BDS + install LeviLamina + the NovaChat .dll.
# ---------------------------------------------------------------------------
Log "step 3: fetching Bedrock Dedicated Server (BDS) + LeviLamina"
# PINNED, no -Auto. The links API only ever serves Mojang's CURRENT BDS, which
# is now 1.26.52 -- precisely the build LeviLamina v26.51.7 cannot load (its
# tooth.json hard-depends on bds 1.26.51 and no v26.52.* tag exists). An -Auto
# fetch here would silently undo the pin and break the platform, so the pinned
# artifact is the contract.
#
# fetch-server.ps1 exits non-zero on a failed download or a hash mismatch, which
# is a real prereq error (exit 2), not a SKIP: a download that fails is a bug or
# an outage someone must fix, and the artifact is pinned so it cannot 404 from
# version drift.
$fetchScript = Join-Path $binDir "fetch-server.ps1"
foreach ($fetchName in @('bds-levilamina', 'levilamina', 'levilamina-preloader', 'levilamina-peeditor', 'levilamina-runtime')) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $fetchScript -Name $fetchName -DistDir $DistDir
    if ($LASTEXITCODE -ne 0) {
        Log "ERROR: fetch-server failed for $fetchName"
        Teardown-All
        exit 2
    }
}

# fetch-server lays each Kind=zip entry down as dist/<Name>-<MC>/. Locate them by
# walking the pinned paths rather than globbing dist/, so a stale artifact from a
# previous lock revision can never be picked up.
function Get-FetchedDir([string]$Name) {
    $e = Get-LockedServer -Name $Name
    if (-not $e) { return $null }
    $d = Join-Path $DistDir "$($e.Name)-$($e.MC)"
    if (Test-Path $d) { return $d }
    return $null
}

$bdsDist = Get-FetchedDir 'bds-levilamina'
if (-not $bdsDist -or -not (Test-Path (Join-Path $bdsDist 'bedrock_server.exe'))) {
    Log "SKIP: BDS server binary unavailable - levilamina E2E not run (expected an extracted tree with bedrock_server.exe under $bdsDist)"
    Set-Content -Path (Join-Path $RunsDir "skip-reason.txt") -Value "BDS unavailable"
    Teardown-All
    exit 3
}
# Verify the loader actually targets the BDS we fetched. These are two separate
# pins on purpose (LeviLamina has no release for a newer BDS), and bumping one
# without the other yields a server that boots but never injects anything -- a
# confusing FAIL far from the cause. Fail loudly at the join instead.
$leviLock = Get-LockedServer -Name 'levilamina'
if ($leviLock -and $leviLock.RequiresBds -and $leviLock.RequiresBds -ne $bdsLock.MC) {
    Log "ERROR: pin mismatch - levilamina $($leviLock.MC) requires BDS $($leviLock.RequiresBds) but bds-levilamina is pinned to $($bdsLock.MC)"
    Teardown-All
    exit 2
}

# The SDK the mod COMPILES against is pinned separately, in its own xmake.lua,
# and the check above does not cover it. A skew there stays invisible until
# runtime: the mod loads and enables, then LeviLamina's symbol resolver fails
# every Minecraft API it needs ("Couldn't find: CommandOutput::success(void)"
# and friends), and the server dies with a bare "Process Crashed!" the moment a
# command runs. Guard it so a mismatched pair fails here, naming both versions.
$xmakeLua = Join-Path $levilaminaSrc 'xmake.lua'
if ($leviLock -and (Test-Path $xmakeLua)) {
    $sdkPin = [regex]::Match(
        (Get-Content $xmakeLua -Raw),
        'add_requires\(\s*"levilamina\s+([0-9][0-9.]*)"'
    ).Groups[1].Value
    if ($sdkPin -and $sdkPin -ne $leviLock.MC) {
        Log "ERROR: SDK pin mismatch - xmake.lua builds against levilamina $sdkPin but versions.lock.ps1 pins the runtime loader to $($leviLock.MC). The mod would load, then crash on the first command."
        Teardown-All
        exit 2
    }
    if ($sdkPin) { Log "  SDK/runtime aligned: levilamina $sdkPin" }
}

$serverDir = Join-Path $RunsDir "bds"
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
Copy-Item (Join-Path $bdsDist '*') -Destination $serverDir -Recurse -Force
Log "  BDS unpacked from ${bdsDist}: $serverDir"

# --- LeviLamina install (the steps `lip install github.com/LiteLDev/LeviLamina`
# would run, done explicitly so the runner needs no lip binary) ---------------
# 1. The loader itself: LeviLamina/{LeviLamina.dll,manifest.json} -> plugins/.
$leviDist = Get-FetchedDir 'levilamina'
if (-not $leviDist -or -not (Test-Path (Join-Path $leviDist 'LeviLamina/LeviLamina.dll'))) {
    Log "ERROR: LeviLamina package missing or not extracted: $leviDist"
    Teardown-All; exit 2
}
$leviPluginDir = Join-Path $serverDir "plugins/LeviLamina"
New-Item -ItemType Directory -Path $leviPluginDir -Force | Out-Null
Copy-Item (Join-Path $leviDist 'LeviLamina/*') -Destination $leviPluginDir -Recurse -Force
Log "  LeviLamina loader installed: $leviPluginDir"

# 2. PreLoader.dll + bedrock_runtime_data go in the BDS ROOT -- the patched exe
#    resolves both relative to itself, not to plugins/.
$preDist = Get-FetchedDir 'levilamina-preloader'
if (-not $preDist -or -not (Test-Path (Join-Path $preDist 'bin/PreLoader.dll'))) {
    Log "ERROR: PreLoader package missing or not extracted: $preDist"
    Teardown-All; exit 2
}
Copy-Item (Join-Path $preDist 'bin/PreLoader.dll') -Destination (Join-Path $serverDir 'PreLoader.dll') -Force
Log "  PreLoader.dll installed into the BDS root"

$rtDist = Get-FetchedDir 'levilamina-runtime'
if (-not $rtDist -or -not (Test-Path (Join-Path $rtDist 'bedrock_runtime_data'))) {
    Log "ERROR: bedrock-runtime-data missing or not extracted: $rtDist"
    Teardown-All; exit 2
}
Copy-Item (Join-Path $rtDist 'bedrock_runtime_data') -Destination (Join-Path $serverDir 'bedrock_runtime_data') -Force
Log "  bedrock_runtime_data installed into the BDS root"

# 3. Patch the BDS image. PeEditor's own defaults are -m -b (-mb): write a
#    patched copy as bedrock_server_mod.exe and keep the original as .bak. This
#    is the step whose absence left the preloader unloaded. PeEditor exits
#    non-zero on a failed patch, and a vanished original would mean it moved
#    rather than copied, so both are checked.
$peDist = Get-FetchedDir 'levilamina-peeditor'
if (-not $peDist -or -not (Test-Path (Join-Path $peDist 'PeEditor.exe'))) {
    Log "ERROR: PeEditor package missing or not extracted: $peDist"
    Teardown-All; exit 2
}
$peExe = Join-Path $serverDir "PeEditor.exe"
Copy-Item (Join-Path $peDist 'PeEditor.exe') -Destination $peExe -Force
$patcher = Start-Process -FilePath $peExe -ArgumentList @('-mb') -WorkingDirectory $serverDir `
    -RedirectStandardOutput (Join-Path $serverDir 'peeditor.stdout.log') `
    -RedirectStandardError  (Join-Path $serverDir 'peeditor.stderr.log') -PassThru -NoNewWindow -Wait
if ($patcher.ExitCode -ne 0) {
    Log "ERROR: PeEditor failed to patch the BDS image (exit $($patcher.ExitCode)); stderr:"
    Get-Content (Join-Path $serverDir 'peeditor.stderr.log') -ErrorAction SilentlyContinue | Select-Object -First 20 | ForEach-Object { Log "  $_" }
    Teardown-All; exit 2
}
$modExe = Join-Path $serverDir "bedrock_server_mod.exe"
if (-not (Test-Path $modExe)) {
    Log "ERROR: PeEditor reported success but $modExe was not produced; the preloader would never load"
    Teardown-All; exit 2
}
Log "  BDS image patched: bedrock_server_mod.exe ($((Get-Item $modExe).Length) bytes)"

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

# Install the NovaChat plugin into BDS's plugins/ dir, next to the LeviLamina
# loader installed above. A LeviLamina mod is a directory under plugins/ holding
# a manifest.json + the .dll its "entry" names.
$pluginsDir = Join-Path $serverDir "plugins"
New-Item -ItemType Directory -Path $pluginsDir -Force | Out-Null
$pluginDir = Join-Path $pluginsDir "novachat-levilamina"
New-Item -ItemType Directory -Path $pluginDir -Force | Out-Null
Copy-Item $dllPath -Destination $pluginDir -Force
Copy-Item $dllManifest -Destination $pluginDir -Force
# Default config template + translations. Mod::getResourceDir() is
# <modDir>/resource_packs and the mod loads getResourceDir()/default-config.json
# on load(), so without these the mod aborts with "Failed to load configuration"
# and LeviLamina reports "0 mod(s) loaded".
$dllDir = Split-Path -Parent $dllPath
foreach ($resourceDir in 'resource_packs', 'lang') {
    $resourceSrc = Join-Path $dllDir $resourceDir
    if (Test-Path $resourceSrc) {
        Copy-Item $resourceSrc -Destination $pluginDir -Recurse -Force
    } else {
        Log "  WARN: $resourceDir not found next to the .dll ($dllDir); the mod may fail to load"
    }
}
Log "  plugin installed: $pluginDir"

# Generate backend credentials early so the plugin config can reference them.
$secretKey = New-RandomHex 32
$clientPw  = New-RandomHex 16

# Write the plugin's backend config. The path is NOT <modDir>/config.json:
# LeviLamina's Mod::getDataDir() is <modDir>/data (Mod.cpp: mImpl->dataDir =
# mImpl->modDir / u8"data"), and NovaChatConfig's constructor builds
# mConfigPath = dataDir / "config.json" (NovaChatConfig.cpp:200). Writing to
# <modDir>/config.json therefore leaves the plugin's own file absent, so load()
# falls back to the bundled template -- whose backend.port is 8888 -- and every
# connect attempt goes to the wrong port. That is exactly what CI run
# 40272156700 showed: "Created config.json from the bundled template" followed
# by chat.network.not_connected, while the backend listened on 27907.
# LeviLamina's NovaChatConfig saves JSON on first load; we pre-write it so the
# plugin points at our backend from the first connect attempt.
# CRITICAL: the username here MUST match clients[].username in novalink.yml
# (the template uses "E2E_Client"), or the backend rejects with NC-401.
$pluginDataDir = Join-Path $pluginDir "data"
New-Item -ItemType Directory -Path $pluginDataDir -Force | Out-Null
$pluginConfig = Join-Path $pluginDataDir "config.json"
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
# Launch bedrock_server_mod.exe -- PeEditor's PATCHED copy, which is the one that
# loads PreLoader.dll and therefore LeviLamina. Falling back to bedrock_server.exe
# (the unpatched original) is what silently produced a plain server with no
# plugins loaded, so a missing patched exe is a hard prereq error here rather
# than a fallback. There is no "LeviLamina*.exe" launcher in the package: the
# loader is a dll in plugins/LeviLamina/.
$launchExe = Join-Path $serverDir "bedrock_server_mod.exe"
if (-not (Test-Path $launchExe)) {
    Log "ERROR: patched server executable missing: $launchExe"
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
        # BDS prints "Server started in (1.8s)!" on completion -- NO period after
        # "started", so the previous pattern's first alternative (which required
        # a literal period there) could never match a real BDS line and this loop
        # spun the full ServerReadySec timeout on a perfectly healthy server.
        # Keep the bare "Server started" prefix; do not reintroduce the period.
        # The 'Done \(' family covers Java-family servers ("Done (12.3s)!").
        # LeviLamina logs the plugin enable line from NovaChatPlugin::enable().
        if ($logContent -match 'Server started|Done \(') { $serverReady = $true; break }
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
$botRunId = Initialize-BotRun $resultsFile
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
    $pass = $joinOk -and $chatOk -and $pluginOk -and $backendOk -and $noFatal -and (-not $runFailure)
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
