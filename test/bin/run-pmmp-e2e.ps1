# ============================================================================
# run-pmmp-e2e.ps1 -- portable PocketMine-MP L1 E2E orchestrator.
#
# Runs the full Layer-1 flow for the pmmp platform:
#   1. Build the NovaLink backend (StarLink:core classpath via init-script, or
#      a fat jar if shadow landed it) + export backend runtime classpath.
#   2. Lay down the NovaChat pmmp plugin source into a PocketMine plugins/ dir.
#   3. Fetch PocketMine-MP phar + PHP runtime (fetch-server.ps1 -Name pocketmine).
#   4. Generate backend config + start the NovaLink backend.
#   5. Start PocketMine-MP (php PocketMine-MP.phar), wait for the "Done" log line.
#   6. Run the bedrock-protocol bot (test/bot/run-e2e-bedrock.js).
#   7. Assert results.json shows the L1 events (join, chat round-trip).
#   8. Teardown: stop PocketMine + backend, dump logs/artifacts on failure.
#
# PocketMine-MP is the easiest Bedrock server: a phar + PHP, no BDS/EULA/Xbox
# complexity. It is the reference for the three non-Java Bedrock platforms.
#
# Exit codes:
#   0 = L1 pass
#   1 = L1 fail (assertion mismatch or a process crashed)
#   2 = prereq error (build/download failed)
#   3 = skipped: no PHP runtime carries the PocketMine extensions, so the
#       server cannot boot and zero assertions ran. The reason is logged and
#       written to <runsDir>/skip-reason.txt for the orchestrator summary.
# ============================================================================
[CmdletBinding()]
param(
    # Resolved in the body below, NOT as a parameter default: under
    # [CmdletBinding()] parameters bind as an advanced function, and the
    # automatic variables ($MyInvocation / $PSScriptRoot / $PSCommandPath) are
    # all empty at bind time -- a default here is $null, so the Split-Path chain
    # throws before the script runs.
    [string]$RepoRoot      = "",
    [string]$DistDir       = "",     # server phar cache; defaults to <repo>/.e2e-artifacts/dist
    [string]$RunsDir       = "",     # per-run workspace; defaults to <repo>/.e2e-artifacts/runs/pmmp
    [int]$McPort           = 19132,  # Bedrock default UDP port
    [int]$NovaPort         = 27905,
    [int]$NovaWsPort       = 34573,
    [int]$ServerReadySec   = 180,
    [int]$BotTimeoutSec    = 360
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath)) }
if (-not $DistDir) { $DistDir  = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $RunsDir) { $RunsDir  = Join-Path $RepoRoot ".e2e-artifacts/runs/pmmp" }
$binDir   = Join-Path $RepoRoot "test/bin"
$botDir   = Join-Path $RepoRoot "test/bot"
$confDir  = Join-Path $RepoRoot "test/conf"
$pmmpSrc  = Join-Path $RepoRoot "NovaChat/Bedrock/pmmp"
. (Join-Path $binDir 'e2e-assertions.ps1')

function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg)
}

# --- failure diagnostics -----------------------------------------------------
# The backend, PocketMine and bot all write to files under $RunsDir, and a
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

# Track every PID we start so teardown is guaranteed.
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

# Ensure the run workspace + dist dir exist.
New-Item -ItemType Directory -Path $RunsDir -Force | Out-Null
New-Item -ItemType Directory -Path $DistDir -Force | Out-Null

# ---------------------------------------------------------------------------
# 0. Resolve a PHP runtime that can actually boot PocketMine-MP.
#
# PocketMine-MP requires ten extensions, and five of them (chunkutils2,
# crypto, encoding, morton, pmmpthread) are PocketMine-private C extensions
# that exist in NO standard PHP distribution and in NO PECL package. The
# server exits with "Selected PHP binary does not satisfy some requirements"
# before loading a single plugin. The only practical source is pmmp's own
# prebuilt binary (github.com/pmmp/PHP-Binaries, asset
# PHP-<ver>-Windows-x64-PM5.zip), which bundles all ten.
#
# So: provision that zip from the version lock, then VERIFY the runtime by
# asking it for its module list. Verification is not decoration -- a cached
# extract, a stale system PHP on PATH, or a partially unpacked tree all look
# present until php -m is asked, and the failure they cause inside PocketMine
# is an opaque ten-line ERROR block. Checking here turns that into one line
# naming the missing extensions.
#
# No usable runtime is a SKIP (exit 3), not a FAIL: the harness did not run,
# so claiming either PASS or FAIL would be a lie about NovaLink's health. The
# orchestrator reports "pmmp SKIPPED (<reason>)" and strict CI mode still
# fails the job, so a missing runtime can never masquerade as green.
# ---------------------------------------------------------------------------
$phpExtensionsRequired = @(
    'chunkutils2', 'crypto', 'encoding', 'gmp', 'igbinary',
    'leveldb', 'morton', 'pmmpthread', 'yaml', 'zip'
)

# Read the extension list from a php.exe. Returns @{ Ok; Version; Modules;
# Missing }. `php -m` exits non-zero when a .dll in php.ini fails to load, so
# the exit code is deliberately ignored -- the module list is what matters,
# and a startup warning is reported as a missing extension.
function Get-PhpRuntimeInfo([string]$exe) {
    $info = @{ Ok = $false; Version = ''; Modules = @(); Missing = @() }
    if (-not $exe -or -not (Test-Path -LiteralPath $exe)) { return $info }
    $savedEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # A broken php.ini prints startup warnings before the version banner,
        # so take the first line that actually looks like the PHP banner.
        $versionLine = (& $exe -v 2>$null | Where-Object { $_ -match '^PHP \d' } | Select-Object -First 1)
        $moduleLines = @(& $exe -m 2>$null)
    } catch {
        return $info
    } finally {
        $ErrorActionPreference = $savedEap
    }
    if (-not $versionLine) { return $info }
    # Normalise: php -m prints one extension per line, but on Windows the
    # section headers ([PHP Modules] / [Zend Modules]) and a trailing blank
    # line come along. Trim + lowercase, then compare set-wise.
    $modules = @($moduleLines |
        ForEach-Object { $_.Trim().ToLowerInvariant() } |
        Where-Object { $_ -and -not $_.StartsWith('[') })
    $missing = @($phpExtensionsRequired | Where-Object { $modules -notcontains $_ })
    $info.Version = $versionLine
    $info.Modules = $modules
    $info.Missing = $missing
    $info.Ok = ($missing.Count -eq 0)
    return $info
}

$phpExe = $null
$phpInfo = $null

# Candidate A: the pmmp prebuilt runtime, present as a cached extract.
$pmmpPhpDir = Join-Path $DistDir 'php-runtime'
$pmmpPhpExeRel = 'bin/php/php.exe'
$pmmpPhpExe = Join-Path $pmmpPhpDir $pmmpPhpExeRel
if (Test-Path -LiteralPath $pmmpPhpExe) {
    $candidate = Get-PhpRuntimeInfo $pmmpPhpExe
    if ($candidate.Ok) {
        $phpExe = $pmmpPhpExe
        $phpInfo = $candidate
    } else {
        Log "  cached PMMP PHP at $pmmpPhpExe is unusable (missing: $($candidate.Missing -join ', ')); re-provisioning"
        Remove-Item -LiteralPath $pmmpPhpDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Candidate B: provision from the version lock via fetch-server.ps1. The lock
# pins both the release URL and its SHA-256, so this is reproducible and a
# corrupted/tampered download is rejected before it is ever unpacked.
if (-not $phpExe) {
    $phpLock = $null
    $lockFile = Join-Path (Split-Path -Parent $binDir) 'versions.lock.ps1'
    if (Test-Path -LiteralPath $lockFile) {
        . $lockFile
        $phpLock = Get-LockedServer -Name 'pmmp-php'
    }
    if (-not $phpLock) {
        Log "  no 'pmmp-php' entry in test/versions.lock.ps1; cannot provision the PMMP PHP runtime"
    } else {
        Log "step 0: fetching the PMMP PHP runtime ($($phpLock.File))"
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $binDir 'fetch-server.ps1') -Name 'pmmp-php' -DistDir $DistDir
        $phpZip = Join-Path $DistDir $phpLock.File
        if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $phpZip)) {
            Log "  unpacking PMMP PHP runtime: $($phpLock.File)"
            # A half-written extract from a killed run would otherwise be
            # mistaken for a good one, so clear it before expanding.
            if (Test-Path -LiteralPath $pmmpPhpDir) { Remove-Item -LiteralPath $pmmpPhpDir -Recurse -Force }
            Expand-Archive -LiteralPath $phpZip -DestinationPath $pmmpPhpDir -Force
            $candidate = Get-PhpRuntimeInfo (Join-Path $pmmpPhpDir $pmmpPhpExeRel)
            if ($candidate.Ok) {
                $phpExe = Join-Path $pmmpPhpDir $pmmpPhpExeRel
                $phpInfo = $candidate
            } else {
                Log "  provisioned PMMP PHP is missing: $($candidate.Missing -join ', ')"
            }
        } else {
            Log "  fetch-server.ps1 could not download $($phpLock.File) (exit $LASTEXITCODE)"
        }
    }
}

# Candidate C: system PHP. Only useful if the host already has the PMMP
# extensions (e.g. a dev box that installed them by hand); on a stock runner
# this always fails verification, which is the correct outcome.
if (-not $phpExe) {
    $phpCmd = Get-Command php -ErrorAction SilentlyContinue
    if ($phpCmd) {
        $candidate = Get-PhpRuntimeInfo $phpCmd.Source
        if ($candidate.Ok) {
            Log "  using system PHP at $($phpCmd.Source) (it carries all required extensions)"
            $phpExe = $phpCmd.Source
            $phpInfo = $candidate
        } else {
            Log "  system PHP at $($phpCmd.Source) is missing: $($candidate.Missing -join ', ')"
        }
    } else {
        Log "  no php on PATH"
    }
}

if (-not $phpExe) {
    $reason = "no PHP runtime with the PocketMine extensions (chunkutils2/crypto/encoding/gmp/igbinary/leveldb/morton/pmmpthread/yaml/zip)"
    Log "SKIP: $reason - pmmp E2E not run."
    Log "  The PMMP PHP build is published at https://github.com/pmmp/PHP-Binaries/releases"
    Log "  (asset PHP-<ver>-Windows-x64-PM5.zip). It is pinned in test/versions.lock.ps1"
    Log "  as 'pmmp-php'; re-run fetch-server.ps1 -Name pmmp-php to provision it."
    Set-Content -LiteralPath (Join-Path $RunsDir 'skip-reason.txt') -Value 'PMMP PHP runtime unavailable'
    exit 3
}

Log "  php: $phpExe ($($phpInfo.Version))"

# PocketMine bootstraps through its own php.ini (extension_dir=ext, all the
# pmmp DLLs enabled). That ini is discovered relative to php.exe, so it is
# found regardless of the working directory -- but an inherited PHP_INI_SCAN_DIR
# (setup-php sets one; scoop sets one) would pull a SECOND ini set into the
# process. Clear it and pin PHPRC to the runtime's own ini so the child server
# and the phar build both get exactly the intended configuration.
$phpIni = Join-Path (Split-Path -Parent $phpExe) 'php.ini'
if (Test-Path -LiteralPath $phpIni) {
    $env:PHPRC = $phpIni
}
$env:PHP_INI_SCAN_DIR = ''

# ---------------------------------------------------------------------------
# 1. Locate the NovaLink backend artifact. Prefer the shadow fat jar (built by
#    StarLink/core's com.gradleup.shadow plugin -> *-all.jar); only fall back
#    to the gradle classpath export when NO fat jar exists. The fat jar path
#    lets this E2E skip gradle entirely (the gradle classpath export hits the
#    known multi-project configure hang under loom without --configure-on-demand).
#    The pmmp plugin is pure PHP source -- no Java build step.
# ---------------------------------------------------------------------------
$fatJar = Get-ChildItem (Join-Path $RepoRoot "StarLink/core/build/libs/*-all.jar") -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $fatJar) {
    $fatJar = Get-ChildItem (Join-Path $RepoRoot "StarLink/core/build/libs/*-fat.jar") -ErrorAction SilentlyContinue | Select-Object -First 1
}
$classpathFile = Join-Path $RepoRoot ".e2e-artifacts/novalink-core.classpath.txt"
if ($fatJar) {
    Log "step 1: using pre-built fat jar: $($fatJar.FullName) (skipping gradle)"
} else {
    Log "step 1: no fat jar; exporting StarLink:core backend classpath via gradle"
    $buildErr = Join-Path $RunsDir "build.err.log"
    $buildOut = Join-Path $RunsDir "build.out.log"
    # Use Start-Process (not the & call operator) so gradlew's stderr output is
    # captured to the file instead of being wrapped as a NativeCommandError record.
    # Under $ErrorActionPreference = "Stop", the & call operator + 2>$buildErr would
    # turn each Fabric Loom stderr banner line into a terminating error.
    $gradleArgs = @(":StarLink:core:jar", "-x", "test", "--console=plain", "--configure-on-demand", `
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
    if (-not (Test-Path $classpathFile)) {
        Log "ERROR: classpath file not written: $classpathFile"
        exit 2
    }
    Log "  using classpath: $classpathFile"
}

# ---------------------------------------------------------------------------
# 2. Fetch PocketMine-MP phar + PHP (other agent owns fetch-server.ps1).
# ---------------------------------------------------------------------------
Log "step 2: fetching PocketMine-MP phar"
# Try the shared fetch-server.ps1 first. If it has no 'pocketmine' entry yet
# (another agent owns versions.lock.ps1), it will exit non-zero -- in that case
# we fall back to a directly-downloaded phar already present in $DistDir.
$fetchOk = $false
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $binDir "fetch-server.ps1") -Name "pocketmine" -DistDir $DistDir
if ($LASTEXITCODE -eq 0) { $fetchOk = $true } else { Log "  fetch-server.ps1 has no 'pocketmine' pin yet; falling back to direct phar in $DistDir" }
$pmmpPhar = Get-ChildItem (Join-Path $DistDir "PocketMine-MP*.phar") -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $pmmpPhar) {
    $pmmpPhar = Get-ChildItem (Join-Path $DistDir "*.phar") -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'pocket|pmmp' } | Select-Object -First 1
}
if (-not $pmmpPhar) {
    if ($fetchOk) {
        Log "ERROR: fetch-server reported success but no PocketMine-MP phar found under $DistDir"
    } else {
        Log "ERROR: no PocketMine-MP phar in $DistDir. Download it from https://github.com/pmmp/PocketMine-MP/releases and place it there."
    }
    exit 2
}
Log "  phar: $($pmmpPhar.FullName)"

# The PHP runtime is resolved and extension-verified before step 1 (see the
# prereq gate above), so nothing else here needs to locate it.

# ---------------------------------------------------------------------------
# 3. Lay down PocketMine run directory + install the NovaChat pmmp plugin.
# ---------------------------------------------------------------------------
Log "step 3: laying down PocketMine run directory + NovaChat plugin"
$serverDir = Join-Path $RunsDir "pocketmine"
$pluginsDir = Join-Path $serverDir "plugins"
New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
New-Item -ItemType Directory -Path $pluginsDir -Force | Out-Null

# Build NovaChat.phar from the PMMP plugin source. PocketMine-MP 5.x's
# PharPluginLoader::canLoadPlugin() ONLY accepts files ending in .phar -- it
# does NOT ship a FolderPluginLoader, so dropping a source dir
# (plugins/NovaChat/plugin.yml + src/) into plugins/ is silently ignored
# (no "Loading NovaChat" log line, no error). We must package the source into a
# .phar first. The build is a pure-PHP Phar build (no external tool needed).
$pluginPhar = Join-Path $pluginsDir "NovaChat.phar"
$pharBuilder = Join-Path $binDir "build-plugin-phar.php"
if (-not (Test-Path $pharBuilder)) {
    Log "ERROR: phar builder script not found: $pharBuilder"
    exit 2
}
# phar.write requires phar.readonly=0 (default is 1). Pass -d to override.
& $phpExe -d phar.readonly=0 $pharBuilder $pmmpSrc $pluginPhar
if ($LASTEXITCODE -ne 0) {
    Log "ERROR: NovaChat.phar build failed (see above). Ensure the PMMP PHP has the Phar extension."
    exit 2
}
Log "  plugin installed: $pluginPhar"

# PocketMine server.properties (Bedrock). xbox-auth=off + offline players for
# local E2E; level-type=flat for fast world gen.
@"
motd=NovaChat-PMMP-E2E
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
"@ | Set-Content -Path (Join-Path $serverDir "server.properties")

# PocketMine has NO Mojang EULA gate (it is not BDS). It shows a first-run
# interactive setup wizard on first launch; --no-wizard (passed at launch)
# skips it. We intentionally do NOT write a custom pocketmine.yml here -- PMMP
# generates a sane default on first run, and a hand-written yml with the wrong
# schema keys crashes World->initRandomTickBlocksFromConfig (foreach on a bool).
# The only config we inject is server.properties (above) + the plugin config
# (below); PMMP's auto-generated pocketmine.yml is left untouched.

# Generate backend credentials early so the plugin config (written next) can
# reference them. The same $clientPw must appear in both novalink.yml (backend
# clients[].password) and the plugin's config.yml (backend.password) or the
# plugin's handshake will fail NC-401.
$secretKey = New-RandomHex 32
$clientPw  = New-RandomHex 16

# Write the plugin's backend config (plugin_data/NovaChat/config.yml) BEFORE first
# start so PMMP does not save a default (from the phar's resources/config.yml)
# that points at the wrong backend (port 18888, username PMMP_Server).
# CRITICAL: the username here MUST match clients[].username in novalink.yml
# (the template uses "E2E_Client"), or the backend rejects with NC-401. The
# password must match {{CLIENT_PASSWORD}} substituted into novalink.yml.
# PMMP writes plugin data to plugin_data/<name>/ when pocketmine.yml's
# plugins.legacy-data-dir is false (the default), so we pre-create that dir +
# drop the config there before the server starts.
$pluginDataDir = Join-Path $serverDir "plugin_data/NovaChat"
New-Item -ItemType Directory -Path $pluginDataDir -Force | Out-Null
$pluginConfig = Join-Path $pluginDataDir "config.yml"
@"
backend:
  host: "127.0.0.1"
  port: $NovaPort
  username: "E2E_Client"
  password: "$clientPw"
  server-version: "5.0.0"
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
# second after "started successfully" -- long before PocketMine and the bot can
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

# Wait for backend readiness.
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
if (-not $ready) { Log "WARN: backend readiness not confirmed in logs, proceeding (PMMP will retry connect)" }

# ---------------------------------------------------------------------------
# 5. Start PocketMine-MP + wait for "Done".
# ---------------------------------------------------------------------------
Log "step 5: starting PocketMine-MP on port $McPort"
$pmmpStdout = Join-Path $serverDir "pmmp.stdout.log"
$pmmpStderr = Join-Path $serverDir "pmmp.stderr.log"
$pmmpPidFile = Join-Path $serverDir "pmmp.pid"
# --no-wizard skips the first-run interactive setup; --disable-ansi strips color
# codes so the log is greppable. --no-xbox+offline via server.properties above.
$pmmpProc = Start-Process -FilePath $phpExe -ArgumentList @($pmmpPhar.FullName, "--no-wizard", "--disable-ansi") `
    -WorkingDirectory $serverDir -RedirectStandardOutput $pmmpStdout -RedirectStandardError $pmmpStderr -PassThru -NoNewWindow
Track-Pid $pmmpProc.Id
$pmmpProc.Id | Set-Content $pmmpPidFile
Log "  pmmp pid=$($pmmpProc.Id)"

# PocketMine readiness: it logs "Done! (Xs)" on completion of startup. It also
# logs "This server is running PocketMine-MP version" early -- do NOT use that as
# the ready marker (it prints before world load). The "Done" line is the gate.
$serverReady = $false
for ($i = 0; $i -lt ($ServerReadySec / 5); $i++) {
    Start-Sleep -Seconds 5
    if ($pmmpProc.HasExited) {
        # PocketMine writes its startup failures (missing extension, bad phar,
        # port clash) to stdout, not stderr -- dumping only stderr printed an
        # empty block and hid the cause. Show both, as the backend check above
        # already does.
        Log "ERROR: PocketMine exited early; stdout (tail):"
        Get-Content $pmmpStdout -ErrorAction SilentlyContinue | Select-Object -Last 25 | ForEach-Object { Log "  $_" }
        Log "ERROR: PocketMine exited early; stderr:"
        Get-Content $pmmpStderr -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Log "  $_" }
        # The extension check above should have caught this before boot, but
        # PocketMine can still reject the runtime (e.g. a version it does not
        # accept). That is a missing prerequisite, not a NovaLink regression,
        # so report SKIPPED rather than the FAIL this would otherwise become.
        $earlyOut = Get-Content $pmmpStdout -Raw -ErrorAction SilentlyContinue
        if ($earlyOut -match 'does not satisfy some requirements|Unable to find the .* extension') {
            Log "SKIP: PocketMine rejected the PHP runtime's extensions - no NovaLink assertion ran."
            Set-Content -LiteralPath (Join-Path $RunsDir 'skip-reason.txt') -Value 'PMMP PHP extensions rejected'
            Teardown-All; exit 3
        }
        Teardown-All; Show-FailureLogs "PocketMine exited early"; exit 1
    }
    if (Test-Path $pmmpStdout) {
        $logContent = Get-Content $pmmpStdout -Raw -ErrorAction SilentlyContinue
        if ($logContent -match 'Done \(') { $serverReady = $true; break }
    }
}
if (-not $serverReady) {
    Log "ERROR: PocketMine did not reach 'Done' within ${ServerReadySec}s"
    Get-Content $pmmpStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    Teardown-All; Show-FailureLogs "PocketMine did not reach 'Done' within ${ServerReadySec}s"; exit 1
}
Log "  PocketMine ready"

# ---------------------------------------------------------------------------
# 6. Run the bedrock bot.
# ---------------------------------------------------------------------------
Log "step 6: installing + running the bedrock-protocol bot"
if (-not (Test-Path (Join-Path $botDir "node_modules/bedrock-protocol"))) {
    Push-Location $botDir
    try { npm ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { Log "ERROR: npm ci failed"; Teardown-All; Show-FailureLogs "npm ci failed"; exit 2 } }
    finally { Pop-Location }
}
$resultsFile = Join-Path $RunsDir "bot-results.json"
$env:SERVER_HOST = "127.0.0.1"
$env:SERVER_PORT = "$McPort"
$env:BOT_NAME = "E2E_Bot_PMMP"
$env:PLATFORM = "pmmp"
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
    # the `join` regex), "/nc list" (matched the `list` regex) and
    # "hello from e2e bot" (matched the chat regex). All three were satisfied
    # by the bot's own input and could not fail on any plugin state.
    #
    # listOk: PMMP prints a "NovaChat Channels:" header under its prefix. This
    # replaces the old `global|list` regex, which matched the bot's own
    # "/nc list" row.
    #
    # chatOk caveat: this harness writes chat.replace_vanilla: false, which
    # PMMP's onPlayerChat reads as HYBRID and therefore does NOT forward -- the
    # bot's own chat line never reaches the backend at all. PMMP can therefore
    # never produce the 'text:raw' row chatOk requires, so chatOk is expected
    # to be FALSE here and the failure is a property of the harness config, not
    # of the plugin. It is left in as a gate on purpose: this harness is
    # already red on backendOk (see below) and turning chatOk green would need
    # provisioning PMMP with a reachable backend, which is the real fix.
    # backendOk below is the flag that does prove the link.
    $rows = Get-BotReceivedRows $results
    $msgs = Get-BotReceivedTexts $rows
    # joinOk: NovaChatCommand::handleJoin prints "Joined channel: <id>" as a
    # literal, but unconditionally once a chat handler exists -- it is not a
    # backend proof. The old `join` regex additionally matched the vanilla
    # "E2E_Bot_X joined the game" banner.
    $joinOk   = [bool]($msgs -match 'Joined channel')
    $listOk   = [bool]($msgs -match 'NovaChat Channels')
    # chatOk: the phrase must come back inside NovaChat's channel-format
    # envelope ("[<channel>] <player>: <text>"), as a plugin-emitted raw text
    # row. The envelope is the load-bearing part: vanilla's own re-broadcast of
    # the sender's line also reaches the bot, so matching the phrase alone
    # still passed on a bare server. Colour codes are stripped first because
    # bedrock-protocol records them ("<sect>c[全服] ...").
    #
    # PMMP forwards player chat ONLY in REPLACE mode: onPlayerChat returns
    # early for the configured default --
    # "HYBRID mode: let vanilla chat handle the message. Forwarding here would
    #  produce duplicates (vanilla + backend)".
    # The harness keeps chat.replace_vanilla: false, so the mode that enables
    # forwarding must come from the bot's /nc toggle. run-e2e-bedrock.js now
    # sends the phrase after ONE toggle (REPLACE); it used to send two, which
    # landed back in HYBRID and made this row impossible to produce.
    $chatOk   = (@(Select-BotRowsByPlainText $rows '\[[^\]]+\]\s*E2E_Bot_PMMP\s*:\s*.*hello from e2e bot' |
        Where-Object { $_.kind -eq 'text:raw' })).Count -ge 1
    # pluginOk: a line only NovaChat can emit reached the bot.
    $pluginOk = Test-PluginEvidence $rows
    # backendOk: handleWho prints "Fetching online members for <id>..." only
    # after NetworkClient::isAuthenticated() passes; the alternative branch is
    # "Channel member query is unavailable (requires backend support)".
    $backendOk = Test-BackendEvidence $rows
    # Fatal check reads the SERVER's log. Plugin errors go to PocketMine's
    # console log, never to the bot's chat transcript, so the old $msgs scan
    # looked in the wrong place and was a constant true.
    $fatal = Test-ServerLogFatal @($pmmpStdout, $pmmpStderr)
    $noFatal = ($null -eq $fatal)
    Log ("  join={0} list={1} chat={2} plugin={3} backend={4} noFatal={5}" -f $joinOk, $listOk, $chatOk, $pluginOk, $backendOk, $noFatal)
    if (-not $noFatal) { Log ("  FATAL in server log: {0}:{1}: {2}" -f $fatal.Path, $fatal.LineNumber, $fatal.Line.Trim()) }
    $pass = $joinOk -and $listOk -and $chatOk -and $pluginOk -and $backendOk -and $noFatal
} else {
    Log "ERROR: results.json not written: $resultsFile"
}

# ---------------------------------------------------------------------------
# 8. Teardown.
# ---------------------------------------------------------------------------
Teardown-All
if ($pass) {
    Log "RESULT: L1 PASS (pmmp/pocketmine)"
    exit 0
} else {
    Log "RESULT: L1 FAIL (pmmp/pocketmine) -- dumping bot log tail:"
    Get-Content $botStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    Show-FailureLogs "L1 assertion failed"
    exit 1
}
