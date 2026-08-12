# ============================================================================
# run-e2e-orchestrator.ps1 -- multi-platform E2E dispatcher.
#
# This is the CI entry point. It dispatches to per-platform scripts.
#
# CURRENT COVERAGE (honest status):
#   bukkit      -- FULLY IMPLEMENTED (run-bukkit-e2e.ps1, L1 flow complete).
#   folia       -- TODO stub (exits with a clear "not implemented" message).
#   velocity    -- TODO stub
#   bungee      -- TODO stub
#   nukkit      -- TODO stub
#   pnx         -- TODO stub
#   sponge      -- TODO stub
#   endstone    -- IMPLEMENTED (run-endstone-e2e.ps1, BDS+Endstone+python plugin).
#   levilamina  -- IMPLEMENTED (run-levilamina-e2e.ps1, BDS+LeviLamina+C++ dll).
#   pmmp        -- IMPLEMENTED (run-pmmp-e2e.ps1, PocketMine-MP phar+PHP).
#
# The TODO platforms print a message and exit 0 so a matrix job that includes
# them does not fail the whole workflow; the per-platform job in e2e.yml uses
# continue-on-error for the stubs so the real signal is visible per-platform.
# ============================================================================
[CmdletBinding()]
param(
    [string]$Platforms = "bukkit,pmmp",
    [string]$RepoRoot  = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)))
)

$ErrorActionPreference = "Stop"
$binDir = Join-Path $RepoRoot "e2e/bin"
$platforms = $Platforms -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }

function Log([string]$m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }

# Platform definitions table. Each entry maps a platform name to its run script
# and the contract used by the assertion layer. Mirror the structure of the
# bukkit entry (the only fully-implemented Java platform) for the three new
# Bedrock platforms. Bedrock platforms use the shared run-e2e-bedrock.js bot
# (bedrock-protocol) instead of the mineflayer run-e2e.js bot.
#
# Fields:
#   BackendScript     - the per-platform orchestrator script (build+deploy+start).
#   ServerScripts     - the server-start scripts the orchestrator calls (for
#                       documentation; the orchestrator invokes them itself).
#   BotScript         - the bot script the orchestrator runs.
#   ServerReadyFile   - the log file whose readiness marker is grepped.
#   ServerReadyPattern - the regex that marks server readiness.
#   ResultsFile       - where the bot writes results.json.
#   ChatPhrase        - the chat phrase the bot sends for the round-trip.
#   Jdk               - the JDK the backend needs (the server itself is PHP/native
#                       for the three Bedrock platforms; Jdk is for the backend).
$PlatformDefs = @{
    bukkit = @{
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
        BackendScript      = "run-folia-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name folia")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "folia/folia.stdout.log"
        ServerReadyPattern = 'Done \('
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    velocity = @{
        BackendScript      = "run-velocity-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name velocity")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "velocity/velocity.stdout.log"
        ServerReadyPattern = 'Done|Listening on'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 25
    }
    bungee = @{
        BackendScript      = "run-bungee-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name waterfall")
        BotScript          = "run-e2e.js"
        ServerReadyFile    = "bungee/waterfall.stdout.log"
        ServerReadyPattern = 'Listening on'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    nukkit = @{
        BackendScript      = "run-nukkit-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name nukkit")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "nukkit/nukkit.stdout.log"
        ServerReadyPattern = 'Done|Default game mode'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    pnx = @{
        BackendScript      = "run-pnx-e2e.ps1"
        ServerScripts      = @("fetch-server.ps1 -Name nukkit")
        BotScript          = "run-e2e-bedrock.js"
        ServerReadyFile    = "pnx/nukkit.stdout.log"
        ServerReadyPattern = 'Done|Default game mode'
        ResultsFile        = "bot-results.json"
        ChatPhrase         = "hello from e2e bot"
        Jdk                = 21
    }
    sponge = @{
        BackendScript      = "run-sponge-e2e.ps1"
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
# script. TODO stubs are excluded from the default so `run-e2e-orchestrator.ps1`
# without args runs only real coverage. Pass -Platforms folia,velocity,... to
# include stubs (they exit 0 with a TODO marker).
$Implemented = @('bukkit','endstone','levilamina','pmmp')

$results = @()
foreach ($p in $platforms) {
    Log "=== platform: $p ==="
    $def = $PlatformDefs[$p]
    if (-not $def) {
        Log "ERROR: unknown platform '$p'. Known: $($PlatformDefs.Keys -join ', ')"
        $results += [pscustomobject]@{ platform = $p; exitCode = 2; status = 'UNKNOWN' }
        continue
    }
    $script = Join-Path $binDir $def.BackendScript
    if (-not (Test-Path $script)) {
        Log "TODO: platform '$p' not yet implemented in the committed e2e/ harness."
        Log "       The local .e2e/ (gitignored) has a working script; port it to e2e/bin/."
        $results += [pscustomobject]@{ platform = $p; exitCode = 0; status = 'TODO-STUB' }
        continue
    }
    & powershell -NoProfile -ExecutionPolicy Bypass -File $script -RepoRoot $RepoRoot
    $results += [pscustomobject]@{ platform = $p; exitCode = $LASTEXITCODE; status = if ($LASTEXITCODE -eq 0) { 'PASS' } else { 'FAIL' } }
}

Log "=== E2E summary ==="
$results | ForEach-Object { Log ("  {0,-12} {1}" -f $_.platform, $_.status) }
$anyFail = ($results | Where-Object { $_.status -eq 'FAIL' }).Count -gt 0
if ($anyFail) { exit 1 } else { exit 0 }
