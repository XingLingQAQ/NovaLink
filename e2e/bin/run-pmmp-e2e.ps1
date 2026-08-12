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
#   6. Run the bedrock-protocol bot (e2e/bot/run-e2e-bedrock.js).
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
# ============================================================================
[CmdletBinding()]
param(
    [string]$RepoRoot      = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))),
    [string]$DistDir       = "",     # server phar cache; defaults to <repo>/.e2e-artifacts/dist
    [string]$RunsDir       = "",     # per-run workspace; defaults to <repo>/.e2e-artifacts/runs/pmmp
    [int]$McPort           = 19132,  # Bedrock default UDP port
    [int]$NovaPort         = 27905,
    [int]$NovaWsPort       = 34573,
    [int]$ServerReadySec   = 180,
    [int]$BotTimeoutSec    = 360
)

$ErrorActionPreference = "Stop"
if (-not $DistDir) { $DistDir  = Join-Path $RepoRoot ".e2e-artifacts/dist" }
if (-not $RunsDir) { $RunsDir  = Join-Path $RepoRoot ".e2e-artifacts/runs/pmmp" }
$binDir   = Join-Path $RepoRoot "e2e/bin"
$botDir   = Join-Path $RepoRoot "e2e/bot"
$confDir  = Join-Path $RepoRoot "e2e/conf"
$pmmpSrc  = Join-Path $RepoRoot "NovaChat/Bedrock/pmmp"

function Log([string]$msg) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss.fff"), $msg)
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

# Locate the PHP runtime. PocketMine-MP requires a SPECIALIZED PHP build with
# custom extensions (chunkutils2, pmmpthread, leveldb, yaml, etc.) -- the stock
# system PHP (scoop/winget) does NOT have them and PMMP will exit early with
# "Selected PHP binary does not satisfy some requirements". fetch-server.ps1
# -Name pocketmine is expected to provision the PMMP PHP binary (a zip from
# pmmp/PHP-Binaries). We extract it and use its bin/php/php.exe.
$phpExe = $null
$pmmpPhpZip = Get-ChildItem (Join-Path $DistDir "PHP-*-Windows-x64-PM5.zip") -ErrorAction SilentlyContinue | Select-Object -First 1
$pmmpPhpDir = Join-Path $DistDir "php-runtime"
# The PMMP PHP zip extracts to bin/php/php.exe (nested). Check that real path.
$pmmpPhpExeRel = "bin/php/php.exe"
if ($pmmpPhpZip -and -not (Test-Path (Join-Path $pmmpPhpDir $pmmpPhpExeRel))) {
    Log "  unpacking PMMP PHP runtime: $($pmmpPhpZip.Name)"
    if (Test-Path $pmmpPhpDir) { Remove-Item $pmmpPhpDir -Recurse -Force }
    Expand-Archive -Path $pmmpPhpZip.FullName -DestinationPath $pmmpPhpDir -Force
}
$pmmpPhp = Join-Path $pmmpPhpDir $pmmpPhpExeRel
if (Test-Path $pmmpPhp) {
    $phpExe = $pmmpPhp
} else {
    # Fallback: a php.exe anywhere under dist (e.g. fetch-server dropped a
    # pre-extracted php tree). Recurse so a nested bin/php/php.exe still hits.
    $phpFromFetch = Get-ChildItem $DistDir -Recurse -Filter 'php.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($phpFromFetch) {
        $phpExe = $phpFromFetch.FullName
    } else {
        # Last resort: system PHP on PATH. This will only work if the system
        # PHP happens to have all PMMP extensions (it usually does NOT).
        $phpCmd = Get-Command php -ErrorAction SilentlyContinue
        if ($phpCmd) { $phpExe = $phpCmd.Source }
    }
}
if (-not $phpExe) {
    Log "ERROR: no PHP runtime found. Download the PMMP PHP binary from https://github.com/pmmp/PHP-Binaries/releases (PHP-*-Windows-x64-PM5.zip) into $DistDir"
    exit 2
}
Log "  php: $phpExe ($(& $phpExe -v 2>$null | Select-Object -First 1))"

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
if ($fatJar) {
    $novaArgs = @("-Xmx512m", "-jar", $fatJar.FullName, $novaYml)
} else {
    $cp = (Get-Content $classpathFile -Raw).Trim()
    $novaArgs = @("-Xmx512m", "-cp", $cp, "com.nova.link.NovaLinkMain", $novaYml)
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
        Log "ERROR: backend exited early; stderr:"; Get-Content $novaStderr -ErrorAction SilentlyContinue | Select-Object -First 10 | ForEach-Object { Log "  $_" }
        Teardown-All; exit 1
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
        Log "ERROR: PocketMine exited early; stderr:"; Get-Content $pmmpStderr -ErrorAction SilentlyContinue | Select-Object -First 15 | ForEach-Object { Log "  $_" }
        Teardown-All; exit 1
    }
    if (Test-Path $pmmpStdout) {
        $logContent = Get-Content $pmmpStdout -Raw -ErrorAction SilentlyContinue
        if ($logContent -match 'Done \(') { $serverReady = $true; break }
    }
}
if (-not $serverReady) {
    Log "ERROR: PocketMine did not reach 'Done' within ${ServerReadySec}s"
    Get-Content $pmmpStdout -ErrorAction SilentlyContinue | Select-Object -Last 30 | ForEach-Object { Log "  $_" }
    Teardown-All; exit 1
}
Log "  PocketMine ready"

# ---------------------------------------------------------------------------
# 6. Run the bedrock bot.
# ---------------------------------------------------------------------------
Log "step 6: installing + running the bedrock-protocol bot"
if (-not (Test-Path (Join-Path $botDir "node_modules/bedrock-protocol"))) {
    Push-Location $botDir
    try { npm install --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { Log "ERROR: npm install failed"; Teardown-All; exit 2 } }
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
Log "  bot exit code=$($botProc.ExitCode)"

# ---------------------------------------------------------------------------
# 7. Assert results.json.
# ---------------------------------------------------------------------------
Log "step 7: asserting L1 results"
$pass = $false
if (Test-Path $resultsFile) {
    $results = Get-Content $resultsFile -Raw | ConvertFrom-Json
    $msgs = $results.received | ForEach-Object { $_.text }
    $joinOk   = $msgs -match 'joined|已加入|join'
    $listOk   = $msgs -match 'global|list'
    $chatOk   = ($msgs | Where-Object { $_ -match 'hello from e2e bot' }).Count -ge 1
    $noFatal  = -not ($msgs -match 'FATAL|Exception.*NovaLink')
    Log ("  join={0} list={1} chat={2} noFatal={3}" -f $joinOk, $listOk, $chatOk, $noFatal)
    $pass = $joinOk -and $chatOk -and $noFatal
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
    exit 1
}
