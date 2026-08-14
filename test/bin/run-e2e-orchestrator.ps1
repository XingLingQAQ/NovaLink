# ============================================================================
# run-e2e-orchestrator.ps1 -- multi-platform E2E dispatcher.
#
# This is the CI entry point. It dispatches to per-platform scripts.
#
# CURRENT COVERAGE (honest status):
#   bukkit      -- FULLY IMPLEMENTED (run-bukkit-e2e.ps1, L1 flow complete).
#   folia       -- IMPLEMENTED via run-multiplatform-e2e.ps1 (shared primitive).
#   velocity    -- IMPLEMENTED via run-multiplatform-e2e.ps1 (proxy-only; the
#                  downstream purpur it fronts is not started by this harness).
#   bungee      -- IMPLEMENTED via run-multiplatform-e2e.ps1 (proxy-only; same
#                  downstream-purpur caveat as velocity).
#   nukkit      -- IMPLEMENTED via run-multiplatform-e2e.ps1 (Bedrock bot).
#   pnx         -- IMPLEMENTED via run-multiplatform-e2e.ps1 (Bedrock bot).
#   sponge      -- IMPLEMENTED via run-multiplatform-e2e.ps1 (JDK 17).
#   endstone    -- IMPLEMENTED (run-endstone-e2e.ps1, BDS+Endstone+python plugin).
#   levilamina  -- IMPLEMENTED (run-levilamina-e2e.ps1, BDS+LeviLamina+C++ dll).
#   pmmp        -- IMPLEMENTED (run-pmmp-e2e.ps1, PocketMine-MP phar+PHP).
#
# The seven Java platforms (bukkit/folia/velocity/bungee/nukkit/pnx/sponge) are
# driven by the shared run-multiplatform-e2e.ps1 orchestrator under
# test/bin/multiplatform/, which calls the parameterized start-backend.ps1 +
# start-server.ps1 primitives (one backend JVM + one server process per
# platform) and then runs the test/bot/run-e2e.js or run-e2e-bedrock.js bot.
# bukkit keeps its self-contained run-bukkit-e2e.ps1 because that script also
# builds the bukkit plugin fat jar and installs it into the server's plugins/
# dir -- a step the shared primitive does not do (the other Java platforms are
# tested against the server alone, without their platform plugin installed;
# wiring the per-platform plugin build + install is tracked as a follow-up).
# ============================================================================
[CmdletBinding()]
param(
    [string]$Platforms = "bukkit,pmmp",
    [string]$RepoRoot  = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))),
    [int]$TimeoutSec = 420,
    [int]$BotWaitSec = 150
)

$ErrorActionPreference = "Stop"
$binDir = Join-Path $RepoRoot "test/bin"
$multiplatformDir = Join-Path $binDir "multiplatform"
$RequestedPlatforms = $Platforms -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }

function Log([string]$m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }

$pathContract = Join-Path $binDir "verify-orchestration-paths.ps1"
if (-not (Test-Path -LiteralPath $pathContract -PathType Leaf)) {
    Log "ERROR: required harness contract is missing: $pathContract"
    exit 2
}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $pathContract -RepoRoot $RepoRoot
if ($LASTEXITCODE -ne 0) {
    Log "ERROR: committed E2E harness preflight failed."
    exit 2
}

# Platform definitions table. Each entry maps a platform name to its run script
# and the contract used by the assertion layer.
#
# Two dispatch paths:
#   * Self-contained scripts (run-<platform>-e2e.ps1) for platforms whose
#     orchestrator also builds + installs the platform plugin (bukkit) or that
#     need non-JVM server setup (endstone/levilamina/pmmp: BDS zip / PHP phar).
#     Invoked directly with -RepoRoot.
#   * The shared multiplatform orchestrator (run-multiplatform-e2e.ps1) for the
#     seven Java platforms (bukkit/folia/velocity/bungee/nukkit/pnx/sponge).
#     It calls start-backend.ps1 + start-server.ps1 + the bot, enforces a
#     per-platform timeout, and emits a combined summary JSON. Invoked with
#     -RepoRoot + -Platforms <the subset to run>.
#     bukkit is in BOTH tables: it has its self-contained script (preferred for
#     the full plugin-build+install flow) AND a multiplatform entry (so a
#     -Platforms bukkit,folia,... run can bundle it with the others).
#
# Fields:
#   Dispatch          - "self" (run-<platform>-e2e.ps1) | "multiplatform"
#   BackendScript     - the per-platform orchestrator script (for "self" only).
#   BotScript         - the bot script the orchestrator runs.
#   ServerReadyFile   - the log file whose readiness marker is grepped.
#   ServerReadyPattern - the regex that marks server readiness.
#   ResultsFile       - where the bot writes results.json.
#   ChatPhrase        - the chat phrase the bot sends for the round-trip.
#   Jdk               - the JDK the backend needs (the server itself is PHP/native
#                       for the three Bedrock platforms; Jdk is for the backend).
$PlatformDefs = @{
    bukkit = @{
        Dispatch           = "self"
        BackendScript      = "run-bukkit-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name purpur")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "paper/purpur.stdout.log"
        ServerReadyPattern = 'Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    folia = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name folia")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "folia/folia.stdout.log"
        ServerReadyPattern = 'Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    velocity = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name velocity")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "velocity/velocity.stdout.log"
        ServerReadyPattern = 'Done|Listening on'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 25
    }
    bungee = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name waterfall")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "bungee/waterfall.stdout.log"
        ServerReadyPattern = 'Listening on'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    nukkit = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name nukkit")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "nukkit/nukkit.stdout.log"
        ServerReadyPattern = 'Done|Default game mode'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    pnx = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name nukkit")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "pnx/nukkit.stdout.log"
        ServerReadyPattern = 'Done|Default game mode'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    sponge = @{
        Dispatch           = "multiplatform"
        BackendScript      = "run-multiplatform-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name sponge")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "sponge/sponge.stdout.log"
        ServerReadyPattern = 'Sponge server started|Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 17
    }
    # ---- Three non-Java Bedrock platforms (added 2026-08-09) ----
    # endstone: BDS server + Endstone python host + novachat_endstone pip plugin.
    # Server is native (BDS); backend uses JDK21. Bot is bedrock-protocol.
    endstone = @{
        Dispatch           = "self"
        BackendScript      = "run-endstone-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name bds -Auto")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "bds/bds.stdout.log"
        ServerReadyPattern = 'Server started\.|Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    # levilamina: BDS server + LeviLamina C++ mod loader + novachat-levilamina.dll.
    # Server is native (BDS); backend uses JDK21. Bot is bedrock-protocol.
    levilamina = @{
        Dispatch           = "self"
        BackendScript      = "run-levilamina-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name bds -Auto")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "bds/bds.stdout.log"
        ServerReadyPattern = 'Server started\.|Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    # pmmp: PocketMine-MP phar + PHP runtime + NovaChat PHP source plugin.
    # Server is PHP (phar); backend uses JDK21. Bot is bedrock-protocol.
    pmmp = @{
        Dispatch           = "self"
        BackendScript      = "run-pmmp-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name pocketmine -Auto")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "pocketmine/pmmp.stdout.log"
        ServerReadyPattern = 'Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
}

# The default platforms list includes every platform with an IMPLEMENTED
# dispatch. The seven Java platforms route through run-multiplatform-e2e.ps1;
# the three Bedrock platforms (endstone/levilamina/pmmp) + bukkit use their
# self-contained scripts. Pass -Platforms folia,velocity,... to run a subset.
$Implemented = @('bukkit','endstone','levilamina','pmmp')

# Partition the requested platforms into "self" (one script per platform) and
# "multiplatform" (one shared orchestrator run for the whole Java subset). The
# shared orchestrator takes the Java platforms as a single -Platforms arg so it
# can reuse one backend-JVM + bot-deps install across the batch when desired.
$SelfPlatforms   = @()
$MultiPlatforms = @()
foreach ($p in $RequestedPlatforms) {
    $def = $PlatformDefs[$p]
    if (-not $def) {
        Log "ERROR: unknown platform '$p'. Known: $($PlatformDefs.Keys -join ', ')"
        $SelfPlatforms += $p   # surface the error in the loop below
        continue
    }
    if ($def.Dispatch -eq 'multiplatform') {
        $MultiPlatforms += $p
    } else {
        $SelfPlatforms += $p
    }
}

$results = @()

# --- dispatch the self-contained platforms (one script per platform) ---------
foreach ($p in $SelfPlatforms) {
    Log "=== platform: $p ==="
    $def = $PlatformDefs[$p]
    if (-not $def) {
        $results += [pscustomobject]@{ platform = $p; exitCode = 2; status = 'UNKNOWN' }
        continue
    }
    $script = Join-Path $binDir $def.BackendScript
    if (-not (Test-Path $script)) {
        Log "ERROR: required platform script is missing: $script"
        $results += [pscustomobject]@{ platform = $p; exitCode = 2; status = 'MISSING-HARNESS' }
        continue
    }
    $selfPs = Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile","-ExecutionPolicy","Bypass","-File",$script,"-RepoRoot",$RepoRoot,"-BotTimeoutSec",$BotWaitSec) `
        -PassThru -NoNewWindow -Wait
    $selfExit = $selfPs.ExitCode
    $results += [pscustomobject]@{ platform = $p; exitCode = $selfExit; status = if ($selfExit -eq 0) { 'PASS' } else { 'FAIL' } }
}

# --- dispatch the Java platforms via the shared multiplatform orchestrator ----
if ($MultiPlatforms.Count -gt 0) {
    $mpScript = Join-Path $multiplatformDir "run-multiplatform-e2e.ps1"
    if (-not (Test-Path $mpScript)) {
        Log "ERROR: run-multiplatform-e2e.ps1 not found at $mpScript"
        foreach ($p in $MultiPlatforms) {
            $results += [pscustomobject]@{ platform = $p; exitCode = 2; status = 'MISSING-HARNESS' }
        }
    } else {
        $mpPlatforms = $MultiPlatforms -join ','
        Log "=== multiplatform batch: $mpPlatforms ==="
        $mpPs = Start-Process -FilePath "powershell.exe" `
            -ArgumentList @("-NoProfile","-ExecutionPolicy","Bypass","-File",$mpScript,"-Platforms",$mpPlatforms,"-RepoRoot",$RepoRoot,"-TimeoutSec",$TimeoutSec,"-BotWaitSec",$BotWaitSec) `
            -PassThru -NoNewWindow -Wait
        $mpExit = $mpPs.ExitCode
        $status = if ($mpExit -eq 0) { 'PASS' } else { 'FAIL' }
        foreach ($p in $MultiPlatforms) {
            $results += [pscustomobject]@{ platform = $p; exitCode = $mpExit; status = $status }
        }
    }
}

Log "=== E2E summary ==="
$results | ForEach-Object { Log ("  {0,-12} {1}" -f $_.platform, $_.status) }
$anyFail = ($results | Where-Object { $_.exitCode -ne 0 }).Count -gt 0
if ($anyFail) { exit 1 } else { exit 0 }
